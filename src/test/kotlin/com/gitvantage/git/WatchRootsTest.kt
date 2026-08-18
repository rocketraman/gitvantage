// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git

import de.infix.testBalloon.framework.core.testSuite

/**
 * How many watch registrations a repo costs.
 *
 * This is a resource test, not a behaviour test, and it exists because getting it wrong did real
 * damage outside the app. Every registration is a native watcher, and on Linux a native watcher is
 * an `inotify_init()` drawn from a limit shared with every other process on the machine. Splitting a
 * repo's watch into one per non-ignored top-level directory took 36 repos from 36 instances to 258
 * of 512, and the desktop began warning that file monitoring was about to fail — for everything, not
 * only for this app.
 *
 * So the count is the thing worth pinning. A future change that makes the watch set cleverer has to
 * come with a way of sharing one instance across registrations, or it will do this again quietly.
 */
val RepoWatchRoots by testSuite {
    testFixture { Sandbox() } asContextForEach {

        test("a repo costs exactly one registration, however many directories it has") {
            val work = repo()
            commit(work, ".gitignore", "build/\n", "ignore build output")
            listOf("build/classes", "src/main", "docs", "gradle", "packaging").forEach {
                java.io.File(work, it).mkdirs()
            }

            val roots = WatchRoots.forRepo(work.path)

            assert(roots.size == 1) {
                "a repo now costs ${roots.size} inotify instances instead of 1 — see this file's header"
            }
        }

        test("that one registration is recursive and rooted at the repo") {
            val work = repo()
            val root = WatchRoots.forRepo(work.path).single()

            // Recursive because it is the only watch there is: anything shallower would leave the
            // repo's own subdirectories unwatched entirely.
            assert(root.recursive)
            assert(root.path == work.path)
        }

        test("a folder that isn't a repo still yields a watchable root") {
            // Registration happens before any scan proves the path is a repo, so this must not
            // depend on git succeeding.
            val gone = java.io.File(root, "not-here")
            assert(WatchRoots.forRepo(gone.path) == listOf(WatchRoot(gone.path, recursive = true)))
        }
    }
}
