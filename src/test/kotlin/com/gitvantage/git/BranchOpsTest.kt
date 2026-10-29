// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git

import com.gitvantage.git.model.RemoteBranch
import de.infix.testBalloon.framework.core.testSuite
import java.io.File

/** Branch mutations: switch, checkout of a remote branch, push, and local/remote delete. */
val BranchOperations by testSuite {
    testFixture { Sandbox() } asContextForEach {

        // --- switch -------------------------------------------------------------------------

        test("switch checks out an existing branch") {
            val work = repo()
            git(work, "branch", "feature")

            val result = BranchOps.switch(work.path, "feature")

            assert(result.ok)
            assert(result.message == "Switched to feature")
            assert(git(work, "symbolic-ref", "--short", "HEAD").trim() == "feature")
        }

        test("switch fails on a branch that does not exist and stays put") {
            val work = repo()

            val result = BranchOps.switch(work.path, "nope")

            assert(!result.ok)
            assert(git(work, "symbolic-ref", "--short", "HEAD").trim() == Sandbox.MAIN)
        }

        test("switch fails rather than discarding uncommitted work") {
            val work = repo()
            git(work, "switch", "-q", "-c", "feature")
            commit(work, "a.txt", "changed on feature\n", "feature edit")
            git(work, "switch", "-q", Sandbox.MAIN)
            dirty(work)

            val result = BranchOps.switch(work.path, "feature")

            assert(!result.ok)
            // The edit is still there — a failed switch must not have touched the tree.
            assert("uncommitted" in java.io.File(work, "a.txt").readText())
        }

        // --- checkoutRemote -----------------------------------------------------------------

        test("checkoutRemote creates a tracking branch from the remote one") {
            val work = repo()
            val other = clone(originOf(work), "other")
            git(other, "switch", "-q", "-c", "feature")
            commit(other, "f.txt", "theirs\n", "their feature")
            git(other, "push", "-q", "-u", "origin", "feature")
            git(work, "fetch", "-q")
            assert("feature" !in branches(work))

            val result = BranchOps.checkoutRemote(work.path, "origin/feature", hasLocal = false)

            assert(result.ok)
            assert(result.message == "Checked out feature")
            assert(git(work, "symbolic-ref", "--short", "HEAD").trim() == "feature")
            // --track was used: the new branch knows where it came from.
            assert(git(work, "rev-parse", "--abbrev-ref", "@{upstream}").trim() == "origin/feature")
        }

        test("checkoutRemote just switches when the local branch already exists") {
            val work = repo()
            git(work, "branch", "feature")
            val before = rev(work, "feature")

            val result = BranchOps.checkoutRemote(work.path, "origin/feature", hasLocal = true)

            assert(result.ok)
            assert(git(work, "symbolic-ref", "--short", "HEAD").trim() == "feature")
            // The existing branch was reused, not recreated from the remote.
            assert(rev(work, "feature") == before)
        }

        // --- push ---------------------------------------------------------------------------

        test("push publishes a branch that is not checked out") {
            val work = repo()
            git(work, "switch", "-q", "-c", "feature")
            commit(work, "f.txt", "mine\n", "feature work")
            git(work, "switch", "-q", Sandbox.MAIN)

            val result = BranchOps.push(work.path, "feature", upstream = null)

            assert(result.ok)
            assert(result.message == "Published feature")
            assert(rev(originOf(work), "feature") == rev(work, "feature"))
            // Still on main: pushing a ref must not move the working tree.
            assert(git(work, "symbolic-ref", "--short", "HEAD").trim() == Sandbox.MAIN)
        }

        test("push follows the tracked ref when it has a different name") {
            val work = repo()
            git(work, "switch", "-q", "-c", "feature")
            commit(work, "f.txt", "mine\n", "feature work")
            // A branch may track a remote branch of another name; the push must follow the ref it
            // tracks rather than creating a second branch beside it under the local name.
            git(work, "push", "-q", "origin", "feature:renamed")
            git(work, "branch", "--set-upstream-to=origin/renamed", "feature")
            commit(work, "f.txt", "more\n", "more work")

            val result = BranchOps.push(work.path, "feature", upstream = "origin/renamed")

            assert(result.ok)
            assert(result.message == "Pushed feature to origin/renamed")
            assert(rev(originOf(work), "renamed") == rev(work, "feature"))
            assert(gitAllowingFailure(originOf(work), "rev-parse", "--verify", "-q", "feature") != 0)
        }

        test("push fails on a diverged branch without forcing it") {
            val work = repo()
            val other = clone(originOf(work), "other")
            commit(other, "a.txt", "theirs\n", "from other")
            git(other, "push", "-q")
            commit(work, "a.txt", "mine\n", "from work")
            val remoteBefore = rev(originOf(work), Sandbox.MAIN)

            val result = BranchOps.push(work.path, Sandbox.MAIN, upstream = "origin/${Sandbox.MAIN}")

            assert(!result.ok)
            assert("rejected" in result.message)
            // Never --force: the remote is exactly where it was.
            assert(rev(originOf(work), Sandbox.MAIN) == remoteBefore)
        }

        // --- delete -------------------------------------------------------------------------

        test("load tells a merged branch from one nothing was committed to") {
            val work = repo()
            git(work, "branch", "untouched")
            git(work, "switch", "-q", "-c", "landed")
            commit(work, "f.txt", "landed\n", "work")
            git(work, "switch", "-q", Sandbox.MAIN)
            git(work, "merge", "-q", "--no-ff", "-m", "merge landed", "landed")

            val branches = BranchOps.load(work.path).associateBy { it.name }

            assert(branches.getValue("landed").merged)
            assert(!branches.getValue("landed").empty)
            assert(!branches.getValue("untouched").merged)
            assert(branches.getValue("untouched").empty)
        }

        test("load names the worktree detached on a branch's tip") {
            val work = repo()
            val tree = File(root, "side")
            git(work, "worktree", "add", "-q", "-b", "claude/side", tree.path)
            git(tree, "checkout", "-q", "--detach")

            val side = BranchOps.load(work.path).single { it.name == "claude/side" }

            // Labelled, but not held: git lets a branch nobody has checked out be switched to.
            assert(side.detachedWorktreePath == tree.path) { "got ${side.detachedWorktreePath}" }
            assert(!side.inOtherWorktree)
        }

        test("delete removes a merged branch") {
            val work = repo()
            git(work, "branch", "feature")

            val result = BranchOps.delete(work.path, "feature", force = false)

            assert(result.ok)
            assert(result.message == "Deleted feature")
            assert("feature" !in branches(work))
        }

        test("delete refuses an unmerged branch without force") {
            val work = repo()
            git(work, "switch", "-q", "-c", "feature")
            commit(work, "f.txt", "unmerged\n", "unmerged work")
            git(work, "switch", "-q", Sandbox.MAIN)

            val result = BranchOps.delete(work.path, "feature", force = false)

            assert(!result.ok)
            assert("not fully merged" in result.message)
            assert("feature" in branches(work))
        }

        test("delete with force removes an unmerged branch") {
            val work = repo()
            git(work, "switch", "-q", "-c", "feature")
            commit(work, "f.txt", "unmerged\n", "unmerged work")
            git(work, "switch", "-q", Sandbox.MAIN)

            val result = BranchOps.delete(work.path, "feature", force = true)

            assert(result.ok)
            assert("feature" !in branches(work))
        }

        // --- deleteRemote -------------------------------------------------------------------

        test("deleteRemote removes the branch from the remote") {
            val work = repo()
            git(work, "switch", "-q", "-c", "feature")
            commit(work, "f.txt", "mine\n", "feature work")
            git(work, "push", "-q", "-u", "origin", "feature")
            git(work, "switch", "-q", Sandbox.MAIN)
            assert(gitAllowingFailure(originOf(work), "rev-parse", "--verify", "-q", "feature") == 0)

            val remote = RemoteBranch(
                name = "origin/feature", shortName = "feature",
                author = "Test", lastRelative = "now", hasLocal = true, merged = false,
            )
            val result = BranchOps.deleteRemote(work.path, remote)

            assert(result.ok)
            assert(result.message == "Deleted origin/feature")
            assert(gitAllowingFailure(originOf(work), "rev-parse", "--verify", "-q", "feature") != 0)
            // Only the remote copy: the local branch is a separate decision.
            assert("feature" in branches(work))
        }

        test("deleteRemote reports the server's refusal without the transport chatter") {
            val work = repo()
            // The remote's HEAD branch cannot be deleted; git refuses it server-side.
            val remote = RemoteBranch(
                name = "origin/${Sandbox.MAIN}", shortName = Sandbox.MAIN,
                author = "Test", lastRelative = "now", hasLocal = true, merged = true,
            )

            val result = BranchOps.deleteRemote(work.path, remote)

            assert(!result.ok)
            // git's own one-line summary, not the "To <url>" echo or the wrapped `remote:` prose.
            assert("rejected" in result.message)
            assert(!result.message.startsWith("To "))
            assert(!result.message.startsWith("remote:"))
            assert(gitAllowingFailure(originOf(work), "rev-parse", "--verify", "-q", Sandbox.MAIN) == 0)
        }

        // --- deleteRemotes ------------------------------------------------------------------

        fun Sandbox.pushed(work: java.io.File, name: String) {
            git(work, "switch", "-q", "-c", name)
            commit(work, "$name.txt", "$name\n", "$name work")
            git(work, "push", "-q", "-u", "origin", name)
            git(work, "switch", "-q", Sandbox.MAIN)
        }

        fun remoteBranch(name: String) = RemoteBranch(
            name = "origin/$name", shortName = name,
            author = "Test", lastRelative = "now", hasLocal = true, merged = true,
        )

        fun Sandbox.onOrigin(work: java.io.File, name: String) =
            gitAllowingFailure(originOf(work), "rev-parse", "--verify", "-q", "refs/heads/$name") == 0

        test("deleteRemotes removes every branch in one go") {
            val work = repo()
            pushed(work, "a")
            pushed(work, "b")
            pushed(work, "c")

            val result = BranchOps.deleteRemotes(work.path, listOf(remoteBranch("a"), remoteBranch("b")))

            assert(result.ok) { result.message }
            assert(result.message == "Deleted 2 merged branches")
            assert(!onOrigin(work, "a") && !onOrigin(work, "b"))
            assert(onOrigin(work, "c")) // not asked for
            assert(gitAllowingFailure(work, "rev-parse", "--verify", "-q", "refs/remotes/origin/a") != 0)
            // Only the remote copies.
            assert("a" in branches(work) && "b" in branches(work))
        }

        test("deleteRemotes skips a branch pushed to since the last fetch and deletes the rest") {
            val work = repo()
            pushed(work, "a")
            pushed(work, "b")
            val other = clone(originOf(work), "other")
            git(other, "switch", "-q", "b")
            commit(other, "g.txt", "theirs\n", "their follow-up")
            git(other, "push", "-q", "origin", "b")

            val result = BranchOps.deleteRemotes(work.path, listOf(remoteBranch("a"), remoteBranch("b")))

            assert(!result.ok)
            assert(result.message.startsWith("Deleted 1 of 2 merged branches")) { result.message }
            assert("origin/b" in result.message)
            assert(!onOrigin(work, "a"))
            assert(onOrigin(work, "b")) // their commit survives
        }

        test("deleteRemotes counts a branch already deleted on the remote as deleted") {
            val work = repo()
            pushed(work, "a")
            pushed(work, "b")
            git(originOf(work), "branch", "-D", "a")

            val result = BranchOps.deleteRemotes(work.path, listOf(remoteBranch("a"), remoteBranch("b")))

            assert(result.ok) { result.message }
            assert(!onOrigin(work, "b"))
            // The stale copy goes, or the list would keep offering it.
            assert(gitAllowingFailure(work, "rev-parse", "--verify", "-q", "refs/remotes/origin/a") != 0)
        }

        test("deleteRemotes reports a branch the server refuses") {
            val work = repo()
            pushed(work, "a")

            val result = BranchOps.deleteRemotes(work.path, listOf(remoteBranch("a"), remoteBranch(Sandbox.MAIN)))

            assert(!result.ok)
            assert(result.message.startsWith("Deleted 1 of 2")) { result.message }
            assert("origin/${Sandbox.MAIN}" in result.message)
            assert(!onOrigin(work, "a"))
            assert(onOrigin(work, Sandbox.MAIN))
        }

        // --- deleteWithUpstream -------------------------------------------------------------

        test("deleteWithUpstream removes both the local branch and its remote branch") {
            val work = repo()
            git(work, "switch", "-q", "-c", "feature")
            commit(work, "f.txt", "mine\n", "feature work")
            git(work, "push", "-q", "-u", "origin", "feature")
            git(work, "switch", "-q", Sandbox.MAIN)
            git(work, "merge", "-q", "--no-ff", "-m", "merge feature", "feature")

            val result = BranchOps.deleteWithUpstream(work.path, "feature", "origin/feature")

            assert(result.ok)
            assert(result.message == "Deleted feature and origin/feature")
            assert("feature" !in branches(work))
            assert(gitAllowingFailure(originOf(work), "rev-parse", "--verify", "-q", "feature") != 0)
            assert(gitAllowingFailure(work, "rev-parse", "--verify", "-q", "refs/remotes/origin/feature") != 0)
        }

        test("deleteWithUpstream keeps both when someone pushed to the remote since the last fetch") {
            val work = repo()
            git(work, "switch", "-q", "-c", "feature")
            commit(work, "f.txt", "mine\n", "feature work")
            git(work, "push", "-q", "-u", "origin", "feature")
            git(work, "switch", "-q", Sandbox.MAIN)
            val other = clone(originOf(work), "other")
            git(other, "switch", "-q", "feature")
            commit(other, "g.txt", "theirs\n", "their follow-up")
            git(other, "push", "-q", "origin", "feature")

            val result = BranchOps.deleteWithUpstream(work.path, "feature", "origin/feature")

            assert(!result.ok)
            // Their commit survives on the remote, and the local branch is still here to retry from.
            assert(gitAllowingFailure(originOf(work), "rev-parse", "--verify", "-q", "feature") == 0)
            assert("feature" in branches(work))
        }

        test("deleteWithUpstream counts a remote branch already deleted there as deleted") {
            val work = repo()
            git(work, "switch", "-q", "-c", "feature")
            commit(work, "f.txt", "mine\n", "feature work")
            git(work, "push", "-q", "-u", "origin", "feature")
            git(work, "switch", "-q", Sandbox.MAIN)
            git(work, "merge", "-q", "--no-ff", "-m", "merge feature", "feature")
            // What GitHub does on merging the PR; this clone hasn't pruned, so it still has origin/feature.
            git(originOf(work), "branch", "-D", "feature")

            val result = BranchOps.deleteWithUpstream(work.path, "feature", "origin/feature")

            assert(result.ok) { result.message }
            assert("feature" !in branches(work))
            // The stale copy goes too, or the remote list would keep offering to delete it.
            assert(gitAllowingFailure(work, "rev-parse", "--verify", "-q", "refs/remotes/origin/feature") != 0)
        }
    }
}
