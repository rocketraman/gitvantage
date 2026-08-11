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

    val now = 1_800_000_000_000L   // fixed clock; the policy takes it as a parameter for this reason

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
