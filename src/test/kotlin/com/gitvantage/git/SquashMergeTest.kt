// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git

import de.infix.testBalloon.framework.core.testSuite
import java.io.File

/** A `feature` branch with two commits of its own, cut from main, with main moved on since. */
private fun Sandbox.featureRepo(): File {
    val work = repo()
    git(work, "switch", "-q", "-c", "feature")
    commit(work, "a.txt", "two\n", "feature: change a")
    commit(work, "b.txt", "bee\n", "feature: add b")
    git(work, "switch", "-q", Sandbox.MAIN)
    commit(work, "c.txt", "sea\n", "unrelated work on main")
    return work
}

/** Land `feature` on main as one new commit, the way a squash-merged PR does. */
private fun Sandbox.squash(work: File) {
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
            // The premise: main no longer holds the branch's version of a.txt, so only the patch finds it.
            assert(gitAllowingFailure(work, "diff", "--quiet", Sandbox.MAIN, "feature", "--", "a.txt") != 0)

            assert(BranchOps.landedByContent(work, "feature", Sandbox.MAIN))
        }

        test("the check never writes to the object store — the repo's watch would see it and rescan") {
            val work = featureRepo()
            squash(work)
            git(work, "switch", "-q", "-c", "edited")
            commit(work, "a.txt", "three\n", "main edits the squashed line again")
            git(work, "switch", "-q", Sandbox.MAIN)
            val objects = File(work, ".git/objects")
            fun snapshot() = objects.walk().filter { it.isFile }.associate { it.path to it.lastModified() }
            val before = snapshot()
            Thread.sleep(20) // past mtime granularity, so a touch would show

            // Both paths: the direct comparison, and the patch fallback after main moved on.
            assert(BranchOps.landedByContent(work, "feature", Sandbox.MAIN))
            assert(BranchOps.landedByContent(work, "feature", "edited"))

            assert(snapshot() == before) { "the object store changed: ${snapshot() - before.keys}" }
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
