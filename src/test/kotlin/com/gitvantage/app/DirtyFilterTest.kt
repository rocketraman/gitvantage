// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.app

import androidx.compose.ui.graphics.Color
import com.gitvantage.model.Repo
import com.gitvantage.model.Worktree
import com.gitvantage.model.WorktreeAlerts
import de.infix.testBalloon.framework.core.testSuite

/**
 * What the Dirty filter selects: uncommitted work in the repo's own checkout *or* in any of its
 * linked worktrees — while the row's own [RepoView.isDirty], which drives its badges and Commit
 * button, stays about the checkout the row stands for.
 */
val DirtyFilter by testSuite {

    fun tree(dirty: Int) = Worktree(
        path = "/w/side", head = "abc1234", branch = "side", bare = false,
        locked = false, lockReason = null, prunable = false, prunableReason = null,
        isMain = false, dirtyCount = dirty,
    )

    fun view(unstaged: Int = 0, treeDirty: Int? = null, treeSnoozed: Boolean = false) = deriveView(
        Repo(id = "/w", name = "w", branch = "main", unstaged = unstaged),
        Color.Unspecified, emptyList(),
        worktrees = listOfNotNull(treeDirty?.let {
            WorktreeView(
                wt = tree(it), repoId = "/w", alerts = WorktreeAlerts(),
                snoozed = treeSnoozed, snoozedFor = if (treeSnoozed) "2d" else null, parentAlertsOn = true,
            )
        }),
    )

    test("a clean checkout with a dirty worktree is dirty for the filter, not for the row") {
        val v = view(treeDirty = 3)
        assert(v.dirtyAnywhere)
        assert(!v.isDirty)
    }

    test("a dirty checkout is dirty either way") {
        assert(view(unstaged = 1).dirtyAnywhere)
        assert(view(unstaged = 1, treeDirty = 0).dirtyAnywhere)
    }

    test("clean everywhere stays out of the filter") {
        assert(!view().dirtyAnywhere)
        assert(!view(treeDirty = 0).dirtyAnywhere)
    }

    test("a snoozed worktree still counts — a snooze doesn't make a tree clean") {
        assert(view(treeDirty = 2, treeSnoozed = true).dirtyAnywhere)
    }
}
