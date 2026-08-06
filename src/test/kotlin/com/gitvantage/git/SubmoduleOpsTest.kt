// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git

import com.gitvantage.git.model.Submodule
import de.infix.testBalloon.framework.core.testSuite
import java.io.File

/**
 * Submodule mutations: fetch, fetchAll, init, sync, deinit and updatePointer.
 *
 * A submodule needs three repositories — the parent, the submodule's own origin, and the checkout
 * inside the parent — so these arrange more than the other suites. [withSubmodule] builds that
 * once and hands back the parent.
 */
val SubmoduleOperations by testSuite {
    testFixture { Sandbox() } asContextForEach {

        /** A parent repo with `lib` added as a submodule, committed and pushed. */
        fun Sandbox.withSubmodule(): File {
            val parent = repo("parent")
            val libOrigin = bare("lib")
            val libWork = clone(libOrigin, "lib-src")
            commit(libWork, "lib.txt", "v1\n", "lib first")
            git(libWork, "push", "-q", "-u", "origin", Sandbox.MAIN)
            git(parent, "submodule", "add", "-q", libOrigin.path, "lib")
            git(parent, "commit", "-q", "-m", "add submodule")
            return parent
        }

        /** Advance the submodule's origin so the parent's recorded pointer falls behind. */
        fun Sandbox.advanceLibOrigin() {
            val libWork = File(root, "lib-src")
            commit(libWork, "lib.txt", "v2\n", "lib second")
            git(libWork, "push", "-q")
        }

        test("load reports the submodule as initialized with its recorded pointer") {
            val parent = withSubmodule()

            val subs = SubmoduleOps.load(parent.path)

            assert(subs.size == 1)
            assert(subs[0].path == "lib")
            assert(subs[0].initialized)
            assert(subs[0].recordedFull == rev(File(parent, "lib"), "HEAD"))
        }

        test("fetch updates the submodule's remote-tracking ref") {
            val parent = withSubmodule()
            advanceLibOrigin()
            val lib = File(parent, "lib")
            val before = rev(lib, "origin/${Sandbox.MAIN}")

            val result = SubmoduleOps.fetch(parent.path, "lib")

            assert(result.ok)
            assert(result.message == "Fetched lib")
            assert(rev(lib, "origin/${Sandbox.MAIN}") != before)
            // A fetch must not move the checkout itself.
            assert(rev(lib, "HEAD") == before)
        }

        test("fetch reports a submodule that is not initialized") {
            val parent = withSubmodule()
            git(parent, "submodule", "deinit", "-f", "--", "lib")

            val result = SubmoduleOps.fetch(parent.path, "lib")

            assert(!result.ok)
            assert(result.message == "lib not initialized")
        }

        test("fetchAll counts the submodules it fetched") {
            val parent = withSubmodule()
            val subs = SubmoduleOps.load(parent.path)

            val result = SubmoduleOps.fetchAll(parent.path, subs)

            assert(result.ok)
            assert(result.message == "Fetched 1/1 submodules")
        }

        test("fetchAll reports when there is nothing initialized to fetch") {
            val parent = withSubmodule()
            git(parent, "submodule", "deinit", "-f", "--", "lib")
            val subs = SubmoduleOps.load(parent.path)

            val result = SubmoduleOps.fetchAll(parent.path, subs)

            assert(!result.ok)
            assert(result.message == "No initialized submodules")
        }

        test("init checks out a submodule that is not yet present") {
            val parent = withSubmodule()
            git(parent, "submodule", "deinit", "-f", "--", "lib")
            assert(!Git.isRepo(File(parent, "lib")))

            val result = SubmoduleOps.init(parent.path, "lib")

            assert(result.ok)
            assert(result.message == "Initialized lib")
            assert(Git.isRepo(File(parent, "lib")))
            assert(File(parent, "lib/lib.txt").readText() == "v1\n")
        }

        test("sync re-points the submodule remote at the URL in .gitmodules") {
            val parent = withSubmodule()
            val moved = bare("lib-moved")
            git(parent, "config", "--file", ".gitmodules", "submodule.lib.url", moved.path)

            val result = SubmoduleOps.sync(parent.path, "lib")

            assert(result.ok)
            assert(result.message == "Synced lib URL")
            assert(git(File(parent, "lib"), "remote", "get-url", "origin").trim() == moved.path)
        }

        test("deinit removes the submodule's working tree, leaving it re-initable") {
            val parent = withSubmodule()
            assert(File(parent, "lib/lib.txt").exists())

            val result = SubmoduleOps.deinit(parent.path, "lib")

            assert(result.ok)
            assert(result.message == "Deinitialized lib")
            assert(!File(parent, "lib/lib.txt").exists())
            // The mapping survives, which is what makes it re-initable.
            assert("submodule.lib.url" in git(parent, "config", "--file", ".gitmodules", "--list"))
        }

        test("updatePointer advances the submodule and stages the new gitlink") {
            val parent = withSubmodule()
            advanceLibOrigin()
            val lib = File(parent, "lib")
            val before = rev(lib, "HEAD")

            val result = SubmoduleOps.updatePointer(parent.path, "lib")

            assert(result.ok)
            assert("Advanced lib" in result.message)
            assert(rev(lib, "HEAD") != before)
            // Staged but not committed: recording it in the parent is the user's call.
            assert("lib" in git(parent, "diff", "--cached", "--name-only"))
            assert(git(parent, "log", "-1", "--format=%s").trim() == "add submodule")
        }

        test("updatePointer fails on a path that is not a submodule") {
            val parent = withSubmodule()

            val result = SubmoduleOps.updatePointer(parent.path, "not-a-submodule")

            assert(!result.ok)
            assert(result.message.isNotBlank())
        }
    }
}
