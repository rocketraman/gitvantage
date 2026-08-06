// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git

import com.gitvantage.git.model.Worktree
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
