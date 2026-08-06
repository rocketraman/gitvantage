// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git

import com.gitvantage.git.model.Branch
import com.gitvantage.git.model.RemoteBranch
import de.infix.testBalloon.framework.core.testSuite

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
            assert(result.message == "Pushed feature")
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
    }
}
