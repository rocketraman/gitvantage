// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git

import com.gitvantage.git.model.WorktreeSource
import de.infix.testBalloon.framework.core.testSuite
import java.io.File

/** Creating a worktree from a ref: what each kind of source checks out, and how a typed ref resolves. */
val WorktreeCreation by testSuite {
    testFixture { Sandbox() } asContextForEach {

        test("a local branch is checked out as itself") {
            val work = repo()
            git(work, "branch", "side")
            val tree = File(root, "wt")

            val result = WorktreeOps.add(work.path, tree.path, WorktreeSource.LocalBranch("side"), newBranch = null)

            assert(result.ok) { result.message }
            assert(result.message == "Created worktree wt on side") { result.message }
            assert(git(tree, "symbolic-ref", "--short", "HEAD").trim() == "side")
        }

        test("a local branch checked out elsewhere is refused, and nothing is left behind") {
            val work = repo()
            val tree = File(root, "wt")

            val result = WorktreeOps.add(work.path, tree.path, WorktreeSource.LocalBranch(Sandbox.MAIN), newBranch = null)

            assert(!result.ok)
            assert(result.message.startsWith("fatal:")) { result.message }
            assert(!tree.exists())
        }

        test("a remote branch whose local name is taken says so, rather than git's progress line") {
            val work = repo()
            val tree = File(root, "wt")

            val result = WorktreeOps.add(
                work.path, tree.path, WorktreeSource.RemoteBranch("origin/${Sandbox.MAIN}"), newBranch = Sandbox.MAIN,
            )

            assert(!result.ok)
            assert("already exists" in result.message) { result.message }
            assert(!tree.exists())
        }

        test("a remote branch gets a local branch of the same name, tracking it") {
            val work = repo()
            git(work, "switch", "-q", "-c", "feat")
            commit(work, "f.txt", "feat\n", "feat work")
            git(work, "push", "-q", "origin", "feat")
            git(work, "switch", "-q", Sandbox.MAIN)
            git(work, "branch", "-D", "feat")
            val tree = File(root, "wt")

            val result = WorktreeOps.add(work.path, tree.path, WorktreeSource.RemoteBranch("origin/feat"), newBranch = null)

            assert(result.ok) { result.message }
            assert(git(tree, "symbolic-ref", "--short", "HEAD").trim() == "feat")
            assert(git(tree, "rev-parse", "--abbrev-ref", "@{u}").trim() == "origin/feat")
        }

        test("a remote branch can be given a different local name") {
            val work = repo()
            git(work, "push", "-q", "origin", "${Sandbox.MAIN}:feat")
            git(work, "fetch", "-q")
            val tree = File(root, "wt")

            val result = WorktreeOps.add(work.path, tree.path, WorktreeSource.RemoteBranch("origin/feat"), newBranch = "mine")

            assert(result.ok) { result.message }
            assert(git(tree, "symbolic-ref", "--short", "HEAD").trim() == "mine")
            assert(git(tree, "rev-parse", "--abbrev-ref", "@{u}").trim() == "origin/feat")
        }

        test("a tag with no branch name is a detached tree at that commit") {
            val work = repo()
            git(work, "tag", "v1")
            commit(work, "b.txt", "later\n", "after the tag")
            val tree = File(root, "wt")

            val result = WorktreeOps.add(work.path, tree.path, WorktreeSource.Commit("v1", "v1"), newBranch = "  ")

            assert(result.ok) { result.message }
            assert(result.message == "Created worktree wt at v1") { result.message }
            assert(gitAllowingFailure(tree, "symbolic-ref", "-q", "HEAD") != 0)
            assert(rev(tree, "HEAD") == rev(work, "v1"))
        }

        test("a commit with a branch name starts a new branch there, tracking nothing") {
            val work = repo()
            val first = rev(work, "HEAD")
            commit(work, "b.txt", "later\n", "second")
            val tree = File(root, "wt")

            val result = WorktreeOps.add(work.path, tree.path, WorktreeSource.Commit(first, first.take(7)), newBranch = "fix")

            assert(result.ok) { result.message }
            assert(git(tree, "symbolic-ref", "--short", "HEAD").trim() == "fix")
            assert(rev(tree, "HEAD") == first)
            assert(gitAllowingFailure(tree, "rev-parse", "--abbrev-ref", "@{u}") != 0)
        }

        test("resolve takes a local branch, then a remote one, then any commit") {
            val work = repo()
            git(work, "branch", "side")
            git(work, "push", "-q", "origin", "${Sandbox.MAIN}:only-remote")
            git(work, "fetch", "-q")
            git(work, "tag", "v1")

            assert(WorktreeOps.resolve(work.path, "side") == WorktreeSource.LocalBranch("side"))
            assert(WorktreeOps.resolve(work.path, "origin/only-remote") == WorktreeSource.RemoteBranch("origin/only-remote"))
            // The short name alone, when exactly one remote has it — git's own guess.
            assert(WorktreeOps.resolve(work.path, "only-remote") == WorktreeSource.RemoteBranch("origin/only-remote"))
            assert(WorktreeOps.resolve(work.path, "v1") == WorktreeSource.Commit(rev(work, "v1"), "v1"))
            assert(WorktreeOps.resolve(work.path, "no-such-thing") == null)
            assert(WorktreeOps.resolve(work.path, "--detach") == null)
        }

        test("refNames lists branches, remote branches and tags, without a remote's HEAD alias") {
            val work = repo()
            git(work, "remote", "set-head", "origin", Sandbox.MAIN)
            git(work, "tag", "v1")

            val names = WorktreeOps.refNames(work.path)

            assert(Sandbox.MAIN in names && "origin/${Sandbox.MAIN}" in names && "v1" in names) { names.toString() }
            assert("origin" !in names && names.none { it.endsWith("/HEAD") }) { names.toString() }
        }

        test("the default folder sits beside the repo and numbers past what's taken") {
            val work = repo()
            val parent = work.canonicalFile.parentFile

            assert(WorktreeOps.defaultPath(work.path, "feature/log search") == File(parent, "repo-feature-log-search").path)
            File(parent, "repo-fix").mkdirs()
            assert(WorktreeOps.defaultPath(work.path, "fix") == File(parent, "repo-fix-2").path)
            assert(WorktreeOps.defaultPath(work.path, "///") == File(parent, "repo-worktree").path)
        }

        test("pathProblem accepts a missing or empty folder and refuses one with files in it") {
            val empty = File(root, "empty").apply { mkdirs() }
            val full = File(root, "full").apply { mkdirs(); File(this, "x").writeText("x") }

            assert(WorktreeOps.pathProblem(File(root, "nope").path) == null)
            assert(WorktreeOps.pathProblem(empty.path) == null)
            assert(WorktreeOps.pathProblem(full.path) != null)
            assert(WorktreeOps.pathProblem("relative/path") != null)
            assert(WorktreeOps.pathProblem(" ") != null)
        }
    }
}
