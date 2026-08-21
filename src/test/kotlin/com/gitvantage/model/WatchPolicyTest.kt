// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.model

import de.infix.testBalloon.framework.core.testSuite
import java.nio.file.Path

/**
 * The filesystem watcher's debounce.
 *
 * The starvation case is the one worth pinning: a reset-only debounce passes every test you would
 * think to write about coalescing, and still never scans at all under continuous churn. That is how
 * a repo with a coding session running in `.claude/worktrees/` stopped showing its own uncommitted
 * changes — the scan that would have found them was rescheduled a few hundred times a second and
 * never once ran.
 */
val WatchDebounce by testSuite {
    val now = 1_800_000_000_000L // fixed clock; the policy takes it as a parameter for this reason

    test("a lone event waits out the quiet period") {
        val deadline = WatchPolicy.deadlineFor(now)
        assert(WatchPolicy.delayMs(now, deadline) == WatchPolicy.QUIET_MS)
    }

    test("events arriving mid-burst still wait a full quiet period, so a burst costs one scan") {
        val deadline = WatchPolicy.deadlineFor(now)
        // Far enough from the deadline that the quiet period fits: each new event pushes the scan
        // back to 800ms after itself, which is the coalescing the debounce exists for.
        assert(WatchPolicy.delayMs(now + 1_000, deadline) == WatchPolicy.QUIET_MS)
        assert(WatchPolicy.delayMs(now + 3_000, deadline) == WatchPolicy.QUIET_MS)
    }

    test("the wait shrinks rather than overrunning the deadline") {
        val deadline = WatchPolicy.deadlineFor(now)
        // 300ms left of the burst's budget: waiting the full quiet period would overshoot it.
        val late = deadline - 300
        assert(WatchPolicy.delayMs(late, deadline) == 300L)
    }

    test("a burst that never goes quiet still scans — the starvation this exists to prevent") {
        val deadline = WatchPolicy.deadlineFor(now)
        // Events every 10ms forever: a reset-only debounce would postpone the scan on every one of
        // them and never fire. Walk the burst and assert the wait actually reaches zero.
        var t = now
        var fired = false
        while (t <= now + WatchPolicy.MAX_WAIT_MS) {
            if (WatchPolicy.delayMs(t, deadline) == 0L) { fired = true; break }
            t += 10
        }
        assert(fired) { "a continuous stream of events never reached a scan" }
        // And it fires at the ceiling, not before — the coalescing is not given up early.
        assert(t == now + WatchPolicy.MAX_WAIT_MS)
    }

    test("an event past the deadline scans immediately rather than waiting again") {
        val deadline = WatchPolicy.deadlineFor(now)
        assert(WatchPolicy.delayMs(deadline + 5_000, deadline) == 0L)
    }

    /**
     * The same debounce stated as a moment rather than a wait, for the scheduler that sleeps until
     * the earliest repo is due instead of waiting out a timer per event. The two have to agree about
     * when a burst ends or repos scan early, late, or — the case that bites — never.
     */
    test("a lone event is due one quiet period after itself") {
        val deadline = WatchPolicy.deadlineFor(now)
        assert(WatchPolicy.dueAt(now, deadline) == now + WatchPolicy.QUIET_MS)
    }

    test("each event pushes the due time out, but never past the burst's ceiling") {
        val deadline = WatchPolicy.deadlineFor(now)
        assert(WatchPolicy.dueAt(now + 1_000, deadline) == now + 1_000 + WatchPolicy.QUIET_MS)
        // Late in the burst the quiet period would overshoot, so the ceiling wins.
        assert(WatchPolicy.dueAt(deadline - 300, deadline) == deadline)
        assert(WatchPolicy.dueAt(deadline + 5_000, deadline) == deadline + 5_000)
    }

    test("a burst that never goes quiet becomes due at the ceiling, not never") {
        val deadline = WatchPolicy.deadlineFor(now)
        // Events every 10ms: the due time must stop moving once it reaches the ceiling, which is
        // what stops a continuous stream from postponing its scan forever.
        var t = now
        while (t <= now + WatchPolicy.MAX_WAIT_MS) {
            assert(WatchPolicy.dueAt(t, deadline) <= deadline)
            t += 10
        }
    }

    test("the due time and the wait describe the same instant") {
        val deadline = WatchPolicy.deadlineFor(now)
        // Stated twice in the code, so pin them together: a scheduler that sleeps to dueAt and one
        // that waits delayMs must not be able to disagree.
        listOf(now, now + 1_000, deadline - 300, deadline, deadline + 5_000).forEach { t ->
            assert(WatchPolicy.dueAt(t, deadline) == t + WatchPolicy.delayMs(t, deadline))
        }
    }
}

/**
 * Which filesystem events count as the repo changing.
 *
 * The recursive watch covers directories git ignores, so it sees a coding session's worktree that
 * no git command run against the parent ever mentions. Attributing that session's writes to the
 * parent is what starved the debounce above.
 */
val WatchEventRouting by testSuite {

    val repo = Path.of("/src/gitvantage")
    val nested = listOf(Path.of("/src/gitvantage/.claude/worktrees/feat"))

    test("a file in the repo's own tree concerns it") {
        assert(WatchPolicy.concernsRepo(nested, listOf(repo.resolve("src/main/App.kt"))))
    }

    test("a file inside a nested worktree does not") {
        assert(!WatchPolicy.concernsRepo(nested, listOf(nested.single().resolve("src/main/App.kt"))))
    }

    test("the repo's own .git is not a nested checkout — commits and branch switches still count") {
        assert(WatchPolicy.concernsRepo(nested, listOf(repo.resolve(".git/HEAD"))))
    }

    test("a repo with no nested worktrees takes everything") {
        assert(WatchPolicy.concernsRepo(emptyList(), listOf(repo.resolve("anything"))))
    }

    test("a sibling sharing a name prefix is not treated as nested") {
        // "…/worktrees/feat-two" starts with "…/worktrees/feat" as a string but is a different tree.
        val sibling = Path.of("/src/gitvantage/.claude/worktrees/feat-two/App.kt")
        assert(WatchPolicy.concernsRepo(nested, listOf(sibling)))
    }

    test("a move out of a nested worktree into the repo concerns it") {
        // Moved events carry both ends; one of them landing in the repo is a real change to it.
        val from = nested.single().resolve("App.kt")
        val to = repo.resolve("App.kt")
        assert(WatchPolicy.concernsRepo(nested, listOf(from, to)))
    }

    test("an event naming no path concerns the repo — an overflow must not be swallowed") {
        assert(WatchPolicy.concernsRepo(nested, emptyList()))
    }
}

/**
 * What each event is turned into — and above all, that a dropped-events signal survives the trip.
 *
 * The bug this pins: the watcher reports an overflow with *no source*, because it is speaking for
 * itself rather than for any one registration. The collector routed every event by its source's
 * name, so that one fell off the end — the app lost events and lost the only notice that it had.
 * The repo then sat unrefreshed until the next poll with nothing on screen to explain it.
 *
 * The negative cases are the ones that matter here. Asserting an ordinary event still routes proves
 * nothing about a signal that arrives shaped differently from every other event.
 */
val WatchActionRouting by testSuite {

    val id = "/src/gitvantage"
    val nested = listOf("/src/gitvantage/.claude/worktrees/feat")
    val inNested = listOf(Path.of("/src/gitvantage/.claude/worktrees/feat/App.kt"))
    val inRepo = listOf(Path.of("/src/gitvantage/src/main/App.kt"))

    test("an overflow with no source rescans everything — it cannot say which repo it lost") {
        val action = WatchPolicy.actionFor(id = null, needsRescan = true, nested = emptyList()) { emptyList() }
        assert(action == WatchAction.RescanAll) { "a dropped-events signal was swallowed: $action" }
    }

    test("a rescan signal that does name a repo rescans that repo") {
        val action = WatchPolicy.actionFor(id, needsRescan = true, nested = emptyList()) { emptyList() }
        assert(action == WatchAction.Rescan(id))
    }

    test("a rescan signal is not filtered by the nested-worktree rule, whatever paths it carries") {
        // The filter answers "did this write change the repo". A rescan signal says events were
        // *lost*, so the paths it happens to carry say nothing about what went missing — routing it
        // through the filter would drop it for naming a session's worktree, which is the busiest
        // tree in the repo and so the likeliest one attached to an overflow.
        val action = WatchPolicy.actionFor(id, needsRescan = true, nested = nested) { inNested }
        assert(action == WatchAction.Rescan(id)) { "a rescan signal was filtered like a path event: $action" }
    }

    test("an ordinary event with no source is ignored — it names nothing to scan") {
        val action = WatchPolicy.actionFor(id = null, needsRescan = false, nested = emptyList()) { inRepo }
        assert(action == WatchAction.Ignore)
    }

    test("an ordinary event in the repo's own tree rescans it") {
        assert(WatchPolicy.actionFor(id, needsRescan = false, nested = nested) { inRepo } == WatchAction.Rescan(id))
    }

    test("an ordinary event inside a nested worktree is ignored") {
        assert(WatchPolicy.actionFor(id, needsRescan = false, nested = nested) { inNested } == WatchAction.Ignore)
    }

    test("a repo with no nested worktrees never builds the path list") {
        // The common case runs once per filesystem event, so it must not allocate to reach an answer
        // it already has. Proven by a lambda that fails the test if it is ever called.
        var called = false
        val action = WatchPolicy.actionFor(id, needsRescan = false, nested = emptyList()) {
            called = true
            inRepo
        }
        assert(action == WatchAction.Rescan(id))
        assert(!called) { "the common path allocated an event's path list to reach a known answer" }
    }
}
