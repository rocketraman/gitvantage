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

    internal fun record(repo: String, args: List<String>, res: Git.Result, durationMs: Long) {
        val combined = listOf(res.out, res.err).filter { it.isNotBlank() }.joinToString("\n").trimEnd()
        val entry = GitCommand(
            seq.getAndIncrement(), repo, "git " + args.joinToString(" "), res.code, durationMs, combined,
        )
        _entries.update { (it + entry).takeLast(MAX) }
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
