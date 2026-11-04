// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git

import com.gitvantage.model.Activity
import com.gitvantage.model.Perf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * The one place the app spawns `git`.
 *
 * Everything that shells out goes through [spawn]: the mutating operations in [RepoOps],
 * [BranchOps], [WorktreeOps] and [SubmoduleOps] via [run], and the read-only queries in
 * [RepoScanner], [DiffOps], [LogOps] and the `*Ops` list functions via [read], [readOrNull]
 * and [exitCode]. Each of those files used to carry its own near-identical `ProcessBuilder`
 * helper, which meant eight arbitrary timeouts and eight copies of every process-handling
 * subtlety — including the stderr deadlock [spawn] documents.
 *
 * The one deliberate exception is [Actions.openGitGui], which *launches* `git gui` detached
 * rather than running a command: nothing is captured and nothing is waited on, so it belongs
 * with the other "Open in …" launchers and has none of the concerns here. It does not bypass
 * [GitLog] though — it records the launch itself, since the user commits from inside it.
 *
 * Logging is opt-in per call and defaults to on for [run]: side-effecting commands are what
 * the console in [GitLog] exists to show, and read-only polling would flood it.
 */
object Git {

    data class Result(val code: Int, val out: String, val err: String) {
        val ok: Boolean get() = code == 0

        /**
         * The one line of a failed command worth putting in a toast.
         *
         * Both streams are searched, because git splits its explanation across them: a failed
         * `commit` says "nothing to commit" on *stdout* and leaves stderr empty, while a failed
         * `push` writes everything to stderr. ANSI escapes are stripped — the commands are asked
         * to emit them for the console, and they arrive as literal garbage anywhere else.
         *
         * Two kinds of line are skipped, because neither can ever be the reason:
         *
         *  - `To <url>` — a push echoes its destination *before* saying anything about the
         *    outcome, so taking the first stderr line verbatim reports the remote's address as
         *    though it were the error.
         *  - `remote:` — the server's prose, which git wraps mid-sentence at ~72 columns. Skipping
         *    it lands on git's own `! [rejected] <ref> (<reason>)` summary line instead, which is
         *    built to be exactly this: one line, the ref, and why it was refused.
         *
         * The fallback chain keeps something rather than nothing: stderr's first line when every
         * line was skipped, then [fallback] when git said nothing legible at all.
         */
        fun firstError(fallback: String): String {
            fun clean(s: String) = s.lineSequence().map { stripAnsi(it).trim() }
            return clean(err + "\n" + out)
                .firstOrNull { it.isNotEmpty() && !it.startsWith("To ") && !it.startsWith("remote:") }
                ?: clean(err).firstOrNull { it.isNotEmpty() }
                ?: fallback
        }
    }

    private val ANSI = Regex("\\u001B\\[[0-9;]*m")

    /** Remove git's SGR color escapes from text destined for anywhere but the console. */
    fun stripAnsi(s: String): String = ANSI.replace(s, "")

    /** Read-only queries. Uniform, where the per-file helpers used an arbitrary 15/20/30s each. */
    const val READ_TIMEOUT = 30L

    /** Local mutations — commit, switch, branch delete, worktree remove. */
    const val RUN_TIMEOUT = 60L

    /** Anything that talks to a remote: push, fetch, publish. */
    const val NETWORK_TIMEOUT = 90L

    /**
     * Run `git [args]` in [dir] and return its exit code, stdout and stderr.
     *
     * Recorded to [GitLog] unless [log] is false — pass false for a read that happens to want
     * stderr or an exact exit code, so the console stays a log of what the app *changed*.
     * [color] adds `-c color.ui=always` so git emits ANSI escapes even though it's writing to a
     * pipe (the console parses them into colored spans); it follows [log], since the console is
     * the only thing that renders them.
     */
    fun run(
        dir: File,
        args: List<String>,
        timeoutSeconds: Long = RUN_TIMEOUT,
        log: Boolean = true,
        repoName: String = dir.name,
        color: Boolean = log,
    ): Result {
        val full = (if (color) listOf("-c", "color.ui=always") else emptyList()) + args
        val start = System.currentTimeMillis()
        val res = spawn(dir, full, timeoutSeconds, captureErr = true)
        // The recorded command excludes the color prefix — it's noise in a console listing.
        if (log) GitLog.record(repoName, "git", args, res.code, res.out, res.err, System.currentTimeMillis() - start)
        return res
    }

    /** Stdout of a read-only `git [args]`, or "" on any failure. The common query shape. */
    fun read(dir: File, vararg args: String, timeoutSeconds: Long = READ_TIMEOUT): String =
        spawn(dir, args.toList(), timeoutSeconds, captureErr = false, readOnly = true).let { if (it.ok) it.out else "" }

    /** Stdout of a read-only `git [args]`, or null on any failure — for callers that need to tell
     *  "the command failed" from "the command succeeded and said nothing". */
    fun readOrNull(dir: File, vararg args: String, timeoutSeconds: Long = READ_TIMEOUT): String? =
        spawn(dir, args.toList(), timeoutSeconds, captureErr = false, readOnly = true).let { if (it.ok) it.out else null }

    /** Exit code only, for git's yes/no questions (`merge-base --is-ancestor`). -1 if it never ran. */
    fun exitCode(dir: File, vararg args: String, timeoutSeconds: Long = READ_TIMEOUT): Int =
        spawn(dir, args.toList(), timeoutSeconds, captureErr = false, readOnly = true).code

    /**
     * Whether [dir] is a git working tree or repository.
     *
     * `.git` is a directory in an ordinary clone but a *file* pointing at the real gitdir in a
     * linked worktree or a submodule, so this asks only that the entry exists — both are repos
     * as far as every caller here is concerned, and an `isDirectory` test would quietly report
     * "not a git repo" for exactly the worktrees and submodules the app is built to show.
     */
    fun isRepo(dir: File): Boolean = File(dir, ".git").exists()

    fun isRepo(path: String): Boolean = isRepo(File(path))

    /**
     * The git subcommand in [args] — "status" out of `-c color.ui=always status --porcelain`.
     *
     * Global options come first and some of them *take a value*, which is the whole difficulty:
     * `-c` is followed by `color.ui=always`, a token that begins with no dash and so reads as the
     * subcommand to anything scanning for the first non-flag word. That is exactly what the perf
     * counters did, and it filed every logged mutation — fetch, push, commit — under a bucket named
     * `color.ui=always`, where a slow fetch looked like an unexplained mystery command.
     *
     * `-C <path>` gets the same treatment for the same reason. Options spelled `--opt=value` are a
     * single token and need no special case.
     */
    fun subcommandOf(args: List<String>): String {
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-c" || a == "-C" -> i += 2 // a global option and the value it consumes
                a.startsWith("-") -> i += 1
                else -> return a
            }
        }
        return "?"
    }

    // Counted always, not only while collecting — see [Activity] for who reads it and why.
    private fun spawn(dir: File, args: List<String>, timeoutSeconds: Long, captureErr: Boolean, readOnly: Boolean = false): Result =
        Activity.git { spawnMeasured(dir, args, timeoutSeconds, captureErr, readOnly) }

    private fun spawnMeasured(dir: File, args: List<String>, timeoutSeconds: Long, captureErr: Boolean, readOnly: Boolean): Result =
        // Keyed on the subcommand, not the full argument list: "status" is the useful grouping, and
        // the arguments would make a cardinality explosion out of a handful of real commands.
        //
        // `.chars` is how much each subcommand hands back, because a count and a time can't say
        // whether git output is what the garbage collector is busy with. A field report showed ~2.5
        // young collections a second against a ~410MB young generation — about 1GB/s allocated —
        // which a fixture of 44 small repos at the same scan rate came nowhere near (~1-2MB/s, on
        // the JVM and in the native image alike). What real repos return, a squash check's full
        // branch diff above all, is the difference that leaves, and this measures it.
        Perf.concurrent("proc.git") {
            val name = "git." + subcommandOf(args)
            Perf.timed(name) {
                spawnNow(dir, args, timeoutSeconds, captureErr, readOnly)
            }.also { if (Perf.enabled) Perf.count("$name.chars", (it.out.length + it.err.length).toLong()) }
        }

    /**
     * Start `git`, read it to completion, and never wedge.
     *
     * When stderr is wanted it is drained on its own thread. git can fill the pipe buffer
     * (~64KB — a push with a long remote message, a chatty hook) and block writing to it while
     * this thread is still reading stdout to EOF: neither side moves, git never exits, and the
     * call hangs until the timeout. Reading the two concurrently is the only shape that can't
     * deadlock. When stderr isn't wanted it's discarded by the OS, which can't fill and so needs
     * no thread — that keeps the read path, which runs many times per repo per scan, as cheap as
     * the per-file helpers it replaces.
     *
     * [readOnly] queries run with `GIT_OPTIONAL_LOCKS=0`. Without it `git status` refreshes the
     * index's stat cache and writes `.git/index` back — even with nothing to commit — and the
     * repo's watch is recursive, `.git` included. So a scan's own status call was a filesystem
     * event that scheduled the next scan, and in a repo whose files keep changing that loop never
     * settled: git spawned around the clock, and on macOS every spawn is a `fork()` that holds the
     * malloc and Objective-C locks the UI thread needs, so the app grew laggier the longer it ran
     * (and the bigger the process got). Mutations through [run] keep the default; a commit or a
     * switch is expected to write the index, and the app is the one asking.
     *
     * [timeoutSeconds] is a second coroutine beside the read, which kills git *and everything it
     * started* when time runs out and is cancelled when the read finishes first. It used to be a `waitFor(timeout)` after reading stdout to EOF — which a hung
     * git never reaches, so no timeout ever fired: a field report had a `fetch` running for 2h 11m
     * against its 90s. The children matter as much as git: a fetch's `ssh`, or the shell behind an
     * alias, holds the same stdout, and the read only ends when the last of them lets go.
     */
    private fun spawnNow(dir: File, args: List<String>, timeoutSeconds: Long, captureErr: Boolean, readOnly: Boolean): Result = runCatching {
        val pb = ProcessBuilder(listOf("git") + args).directory(dir)
        if (!captureErr) pb.redirectError(ProcessBuilder.Redirect.DISCARD)
        if (readOnly) pb.environment()["GIT_OPTIONAL_LOCKS"] = "0"
        val proc = pb.start()

        var err = ""
        val pump = if (captureErr) {
            Thread { err = runCatching { proc.errorStream.bufferedReader().readText() }.getOrDefault("") }
                .apply { isDaemon = true; start() }
        } else null

        var timedOut = false
        // runBlocking returns only once the timeout coroutine has finished, cancelled or not, which
        // also makes its write to `timedOut` visible here.
        val out = runBlocking {
            // Not on runBlocking's own thread: that thread is about to block in the read, and a
            // timeout waiting for it to be free would never fire.
            val timeout = launch(Dispatchers.Default) {
                delay(timeoutSeconds.seconds)
                timedOut = true
                killTree(proc)
            }
            try {
                proc.inputStream.bufferedReader().readText().also { proc.waitFor() }
            } finally {
                timeout.cancel()
            }
        }
        // join() also publishes the pump thread's write to `err` to this thread.
        pump?.join(TimeUnit.SECONDS.toMillis(2))

        if (timedOut) Result(-1, out, "timed out") else Result(proc.exitValue(), out, err)
    }.getOrElse { e ->
        Result(-1, "", e.message ?: "failed to run git")
    }

    /**
     * Kill [proc] and its descendants. Children first, listed while git is still alive: once git
     * dies they are reparented, stop being its descendants, and keep the pipe open with nobody
     * left to find them.
     */
    private fun killTree(proc: Process) {
        runCatching { proc.descendants().forEach { it.destroyForcibly() } }
        proc.destroyForcibly()
    }
}
