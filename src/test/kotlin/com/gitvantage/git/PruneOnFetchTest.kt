// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git

import de.infix.testBalloon.framework.core.testSuite
import java.io.File

/** A clone holding origin/feature for a branch the remote has since deleted, as GitHub does on merge. */
private fun Sandbox.deletedOnRemote(): File {
    val work = repo()
    git(work, "push", "-q", "origin", "${Sandbox.MAIN}:refs/heads/feature")
    git(work, "fetch", "-q")
    git(originOf(work), "branch", "-D", "feature")
    return work
}

private fun Sandbox.hasRemoteCopy(work: File) =
    gitAllowingFailure(work, "rev-parse", "--verify", "-q", "refs/remotes/origin/feature") == 0

/** The scan's fetch, and the per-repo choice of whether it prunes. */
val PruneOnFetch by testSuite {
    testFixture { Sandbox() } asContextForEach {

        test("a fetch drops the copy of a branch the remote deleted, by default") {
            val work = deletedOnRemote()

            RepoScanner.scan(entry(work), fetch = true)

            assert(!hasRemoteCopy(work))
        }

        test("a repo that turned pruning off keeps it") {
            val work = deletedOnRemote()

            RepoScanner.scan(entry(work).copy(pruneOnFetch = false), fetch = true)

            assert(hasRemoteCopy(work))
        }
    }
}
