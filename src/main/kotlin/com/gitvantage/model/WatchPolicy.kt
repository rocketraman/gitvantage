// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.model

import java.nio.file.Path

/** What one filesystem event asks the app to do. See [WatchPolicy.actionFor]. */
sealed interface WatchAction {
    /** Nothing the repo cares about happened — a write inside a checkout nested in its folder. */
    data object Ignore : WatchAction

    /** Rescan the one repo the event was delivered under. */
    data class Rescan(val id: String) : WatchAction

    /** Rescan every tracked repo: events were dropped, and nothing says which repo they were for. */
    data object RescanAll : WatchAction
}

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
     * How many events the watcher may buffer before it starts dropping them.
     *
     * The library's default is 64, which is sized for an app watching a handful of roots. This one
     * watches every tracked repo recursively, and a single `npm ci` or Gradle build outruns 64
     * events in well under a second — at which point the watcher drops what it cannot hand over and
     * reports a rescan instead (see [actionFor]). A dropped event costs a repo its live refresh
     * until the next poll, so the buffer is sized to absorb a build rather than to be economical:
     * the events are small, and 8192 of them is noise against a dashboard holding every repo's file
     * list.
     *
     * This raises the ceiling; it does not remove it. A collector that cannot keep up will still
     * overflow eventually, which is why [actionFor] has to handle the signal rather than assume it
     * never arrives.
     */
    const val EVENT_BUFFER = 8192

    /**
     * The window the watcher's own native debounce coalesces over, before an event is handed to the
     * JVM at all.
     *
     * The library defaults to 150ms. Coalescing is per path, so this collapses the repeated writes
     * one editor save or one compiler output makes to the same file — the bulk of ordinary churn —
     * without touching a build that writes ten thousand *distinct* files. Worth raising anyway: it
     * is the only reduction available before the event crosses into the JVM and reaches the
     * collector, and the collector is the part under pressure.
     */
    const val NATIVE_DEBOUNCE_MS = 500L

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

    /**
     * What to do about one event: [id] is the repo it was delivered under (null when the watcher is
     * speaking for itself rather than for a registration), [needsRescan] is the watcher saying it
     * dropped events, and [nested] are the checkouts inside [id]'s folder that [concernsRepo]
     * filters against.
     *
     * **A rescan signal is not a path event and must not be filtered like one.** It means "events
     * were lost, go and look" — so it bypasses [concernsRepo] entirely, whatever paths happen to be
     * attached. And when it names no repo, the only honest answer is to rescan all of them: the
     * watcher is reporting a loss it cannot attribute, and picking a repo would be a guess.
     *
     * This is the case that used to fall through the floor. A watcher-level overflow arrives with no
     * source, the collector reached for the source's name to route it, and the whole event vanished
     * — so the app dropped events *and* dropped the one signal that says events were dropped. The
     * repo then sat unrefreshed until the next poll with nothing on screen to say why, which is
     * precisely the failure [concernsRepo]'s empty-paths case was written to prevent and could not,
     * because it was never reached.
     *
     * [paths] is a lambda so an ordinary event on a repo with no nested checkouts — nearly every
     * event — costs no allocation at all. This runs once per filesystem event.
     */
    fun actionFor(
        id: String?,
        needsRescan: Boolean,
        nested: List<String>,
        paths: () -> List<Path>,
    ): WatchAction = when {
        id == null -> if (needsRescan) WatchAction.RescanAll else WatchAction.Ignore
        needsRescan -> WatchAction.Rescan(id)
        nested.isEmpty() -> WatchAction.Rescan(id)
        concernsRepo(nested.map(Path::of), paths()) -> WatchAction.Rescan(id)
        else -> WatchAction.Ignore
    }
}
