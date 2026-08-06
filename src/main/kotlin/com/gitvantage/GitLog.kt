// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

import androidx.compose.runtime.mutableStateListOf

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

    internal fun record(repo: String, args: List<String>, res: Git.Result, durationMs: Long) {
        val combined = listOf(res.out, res.err).filter { it.isNotBlank() }.joinToString("\n").trimEnd()
        entries.add(Entry(seq++, repo, "git " + args.joinToString(" "), res.code, durationMs, combined))
        while (entries.size > MAX) entries.removeAt(0)
    }

    fun clear() = entries.clear()
}
