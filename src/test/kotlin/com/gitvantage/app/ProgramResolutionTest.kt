// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.app

import de.infix.testBalloon.framework.core.testSuite
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * How [Programs] decides an external program exists and how it should be run — the pure half of
 * the "Open in …" fix, which is the half that can be checked without a Mac or a Windows box.
 *
 * [Programs.find] and [Programs.invocation] take their search path and their platform as arguments
 * precisely so this suite can pose the Windows questions from Linux; only the caching wrapper
 * reads the real environment.
 */
val ProgramResolution by testSuite {

    /** A file at [name] under [dir], executable, so it passes the Unix half of the check. */
    fun program(dir: File, name: String): File =
        File(dir, name).apply { writeText(""); setExecutable(true) }

    test("a bare name resolves to the first directory on the search path that has it") {
        val root = createTempDirectory("progs").toFile()
        val first = File(root, "first").apply { mkdirs() }
        val second = File(root, "second").apply { mkdirs() }
        program(second, "kitty")
        val late = Programs.find("kitty", listOf(first, second), listOf(""))
        assert(late == File(second, "kitty")) { "expected the only copy, got $late" }

        val early = program(first, "kitty")
        assert(Programs.find("kitty", listOf(first, second), listOf("")) == early) {
            "an earlier directory must win, as it does in a shell"
        }
    }

    test("on Windows the directory is the outer loop and the extension the inner one") {
        // The order Windows itself searches, and the one that surprises people: an earlier
        // directory's .cmd beats a later directory's .exe, even though .exe sorts first in PATHEXT.
        val root = createTempDirectory("progs").toFile()
        val first = File(root, "first").apply { mkdirs() }
        val second = File(root, "second").apply { mkdirs() }
        val exts = listOf(".COM", ".EXE", ".BAT", ".CMD", "")
        val cmd = program(first, "code.CMD")
        program(second, "code.EXE")
        assert(Programs.find("code", listOf(first, second), exts) == cmd)
    }

    test("a name already carrying its extension still resolves") {
        // wt.exe is spelled with the extension because that is how Windows Terminal is documented.
        // Every real PATHEXT entry misses it — "wt.exe.EXE" — so the empty extension has to be
        // there to catch it, or Windows Terminal is invisible.
        val dir = createTempDirectory("progs").toFile()
        val wt = program(dir, "wt.exe")
        assert(Programs.find("wt.exe", listOf(dir), listOf(".COM", ".EXE", ".BAT", ".CMD", "")) == wt)
    }

    test("a name with a separator is a location, not something to search for") {
        // This is how a $TERMINAL set to an absolute path resolves.
        val dir = createTempDirectory("progs").toFile()
        val term = program(dir, "myterm")
        assert(Programs.find(term.absolutePath, emptyList(), listOf("")) == term)
        assert(Programs.find("/nonexistent/myterm", emptyList(), listOf("")) == null)
    }

    test("a program that is not installed resolves to nothing") {
        // The whole point of resolving up front: this is the answer a chain needs in order to try
        // its next rung, and the answer the old start-means-success test could not give.
        val dir = createTempDirectory("progs").toFile()
        assert(Programs.find("definitely-not-installed", listOf(dir), listOf("")) == null)
    }

    test("a directory is not a program") {
        val dir = createTempDirectory("progs").toFile()
        File(dir, "code").mkdirs()
        assert(Programs.find("code", listOf(dir), listOf("")) == null)
    }

    test("a Windows batch file is run through the interpreter, a real image directly") {
        // CreateProcess cannot start a .cmd. VS Code lands on PATH as code.cmd, so without this
        // wrapping "Open in IDE" fails on a Windows machine that plainly has VS Code installed —
        // and the failure looks exactly like "not installed".
        val dir = createTempDirectory("progs").toFile()
        val batch = program(dir, "code.cmd")
        val image = program(dir, "wt.exe")

        val wrapped = Programs.invocation(batch, Os.WINDOWS)
        assert(wrapped.size == 3 && wrapped[1] == "/c" && wrapped[2] == batch.absolutePath) {
            "expected an interpreter invocation, got $wrapped"
        }
        assert(Programs.invocation(image, Os.WINDOWS) == listOf(image.absolutePath))
        // Nothing is wrapped off Windows, where the extension carries no such meaning.
        assert(Programs.invocation(batch, Os.LINUX) == listOf(batch.absolutePath))
        assert(Programs.invocation(batch, Os.MAC) == listOf(batch.absolutePath))
    }

    test("every copy on the search path is findable, not just the first") {
        // What "Open in git GUI" needs. The first git on a Mac launched from Finder is Apple's,
        // which carries no git-gui; the Homebrew git that does is further down the path. Stopping
        // at the first would report "not installed" on the machines where it *is* installed.
        val root = createTempDirectory("progs").toFile()
        val first = File(root, "first").apply { mkdirs() }
        val second = File(root, "second").apply { mkdirs() }
        val apple = program(first, "git")
        val brew = program(second, "git")

        val all = Programs.findAll("git", listOf(first, second), listOf(""))
        assert(all == listOf(apple, brew)) { "expected both, in search order, got $all" }
        // find stays the first of them, so nothing that only wants one answer changed.
        assert(Programs.find("git", listOf(first, second), listOf("")) == apple)
    }

    test("a located path yields at most one answer") {
        val dir = createTempDirectory("progs").toFile()
        val git = program(dir, "git")
        assert(Programs.findAll(git.absolutePath, listOf(dir), listOf("")) == listOf(git))
        assert(Programs.findAll("/nonexistent/git", listOf(dir), listOf("")).isEmpty())
    }

    test("macOS is recognised before Windows") {
        // "darwin" contains "win". Testing for Windows first would call a Mac a PC, and every
        // launch would go to cmd.exe.
        assert(Os.from("Mac OS X") == Os.MAC)
        assert(Os.from("Darwin") == Os.MAC)
        assert(Os.from("Windows 11") == Os.WINDOWS)
        assert(Os.from("Linux") == Os.LINUX)
        assert(Os.from("FreeBSD") == Os.LINUX)
    }
}
