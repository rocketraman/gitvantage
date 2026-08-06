// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

import de.infix.testBalloon.framework.core.testSuite

/** Repo-level mutations: push, commit, fast-forward, stash apply/drop. */
val RepoOperations by testSuite {
    testFixture { Sandbox() } asContextForEach {

        // --- push ---------------------------------------------------------------------------

        test("push sends commits to the tracked upstream") {
            val work = repo()
            commit(work, "b.txt", "two\n", "second")

            val result = RepoOps.push(entry(work))

            assert(result.ok)
            assert(result.message == "${work.name} pushed")
            // The remote actually moved — not merely that git exited 0.
            assert(rev(work, "HEAD") == rev(originOf(work), Sandbox.MAIN))
        }

        test("push publishes a branch that has no upstream and sets it") {
            val work = repo()
            git(work, "switch", "-q", "-c", "feature")
            commit(work, "c.txt", "three\n", "on feature")
            assert(gitAllowingFailure(work, "rev-parse", "--verify", "-q", "@{upstream}") != 0)

            val result = RepoOps.push(entry(work))

            assert(result.ok)
            // `push -u` had to have been chosen: the branch now tracks, which a plain push never does.
            assert(rev(work, "@{upstream}") == rev(work, "HEAD"))
            assert(rev(originOf(work), "feature") == rev(work, "HEAD"))
        }

        test("push fails when the branch has diverged, and says why") {
            val work = repo()
            val other = clone(originOf(work), "other")
            commit(other, "a.txt", "theirs\n", "from other")
            git(other, "push", "-q")
            commit(work, "a.txt", "mine\n", "from work")

            val result = RepoOps.push(entry(work))

            assert(!result.ok)
            // The reason, not the transport chatter git prints ahead of it.
            assert("rejected" in result.message)
            assert(!result.message.contains("To "))
        }

        test("push reports a directory that is not a repo") {
            val plain = java.io.File(root, "plain").apply { mkdirs() }

            val result = RepoOps.push(entry(plain))

            assert(!result.ok)
            assert(result.message == "Not a git repo")
        }

        // --- commit -------------------------------------------------------------------------

        test("commit stages everything when asked and records subject and body") {
            val work = repo()
            java.io.File(work, "new.txt").writeText("fresh\n")
            val before = rev(work, "HEAD")

            val result = RepoOps.commit(entry(work), "the subject", "the body", stageAll = true)

            assert(result.ok)
            assert(rev(work, "HEAD") != before)
            assert(git(work, "log", "-1", "--format=%s").trim() == "the subject")
            assert(git(work, "log", "-1", "--format=%b").trim() == "the body")
            // The untracked file was picked up, which is the whole point of stageAll.
            assert("new.txt" in git(work, "show", "--name-only", "--format=", "HEAD"))
        }

        test("commit without stageAll takes only what is already staged") {
            val work = repo()
            java.io.File(work, "staged.txt").writeText("in\n")
            java.io.File(work, "unstaged.txt").writeText("out\n")
            git(work, "add", "staged.txt")

            val result = RepoOps.commit(entry(work), "partial", "", stageAll = false)

            assert(result.ok)
            val files = git(work, "show", "--name-only", "--format=", "HEAD")
            assert("staged.txt" in files)
            assert("unstaged.txt" !in files)
        }

        test("commit omits the body paragraph when the body is blank") {
            val work = repo()
            java.io.File(work, "x.txt").writeText("x\n")

            RepoOps.commit(entry(work), "just a subject", "   ", stageAll = true)

            assert(git(work, "log", "-1", "--format=%b").isBlank())
        }

        test("commit fails on a clean tree") {
            val work = repo()

            val result = RepoOps.commit(entry(work), "nothing here", "", stageAll = true)

            assert(!result.ok)
            assert(result.message.startsWith("${work.name}: "))
        }

        // --- fast-forward -------------------------------------------------------------------

        test("fastForward advances to the upstream") {
            val work = repo()
            val other = clone(originOf(work), "other")
            commit(other, "a.txt", "theirs\n", "from other")
            git(other, "push", "-q")
            git(work, "fetch", "-q")
            val target = rev(work, "@{upstream}")
            assert(rev(work, "HEAD") != target)

            val result = RepoOps.fastForward(entry(work))

            assert(result.ok)
            assert(rev(work, "HEAD") == target)
        }

        test("fastForward refuses when the branch has its own commits") {
            val work = repo()
            val other = clone(originOf(work), "other")
            commit(other, "a.txt", "theirs\n", "from other")
            git(other, "push", "-q")
            git(work, "fetch", "-q")
            commit(work, "b.txt", "mine\n", "diverging")
            val before = rev(work, "HEAD")

            val result = RepoOps.fastForward(entry(work))

            assert(!result.ok)
            // Refused, not merged: a fast-forward that silently became a merge would be a data change.
            assert(rev(work, "HEAD") == before)
        }

        // --- stash --------------------------------------------------------------------------

        test("stashApply restores the change and keeps the stash") {
            val work = repo()
            dirty(work)
            git(work, "stash", "-q")
            assert(git(work, "status", "--porcelain").isBlank())

            val result = RepoOps.stashApply(entry(work), "stash@{0}")

            assert(result.ok)
            assert(result.message == "Applied stash@{0}")
            assert(git(work, "status", "--porcelain").isNotBlank())
            // "apply" and not "pop": the stash must survive.
            assert(git(work, "stash", "list").isNotBlank())
        }

        test("stashDrop deletes the stash") {
            val work = repo()
            dirty(work)
            git(work, "stash", "-q")

            val result = RepoOps.stashDrop(entry(work), "stash@{0}")

            assert(result.ok)
            assert(result.message == "Dropped stash@{0}")
            assert(git(work, "stash", "list").isBlank())
        }

        test("stash operations fail on a ref that does not exist") {
            val work = repo()

            val applied = RepoOps.stashApply(entry(work), "stash@{7}")
            val dropped = RepoOps.stashDrop(entry(work), "stash@{7}")

            assert(!applied.ok)
            assert(!dropped.ok)
            assert(applied.message.isNotBlank())
        }
    }
}
