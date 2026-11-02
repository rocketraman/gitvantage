// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.model

import de.infix.testBalloon.framework.core.testSuite

/** [inDisplayOrder]: uncommitted work first, then newest commit, ties left in git's path order. */
val WorktreeDisplayOrder by testSuite {

    fun tree(name: String, dirty: Int = 0, epoch: Long? = null) = Worktree(
        path = "/w/$name", head = "abc1234", branch = name, bare = false,
        locked = false, lockReason = null, prunable = false, prunableReason = null,
        isMain = false, dirtyCount = dirty, lastEpoch = epoch,
    )

    fun names(trees: List<Worktree>) = trees.inDisplayOrder().map { it.branch }

    test("a dirty tree comes before a clean one, however old its last commit") {
        assert(names(listOf(tree("clean", epoch = 200), tree("dirty", dirty = 3, epoch = 100))) == listOf("dirty", "clean"))
    }

    test("within each group, the most recent commit comes first") {
        val trees = listOf(
            tree("a", epoch = 100), tree("b", dirty = 1, epoch = 100),
            tree("c", epoch = 300), tree("d", dirty = 2, epoch = 300),
        )
        assert(names(trees) == listOf("d", "b", "c", "a"))
    }

    test("a tree with no commit time sorts last in its group, and ties keep git's order") {
        val trees = listOf(tree("gone"), tree("x", epoch = 100), tree("y"), tree("z", epoch = 100))
        assert(names(trees) == listOf("x", "z", "gone", "y"))
    }
}
