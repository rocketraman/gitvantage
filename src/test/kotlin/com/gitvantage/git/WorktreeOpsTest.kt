// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git

import com.gitvantage.model.Worktree
import de.infix.testBalloon.framework.core.testSuite
import java.io.File

/** Worktree mutations: remove (with its force and branch-deleting variants) and prune. */
val WorktreeOperations by testSuite {
    testFixture { Sandbox() } asContextForEach {

        test("remove deletes the worktree directory and leaves its branch alone") {
            val work = repo()
            val tree = File(root, "wt")
            git(work, "worktree", "add", "-q", "-b", "side", tree.path)
            assert(tree.isDirectory)

            val result = WorktreeOps.remove(work.path, tree.path, force = false, locked = false)

            assert(result.ok)
            assert(result.message == "Removed worktree wt")
            assert(!tree.exists())
            // The branch it held is a separate thing and must survive.
            assert("side" in branches(work))
        }

        test("remove refuses a worktree with uncommitted work unless forced") {
            val work = repo()
            val tree = File(root, "wt")
            git(work, "worktree", "add", "-q", "-b", "side", tree.path)
            File(tree, "a.txt").appendText("uncommitted\n")

            val result = WorktreeOps.remove(work.path, tree.path, force = false, locked = false)

            assert(!result.ok)
            assert(tree.isDirectory)
            assert("a.txt" in git(tree, "status", "--porcelain"))
        }

        test("remove with force discards a dirty worktree") {
            val work = repo()
            val tree = File(root, "wt")
            git(work, "worktree", "add", "-q", "-b", "side", tree.path)
            File(tree, "a.txt").appendText("uncommitted\n")

            val result = WorktreeOps.remove(work.path, tree.path, force = true, locked = false)

            assert(result.ok)
            assert(!tree.exists())
        }

        test("remove with alsoBranch deletes a landed branch too") {
            val work = repo()
            val tree = File(root, "wt")
            // A branch with no commits of its own is already contained in main, so `branch -d` accepts it.
            git(work, "worktree", "add", "-q", "-b", "side", tree.path)

            val result = WorktreeOps.remove(work.path, tree.path, force = false, locked = false, alsoBranch = "side")

            assert(result.ok)
            assert(result.message == "Removed worktree wt and branch side")
            assert(!tree.exists())
            assert("side" !in branches(work))
        }

        test("remove still succeeds when the branch delete is refused, and says the branch was kept") {
            val work = repo()
            val tree = File(root, "wt")
            git(work, "worktree", "add", "-q", "-b", "side", tree.path)
            commit(tree, "f.txt", "unmerged\n", "work only on side")

            val result = WorktreeOps.remove(work.path, tree.path, force = false, locked = false, alsoBranch = "side")

            // The folder is gone either way, so this is a success with a caveat, not a failure —
            // reporting failure would invite a retry that cannot help.
            assert(result.ok)
            assert("branch kept" in result.message)
            assert(!tree.exists())
            assert("side" in branches(work))
        }

        test("remove tidies the empty .claude/worktrees parent a session left behind") {
            val work = repo()
            val agentTree = File(work, ".claude/worktrees/claude-abc")
            git(work, "worktree", "add", "-q", "-b", "claude/thing", agentTree.path)

            val result = WorktreeOps.remove(work.path, agentTree.path, force = false, locked = false)

            assert(result.ok)
            assert(!agentTree.exists())
            // The generated parent goes too, rather than lingering in the repo forever.
            assert(!File(work, ".claude/worktrees").exists())
        }

        test("remove leaves a non-empty worktrees parent in place") {
            val work = repo()
            val one = File(work, ".claude/worktrees/one")
            val two = File(work, ".claude/worktrees/two")
            git(work, "worktree", "add", "-q", "-b", "claude/one", one.path)
            git(work, "worktree", "add", "-q", "-b", "claude/two", two.path)

            WorktreeOps.remove(work.path, one.path, force = false, locked = false)

            assert(!one.exists())
            assert(two.isDirectory)
            assert(File(work, ".claude/worktrees").isDirectory)
        }

        // ---- what the list surfaces read off each tree ----

        test("listWithWork fills in each other tree's work, last commit and landed-ness") {
            val work = repo()
            val tree = File(root, "wt")
            git(work, "worktree", "add", "-q", "-b", "side", tree.path)
            commit(tree, "f.txt", "unlanded\n", "work only on side")
            File(tree, "a.txt").appendText("uncommitted\n")

            val side = WorktreeOps.listWithWork(work.path).single { !it.isCurrent }

            assert(side.branch == "side")
            assert(side.dirtyCount == 1) { "got ${side.dirtyCount}" }
            assert(side.unmerged == 1) { "got ${side.unmerged}" }
            assert(side.unlanded)
            // The row's right-hand column and the strip's "2h" both come off this read, so the scan
            // path has to fill it — not just the detail panel's load.
            assert(side.lastRelative.isNotEmpty())
            assert(side.lastAuthor == "Test") { "got ${side.lastAuthor}" }
            assert(side.lastEpoch != null && side.lastEpoch > 0)
            // Its own commit isn't in main, so the branch hasn't landed.
            assert(!side.branchMerged)
        }

        test("a branch already contained in mainline reads as merged on the scan path") {
            val work = repo()
            val tree = File(root, "wt")
            // No commits of its own, so main already contains everything on it.
            git(work, "worktree", "add", "-q", "-b", "side", tree.path)

            val side = WorktreeOps.listWithWork(work.path).single { !it.isCurrent }

            // Gates the "merged" badge and whether "Remove + branch" is offered at all, both of which
            // the table's sub-rows show — so it cannot be a detail-panel-only field.
            assert(side.branchMerged)
            assert(!side.unlanded)
        }

        test("changes lists modified and untracked files, with a diffstat for the tracked ones") {
            val work = repo()
            val tree = File(root, "wt")
            git(work, "worktree", "add", "-q", "-b", "side", tree.path)
            File(tree, "a.txt").writeText("one\ntwo\nthree\n")   // was "one\n": +2 lines
            File(tree, "new.txt").writeText("fresh\n")

            val changes = WorktreeOps.changes(tree.path).associateBy { it.path }

            assert(changes.size == 2) { "got ${changes.keys}" }
            val modified = changes.getValue("a.txt")
            assert(!modified.untracked)
            assert(modified.added == 2) { "got +${modified.added}" }
            assert(modified.deleted == 0) { "got -${modified.deleted}" }
            // An untracked file has nothing to diff against, so it carries no counts — the row says
            // "untracked" rather than claiming "+0 −0".
            val untracked = changes.getValue("new.txt")
            assert(untracked.untracked)
            assert(untracked.added == 0 && untracked.deleted == 0)
        }

        test("changes on a clean worktree is empty, and on a path that isn't one it doesn't throw") {
            val work = repo()
            val tree = File(root, "wt")
            git(work, "worktree", "add", "-q", "-b", "side", tree.path)

            assert(WorktreeOps.changes(tree.path).isEmpty())
            assert(WorktreeOps.changes(File(root, "not-a-worktree").path).isEmpty())
        }

        test("prune drops the administrative entry for a directory that is gone") {
            val work = repo()
            val tree = File(root, "wt")
            git(work, "worktree", "add", "-q", "-b", "side", tree.path)
            // Delete the folder behind git's back — the state prune exists to clean up.
            tree.deleteRecursively()
            assert(WorktreeOps.list(work.path).size == 2)

            val result = WorktreeOps.prune(work.path)

            assert(result.ok)
            assert(result.message == "Pruned stale worktree entries")
            assert(WorktreeOps.list(work.path).size == 1)
        }
    }
}
