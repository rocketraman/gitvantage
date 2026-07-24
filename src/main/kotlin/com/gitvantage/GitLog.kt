// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

import androidx.compose.runtime.mutableStateListOf
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Records the side-effecting `git` commands the app runs (push, fetch, switch, commit,
 * stash, branch delete, fast-forward, …) so the user can open a console and see exactly
 * what happened. Read-only scans (status/log/rev-list during [RepoScanner]) are NOT logged
 * — they'd flood the console with polling noise.
 *
 * [exec] is the single choke point that runs a logged git command; both [GitOps] and
 * [BranchOps] route their mutating operations through it. Output keeps its raw ANSI escapes
 * (git is invoked with `color.ui=always`); the console parses them into colored spans.
 */
object GitLog {

    data class Entry(
        val seq: Long,
        val repo: String,
        val command: String,   // "git push -u origin HEAD"
        val exitCode: Int,
        val durationMs: Long,
        val output: String,    // combined stdout+stderr, may contain ANSI SGR escapes
    )

    /** Newest entries last. Observed by the console overlay. Capped so it can't grow unbounded. */
    val entries = mutableStateListOf<Entry>()
    private const val MAX = 500
    @Volatile private var seq = 0L

    data class Result(val code: Int, val out: String, val err: String)

    /**
     * Run `git [args]` in [dir], record it, and return the result. When [color] is true the
     * command is prefixed with `-c color.ui=always` so git emits ANSI colors even though it's
     * writing to a pipe (the recorded/displayed args exclude that prefix, for readability).
     */
    fun exec(repoName: String, dir: File, args: List<String>, timeoutSeconds: Long = 60, color: Boolean = true): Result {
        val full = (if (color) listOf("-c", "color.ui=always") else emptyList()) + args
        val start = System.currentTimeMillis()
        val res = try {
            val proc = ProcessBuilder(listOf("git") + full).directory(dir).start()
            val out = proc.inputStream.bufferedReader().readText()
            val err = proc.errorStream.bufferedReader().readText()
            if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                proc.destroyForcibly(); Result(-1, out, "timed out")
            } else Result(proc.exitValue(), out, err)
        } catch (e: Exception) {
            Result(-1, "", e.message ?: "failed to run git")
        }
        record(repoName, args, res, System.currentTimeMillis() - start)
        return res
    }

    private fun record(repo: String, args: List<String>, res: Result, durationMs: Long) {
        val combined = listOf(res.out, res.err).filter { it.isNotBlank() }.joinToString("\n").trimEnd()
        entries.add(Entry(seq++, repo, "git " + args.joinToString(" "), res.code, durationMs, combined))
        while (entries.size > MAX) entries.removeAt(0)
    }

    fun clear() = entries.clear()
}
