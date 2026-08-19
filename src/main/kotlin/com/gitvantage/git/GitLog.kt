// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git

import com.gitvantage.git.model.GitCommand
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Records the side-effecting `git` commands the app runs (push, fetch, switch, commit,
 * stash, branch delete, fast-forward, …) so the user can open a console and see exactly
 * what happened. Read-only scans (status/log/rev-list during [RepoScanner]) are NOT logged
 * — they'd flood the console with polling noise.
 *
 * This is the recorder, not the runner: [Git] spawns every command and calls [record] for the
 * ones marked loggable, so a mutating operation cannot reach git without passing through here.
 * Output keeps its raw ANSI escapes (those commands are invoked with `color.ui=always`); the
 * console parses them into colored spans.
 *
 * [entries] is a [StateFlow] rather than Compose snapshot state on purpose: this package must stay
 * headless, so that a scan, a test or a future headless mode can run git without a UI toolkit on the
 * classpath. The console collects it with `collectAsState`, which recomposes just the same.
 */
object GitLog {

    private const val MAX = 500

    private val _entries = MutableStateFlow<List<GitCommand>>(emptyList())

    /** Newest entries last. Observed by the console overlay. Capped so it can't grow unbounded. */
    val entries: StateFlow<List<GitCommand>> = _entries.asStateFlow()

    // Atomic rather than @Volatile: `record` can be called from several IO threads at once, and a
    // read-increment-write on a volatile Long is not atomic — two commands could share a seq, which
    // is the key the console's list is diffed by.
    private val seq = AtomicLong(0)

    /**
     * Record a git client the app *launched* rather than ran — `git gui`, GitButler.
     *
     * These are started detached with both streams discarded, so there is no output to show and
     * no exit code to wait for. They are recorded anyway, because the user goes on to commit and
     * stage inside them: those are real mutations this console would otherwise have no trace of,
     * and the dashboard state jumps afterwards with nothing to explain why. The entry marks *when
     * the app handed the repo over*, which is the part the app actually knows.
     *
     * [command] is the whole argv and is written verbatim — unlike [record] these are not all
     * `git` subcommands, and captioning `but --path …` as a git command would misreport what ran.
     *
     * The output line states the gap in as many words rather than leaving a blank the reader
     * would take for "it printed nothing": a console that invents plausible command output is
     * worse than one that admits what it cannot see.
     */
    internal fun recordLaunch(repo: String, command: List<String>, started: Boolean) {
        val entry = GitCommand(
            seq.getAndIncrement(), repo, command.joinToString(" "),
            exitCode = if (started) 0 else -1,
            durationMs = 0,
            output = if (started) "(launched detached — its output is not captured here)"
            else "(could not be launched)",
        )
        _entries.update { (it + entry).takeLast(MAX) }
    }

    /** [program] is spelled by the caller rather than assumed: almost every entry is `git` via
     *  [Git.run], but `gh pr checkout` mutates the repo the same way and is recorded through
     *  here too — and the console must caption it as the command that actually ran. */
    internal fun record(repo: String, program: String, args: List<String>, code: Int, out: String, err: String, durationMs: Long) {
        val combined = listOf(out, err).filter { it.isNotBlank() }.joinToString("\n").trimEnd()
        val entry = GitCommand(
            seq.getAndIncrement(), repo, "$program " + args.joinToString(" "), code, durationMs, combined,
        )
        _entries.update { (it + entry).takeLast(MAX) }
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
