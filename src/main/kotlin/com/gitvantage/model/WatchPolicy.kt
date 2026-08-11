// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.model

import java.nio.file.Path

/**
 * How long the filesystem watcher waits before rescanning a repo whose files changed.
 *
 * A burst of events — one editor save is several, a checkout is thousands — should cost one scan,
 * so each event pushes the scan back to [QUIET_MS] after the *last* one. On its own that is a
 * reset-only debounce, and a reset-only debounce can be starved: while events keep arriving closer
 * together than the quiet period, the scan is postponed again every time and never runs at all.
 *
 * That is not hypothetical. A repo's watch is recursive, so it also covers directories git ignores
 * — `build/`, `.gradle/`, `node_modules/`, and the `.claude/worktrees/` a coding session works in.
 * Anything writing there continuously holds the repo's scan off indefinitely, and the detail panel
 * then shows the repo as it was when the churn started: changes made since are missing, and with
 * the counts stuck at zero so is the Diff button.
 *
 * [MAX_WAIT_MS] is the ceiling that makes starvation impossible — the scan runs that long after the
 * *first* event of a burst however busy the tree stays. Pure and clock-injected, so both halves can
 * be tested without waiting on a real clock.
 */
object WatchPolicy {

    /** Quiet period: how long after the last event a scan runs, when events stop arriving. */
    const val QUIET_MS = 800L

    /** Ceiling: the longest a burst can postpone the scan past its first event. */
    const val MAX_WAIT_MS = 5_000L

    /**
     * How long to wait before rescanning, for an event seen at [now] in a burst whose scan must
     * happen by [deadline].
     *
     * The quiet period, clamped so it can never push past the deadline; 0 once the deadline has
     * arrived, which is what makes a continuous stream scan on schedule rather than never.
     */
    fun delayMs(now: Long, deadline: Long): Long = (deadline - now).coerceIn(0L, QUIET_MS)

    /** The deadline for a burst whose first event arrived at [now]. */
    fun deadlineFor(now: Long): Long = now + MAX_WAIT_MS

    /**
     * Whether a change to [paths] is a change to the repo itself, rather than to one of the
     * checkouts [nested] inside its folder.
     *
     * A linked worktree under the repo — a coding session's `.claude/worktrees/<slug>` — has its own
     * index and its own status, and nothing written there can change what `git status` says in the
     * parent. git agrees, since the directory is gitignored; the recursive watch is the one place
     * that doesn't know it, and reports every file the session writes as the parent changing.
     *
     * Empty [paths] means the event named none — an Overflow, where the watcher is reporting that it
     * dropped events. That is treated as concerning the repo: swallowing the one event that says
     * "you have missed some" is how a repo goes stale until the next poll.
     */
    fun concernsRepo(nested: List<Path>, paths: List<Path>): Boolean {
        if (nested.isEmpty() || paths.isEmpty()) return true
        // Compared component-wise rather than as strings, so a sibling that merely shares a name
        // prefix ("…/worktrees/feat-two" against "…/worktrees/feat") is not read as nested.
        return paths.any { p -> nested.none { p.startsWith(it) } }
    }
}
