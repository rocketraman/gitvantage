// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git

import de.infix.testBalloon.framework.core.testSuite

/** A `feature` branch with two commits of its own, cut from main, with main moved on since. */
private fun Sandbox.featureRepo(): java.io.File {
    val work = repo()
    git(work, "switch", "-q", "-c", "feature")
    commit(work, "a.txt", "two\n", "feature: change a")
    commit(work, "b.txt", "bee\n", "feature: add b")
    git(work, "switch", "-q", Sandbox.MAIN)
    commit(work, "c.txt", "sea\n", "unrelated work on main")
    return work
}

/** Land `feature` on main as one new commit, the way a squash-merged PR does. */
private fun Sandbox.squash(work: java.io.File) {
    git(work, "merge", "-q", "--squash", "feature")
    git(work, "commit", "-q", "-m", "Feature (#1)")
}

/** Branches merged by content rather than history — squash and rebase merges. */
val SquashMerge by testSuite {
    testFixture { Sandbox() } asContextForEach {

        test("a squash-merged branch reads as merged, though git calls it unmerged") {
            val work = featureRepo()
            squash(work)
            // The premise: by ancestry, the branch never landed.
            assert(gitAllowingFailure(work, "merge-base", "--is-ancestor", "feature", Sandbox.MAIN) != 0)

            val feature = BranchOps.load(work.path).single { it.name == "feature" }

            assert(feature.merged && feature.squashed && !feature.empty)
        }

        test("a rebase-merged branch reads as merged") {
            val work = featureRepo()
            git(work, "cherry-pick", "${Sandbox.MAIN}..feature")

            val feature = BranchOps.load(work.path).single { it.name == "feature" }

            assert(feature.merged && feature.squashed)
        }

        test("a squash that main has since edited over is still found, by its patch") {
            val work = featureRepo()
            squash(work)
            commit(work, "a.txt", "three\n", "main edits the squashed line again")
            // The premise: merging the branch now conflicts, so the merge-tree check alone misses it.
            assert(gitAllowingFailure(work, "merge-tree", "--write-tree", Sandbox.MAIN, "feature") != 0)

            assert(BranchOps.landedByContent(work, "feature", Sandbox.MAIN))
        }

        test("a branch with work after its squash isn't merged") {
            val work = featureRepo()
            squash(work)
            git(work, "switch", "-q", "feature")
            commit(work, "d.txt", "dee\n", "feature: more after the merge")
            git(work, "switch", "-q", Sandbox.MAIN)

            val feature = BranchOps.load(work.path).single { it.name == "feature" }

            assert(!feature.merged && !feature.squashed)
        }

        test("an unmerged branch stays unmerged") {
            val work = featureRepo()

            val feature = BranchOps.load(work.path).single { it.name == "feature" }

            assert(!feature.merged && !feature.squashed && !feature.empty)
        }

        test("a squash-merged remote branch reads as merged") {
            val work = featureRepo()
            git(work, "push", "-q", "origin", "feature")
            squash(work)

            val remote = BranchOps.loadRemotes(work.path, emptySet()).single { it.name == "origin/feature" }

            assert(remote.merged && remote.squashed)
        }
    }
}
