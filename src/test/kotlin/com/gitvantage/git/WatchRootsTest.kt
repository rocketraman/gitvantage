// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git

import de.infix.testBalloon.framework.core.testSuite
import java.io.File

/**
 * Which directories a repo gets watched through.
 *
 * The point of the split is that build output never gets walked: registering a recursive watch means
 * walking the tree it covers, and in a working repo almost every file on disk is an artefact. On this
 * project it was 5,113 ignored files out of 5,209, essentially all in `build/`.
 *
 * Asked of real repos with real ignore rules rather than a stubbed matcher, because the answer comes
 * from `git check-ignore` and the whole point is to inherit git's rules rather than reimplement them.
 */
val RepoWatchRoots by testSuite {
    testFixture { Sandbox() } asContextForEach {

        test("an ignored directory is not watched, and the rest of the repo still is") {
            val work = repo()
            commit(work, ".gitignore", "build/\n", "ignore build output")
            File(work, "build/classes").mkdirs()
            File(work, "src/main").mkdirs()

            val roots = WatchRoots.forRepo(work.path)
            val names = roots.map { File(it.path).name }

            assert("build" !in names) { "the ignored directory is still watched: $names" }
            assert("src" in names) { "the source directory stopped being watched: $names" }
        }

        test("the repo folder itself is watched shallowly, so top-level files still report") {
            val work = repo()
            File(work, "src").mkdirs()

            val roots = WatchRoots.forRepo(work.path)
            val self = roots.single { it.path == work.path }

            // Shallow on purpose: recursive here would walk everything the split exists to avoid,
            // ignored directories included, and make the rest of the list pointless.
            assert(!self.recursive) { "the repo root is still registered recursively" }
            assert(roots.filter { it.path != work.path }.all { it.recursive })
        }

        /**
         * `.git` is not something git reports as ignored, and it must not be dropped: HEAD, refs and
         * the index are how a commit, a checkout or a fetch becomes visible to the app at all.
         */
        test(".git is watched") {
            val work = repo()
            val roots = WatchRoots.forRepo(work.path)
            assert(roots.any { File(it.path).name == ".git" }) { "nothing would notice a commit" }
        }

        test("git's own rules are inherited, negations included") {
            val work = repo()
            // A pattern that a hand-rolled matcher gets wrong: everything ignored, then one taken
            // back. Only git knows this, which is why git is asked.
            commit(work, ".gitignore", "vendor/\n!vendor/keep/\n", "ignore vendor except keep")
            File(work, "vendor/drop").mkdirs()
            File(work, "vendor/keep").mkdirs()

            val names = WatchRoots.forRepo(work.path).map { File(it.path).name }
            assert("vendor" !in names) { "the ignored parent is watched: $names" }
        }

        test("a repo with nothing ignored watches every directory it has") {
            val work = repo()
            File(work, "src").mkdirs()
            File(work, "docs").mkdirs()

            val names = WatchRoots.forRepo(work.path).map { File(it.path).name }.toSet()
            assert(setOf("src", "docs", ".git").all { it in names }) { "got $names" }
        }

        /**
         * The fallback direction matters: watching too much is slow, watching too little is wrong.
         * A path that isn't a repo — or a folder that has gone away — must come back as the single
         * recursive watch this replaced, not as an empty list that silently watches nothing.
         */
        test("an unreadable folder falls back to one recursive watch") {
            val gone = File(root, "not-here")
            assert(WatchRoots.forRepo(gone.path) == listOf(WatchRoot(gone.path, recursive = true)))
        }

        test("child directory names are what re-registration is keyed on") {
            val work = repo()
            File(work, "src").mkdirs()
            val before = WatchRoots.childDirNames(work.path)

            File(work, "newmodule").mkdirs()

            // The shallow root watch reports the new directory; this is what tells the app the watch
            // set is stale, without paying `check-ignore` after every burst to find out.
            assert(WatchRoots.childDirNames(work.path) != before)
            assert("newmodule" in WatchRoots.childDirNames(work.path))
        }
    }
}
