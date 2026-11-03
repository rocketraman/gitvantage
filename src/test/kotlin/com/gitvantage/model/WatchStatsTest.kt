// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.model

import de.infix.testBalloon.framework.core.testSuite
import java.nio.file.Path

/** Which repos and folders the watcher's events come from — see [WatchStats]. */
val WatchStatistics by testSuite {

    val root = Path.of("/home/u/src/proj")
    fun at(rel: String) = root.resolve(rel)

    test("git's own files are named, down to the entry under .git") {
        assert(WatchStats.folderOf(root, at(".git/index")) == ".git/index")
        assert(WatchStats.folderOf(root, at(".git/refs/heads/main")) == ".git/refs")
    }

    test("generic folders are named, and a project's own folders are not") {
        assert(WatchStats.folderOf(root, at("build/classes/A.class")) == "build/")
        assert(WatchStats.folderOf(root, at("node_modules/x/index.js")) == "node_modules/")
        assert(WatchStats.folderOf(root, at(".claude/worktrees/wt/a.txt")) == ".claude/")
        assert(WatchStats.folderOf(root, at("acme-secret/plan.md")) == "(other folder)")
    }

    test("a file at the top and a path elsewhere are told apart") {
        assert(WatchStats.folderOf(root, at("README.md")) == "(root files)")
        assert(WatchStats.folderOf(root, root) == "(root files)")
        assert(WatchStats.folderOf(root, Path.of("/home/u/src/proj-two/a")) == "(outside)")
    }

    test("the report ranks repos by events, with their rescans and folder shares, under the caller's label") {
        WatchStats.reset()
        Perf.enable(true)
        try {
            repeat(3) { WatchStats.event("/quiet", "(root files)") }
            repeat(6) { WatchStats.event("/busy", "build/") }
            repeat(2) { WatchStats.event("/busy", ".git/index") }
            WatchStats.rescan("/busy")
            val lines = WatchStats.report { if (it == "/busy") "repo #2" else "repo #1" }
            assert(lines.size == 3) { "got $lines" }
            assert(lines[1].trim().startsWith("repo #2")) { "busiest not first: $lines" }
            assert("events=8" in lines[1] && "rescans=1" in lines[1]) { "got ${lines[1]}" }
            assert("build/ 75% · .git/index 25%" in lines[1]) { "got ${lines[1]}" }
            assert(lines.none { "/busy" in it || "/quiet" in it }) { "a path leaked into the report: $lines" }
        } finally {
            Perf.enable(false)
            WatchStats.reset()
        }
    }

    test("repos rescanned without events of their own stay out of the list") {
        WatchStats.reset()
        Perf.enable(true)
        try {
            // What a watcher overflow leaves: every repo rescanned, one with real events.
            (1..6).forEach { WatchStats.rescan("/r$it") }
            WatchStats.event("/r6", "build/")
            val lines = WatchStats.report { it.removePrefix("/") }
            assert(lines.size == 2 && lines[1].trim().startsWith("r6")) { "got $lines" }
        } finally {
            Perf.enable(false)
            WatchStats.reset()
        }
    }

    test("a share too small to round to a percent reads as under one") {
        assert(WatchStats.share(1, 994) == "<1%")
        assert(WatchStats.share(0, 994) == "0%")
        assert(WatchStats.share(990, 994) == "99%")
    }

    test("nothing is recorded while collection is off") {
        WatchStats.reset()
        Perf.enable(false)
        if (!Perf.forcedOn) {
            WatchStats.event("/r", "build/")
            assert(WatchStats.report { it }.isEmpty())
        }
    }
}
