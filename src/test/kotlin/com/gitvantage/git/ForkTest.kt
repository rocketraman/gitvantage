// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git

import com.gitvantage.git.model.groupByRemote
import de.infix.testBalloon.framework.core.testSuite
import java.io.File

/**
 * A fork: `work` is a clone of `origin`, which was forked from `upstream`; [seed] is the upstream
 * project's own checkout, for landing commits there.
 */
private class Fork(val work: File, val origin: File, val upstream: File, val seed: File)

/**
 * The fork arrangement: the upstream project, a fork of it, and a clone of the fork with the
 * project added as a second remote under [upstreamName] — whatever the user chose to call it.
 */
private fun Sandbox.fork(upstreamName: String = "upstream"): Fork {
    val up = bare("project")
    val seed = clone(up, "seed")
    commit(seed, "a.txt", "one\n", "first commit")
    git(seed, "push", "-q", "-u", "origin", Sandbox.MAIN)
    val origin = bare("fork")
    git(seed, "push", "-q", origin.path, Sandbox.MAIN)
    val work = clone(origin, "work")
    git(work, "remote", "add", upstreamName, up.path)
    git(work, "fetch", "-q", upstreamName)
    return Fork(work, origin, up, seed)
}

/** Land [n] commits on the upstream project and fetch them into the fork's clone. */
private fun Sandbox.advanceUpstream(f: Fork, n: Int = 2) {
    repeat(n) { commit(f.seed, "up$it.txt", "upstream $it\n", "upstream work $it") }
    git(f.seed, "push", "-q")
    git(f.work, "fetch", "-q", "--all")
}

/** Pull from one remote, push to another — and what the app reads and does about it. */
val ForkSupport by testSuite {
    testFixture { Sandbox() } asContextForEach {

        // --- detection and setup --------------------------------------------------------------

        test("the mainline offers every other remote to pull from, upstream first, whatever they're called") {
            val f = fork(upstreamName = "sksamuel")
            git(f.work, "remote", "add", "upstream", f.upstream.path)
            git(f.work, "fetch", "-q", "upstream")

            val main = BranchOps.load(f.work.path).single { it.isMainline }

            assert(main.pullCandidates == listOf("upstream/${Sandbox.MAIN}", "sksamuel/${Sandbox.MAIN}")) {
                "got ${main.pullCandidates}"
            }
            assert(!main.isTriangular)
        }

        test("trackUpstream pulls from the chosen remote and pins pushes to the current one") {
            val f = fork()

            val result = BranchOps.trackUpstream(f.work.path, Sandbox.MAIN, "upstream/${Sandbox.MAIN}")

            assert(result.ok) { result.message }
            assert(git(f.work, "config", "branch.${Sandbox.MAIN}.remote").trim() == "upstream")
            assert(git(f.work, "config", "remote.pushDefault").trim() == "origin")
        }

        test("trackUpstream leaves a pushDefault the user already set") {
            val f = fork()
            git(f.work, "config", "remote.pushDefault", "elsewhere")

            BranchOps.trackUpstream(f.work.path, Sandbox.MAIN, "upstream/${Sandbox.MAIN}")

            assert(git(f.work, "config", "remote.pushDefault").trim() == "elsewhere")
        }

        test("load reads a fork's push side even though %(push) is empty under push.default=simple") {
            val f = fork()
            BranchOps.trackUpstream(f.work.path, Sandbox.MAIN, "upstream/${Sandbox.MAIN}")
            advanceUpstream(f)
            // The premise: git itself can't name the push ref here, which is why the app rebuilds it.
            assert(gitAllowingFailure(f.work, "rev-parse", "@{push}") != 0)

            val main = BranchOps.load(f.work.path).single { it.isMainline }

            assert(main.upstream == "upstream/${Sandbox.MAIN}")
            assert(main.upstreamBehind == 2)
            assert(main.pushRef == "origin/${Sandbox.MAIN}") { "got ${main.pushRef}" }
            assert(main.pushAhead == 0 && main.pushBehind == 0 && !main.pushMissing)
            // And the way back.
            assert(main.pullCandidates == listOf("origin/${Sandbox.MAIN}"))
        }

        test("the scan counts unpushed commits against the fork, not the upstream") {
            val f = fork()
            BranchOps.trackUpstream(f.work.path, Sandbox.MAIN, "upstream/${Sandbox.MAIN}")
            advanceUpstream(f)

            val before = RepoScanner.scan(entry(f.work), fetch = false)
            assert(before.upstream == "upstream/${Sandbox.MAIN}" && before.behind == 2)
            assert(before.pushTarget == "origin/${Sandbox.MAIN}" && before.unpushed == 0)

            git(f.work, "merge", "-q", "--ff-only", "upstream/${Sandbox.MAIN}")
            val after = RepoScanner.scan(entry(f.work), fetch = false)

            // Level with the upstream, two commits short on the fork: that's what Push would send.
            assert(after.ahead == 0 && after.behind == 0)
            assert(after.unpushed == 2) { "got ${after.unpushed}" }
        }

        test("remote branches group by remote, the mainline's push remote first, then its pull remote") {
            val f = fork()
            git(f.work, "remote", "add", "alpha", f.upstream.path)
            git(f.work, "fetch", "-q", "--all")
            BranchOps.trackUpstream(f.work.path, Sandbox.MAIN, "upstream/${Sandbox.MAIN}")
            val main = BranchOps.load(f.work.path).single { it.isMainline }
            val remotes = BranchOps.loadRemotes(f.work.path, setOf(Sandbox.MAIN))

            val lead = listOfNotNull(main.pushRef, main.upstream).map { it.substringBefore('/') }
            val groups = groupByRemote(remotes, lead)

            assert(groups.map { it.first } == listOf("origin", "upstream", "alpha")) { "got ${groups.map { it.first }}" }
            assert(groups.all { (remote, list) -> list.all { it.remote == remote } })
        }

        // --- sync ------------------------------------------------------------------------------

        test("sync fast-forwards the checked-out mainline and pushes it to the fork") {
            val f = fork()
            BranchOps.trackUpstream(f.work.path, Sandbox.MAIN, "upstream/${Sandbox.MAIN}")
            advanceUpstream(f)
            val main = BranchOps.load(f.work.path).single { it.isMainline }

            val result = BranchOps.sync(f.work.path, main)

            assert(result.ok) { result.message }
            val tip = rev(f.upstream, Sandbox.MAIN)
            assert(rev(f.work, Sandbox.MAIN) == tip)
            assert(rev(f.origin, Sandbox.MAIN) == tip)
            assert(git(f.work, "config", "branch.${Sandbox.MAIN}.remote").trim() == "upstream")
        }

        test("sync updates a mainline that isn't checked out without touching the working tree") {
            val f = fork()
            BranchOps.trackUpstream(f.work.path, Sandbox.MAIN, "upstream/${Sandbox.MAIN}")
            git(f.work, "switch", "-q", "-c", "feature")
            advanceUpstream(f)
            val main = BranchOps.load(f.work.path).single { it.isMainline }
            assert(!main.isCurrent)

            val result = BranchOps.sync(f.work.path, main)

            assert(result.ok) { result.message }
            assert(rev(f.work, Sandbox.MAIN) == rev(f.upstream, Sandbox.MAIN))
            assert(rev(f.origin, Sandbox.MAIN) == rev(f.upstream, Sandbox.MAIN))
            assert(git(f.work, "symbolic-ref", "--short", "HEAD").trim() == "feature")
            assert(!File(f.work, "up0.txt").exists())
        }

        test("sync refuses a mainline with commits of its own and pushes nothing") {
            val f = fork()
            BranchOps.trackUpstream(f.work.path, Sandbox.MAIN, "upstream/${Sandbox.MAIN}")
            git(f.work, "switch", "-q", "-c", "feature")
            git(f.work, "switch", "-q", Sandbox.MAIN)
            commit(f.work, "mine.txt", "mine\n", "local work on main")
            git(f.work, "switch", "-q", "feature")
            advanceUpstream(f)
            val main = BranchOps.load(f.work.path).single { it.isMainline }
            val forkBefore = rev(f.origin, Sandbox.MAIN)
            val localBefore = rev(f.work, Sandbox.MAIN)

            val result = BranchOps.sync(f.work.path, main)

            assert(!result.ok)
            assert(rev(f.work, Sandbox.MAIN) == localBefore)
            assert(rev(f.origin, Sandbox.MAIN) == forkBefore)
        }

        // --- push and delete ---------------------------------------------------------------------

        test("publishing a branch that pulls from upstream sends it to the fork and keeps its upstream") {
            val f = fork()
            git(f.work, "config", "remote.pushDefault", "origin")
            git(f.work, "switch", "-q", "--track", "-c", "feature", "upstream/${Sandbox.MAIN}")
            commit(f.work, "f.txt", "mine\n", "feature work")
            val feature = BranchOps.load(f.work.path).single { it.name == "feature" }
            assert(feature.pushRef == "origin/feature" && feature.pushMissing)

            val result = BranchOps.push(f.work.path, "feature", feature.upstream, feature.pushRef)

            assert(result.ok) { result.message }
            assert(rev(f.origin, "feature") == rev(f.work, "feature"))
            assert(gitAllowingFailure(f.upstream, "rev-parse", "--verify", "-q", "feature") != 0)
            // No -u: the branch still pulls from upstream.
            assert(git(f.work, "config", "branch.feature.remote").trim() == "upstream")
        }

        test("the remote copy to delete is the fork's, never the upstream's") {
            val f = fork()
            git(f.work, "config", "remote.pushDefault", "origin")
            git(f.work, "switch", "-q", "--track", "-c", "feature", "upstream/${Sandbox.MAIN}")
            commit(f.work, "f.txt", "mine\n", "feature work")
            assert(BranchOps.deletableUpstream(f.work, "feature") == null) // nothing on the fork yet
            git(f.work, "push", "-q", "origin", "feature")

            assert(BranchOps.deletableUpstream(f.work, "feature") == "origin/feature")
        }

        test("a branch with no upstream is published to remote.pushDefault rather than origin") {
            val f = fork()
            git(f.work, "config", "remote.pushDefault", "upstream")
            git(f.work, "switch", "-q", "-c", "loose")
            commit(f.work, "l.txt", "loose\n", "loose work")
            // What the Push prompt names, before anything is pushed.
            assert(RepoScanner.scan(entry(f.work), fetch = false).publishRemote == "upstream")

            val result = RepoOps.push(entry(f.work))

            assert(result.ok) { result.message }
            assert(rev(f.upstream, "loose") == rev(f.work, "loose"))
            assert(gitAllowingFailure(f.origin, "rev-parse", "--verify", "-q", "loose") != 0)
        }
    }
}
