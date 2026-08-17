// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git

import de.infix.testBalloon.framework.core.testSuite

/**
 * The `## ` header of `git status --branch`, which now answers what `symbolic-ref`,
 * `rev-parse @{upstream}` and `rev-list --count` used to answer in three more subprocesses.
 *
 * Every string here was copied from a real `git status --porcelain --branch` rather than written
 * from memory — the whole saving rests on reading git's own wording exactly, and a header shape
 * misread is a branch name, a tracking pair or a divergence count silently wrong on every row.
 */
val StatusBranchHeader by testSuite {

    test("a branch with no upstream configured") {
        val h = RepoScanner.parseBranch("## main")!!
        assert(h.branch == "main")
        assert(h.upstream == null)
        assert(h.ahead == 0 && h.behind == 0)
    }

    test("a tracking branch in sync") {
        val h = RepoScanner.parseBranch("## main...origin/main")!!
        assert(h.branch == "main")
        assert(h.upstream == "origin/main")
        assert(h.ahead == 0 && h.behind == 0)
    }

    test("one side of the divergence only") {
        val ahead = RepoScanner.parseBranch("## main...origin/main [ahead 1]")!!
        assert(ahead.ahead == 1 && ahead.behind == 0)
        // Not symmetric by accident: a lone "behind" must not be read into the ahead slot, which
        // is what a positional parse of the bracket would do.
        val behind = RepoScanner.parseBranch("## main...origin/main [behind 3]")!!
        assert(behind.ahead == 0 && behind.behind == 3)
    }

    test("both sides") {
        val h = RepoScanner.parseBranch("## main...origin/main [ahead 1, behind 2]")!!
        assert(h.ahead == 1 && h.behind == 2)
    }

    /**
     * The case worth being deliberate about. `rev-parse @{upstream}` *fails* for a deleted upstream,
     * so the repo has always shown "No upstream" for it; reporting the dead branch's name instead
     * would look like tracking that works and quietly stop warning.
     */
    test("a gone upstream reads as no upstream, as the command it replaces did") {
        val h = RepoScanner.parseBranch("## main...origin/main [gone]")!!
        assert(h.branch == "main")
        assert(h.upstream == null) { "a deleted upstream was reported as tracked: ${h.upstream}" }
    }

    test("a detached HEAD has no branch") {
        val h = RepoScanner.parseBranch("## HEAD (no branch)")!!
        assert(h.branch == null)
        assert(h.upstream == null)
    }

    test("a branch with no commits on it yet still names the branch") {
        val h = RepoScanner.parseBranch("## No commits yet on main")!!
        assert(h.branch == "main")
        assert(h.upstream == null)
    }

    test("the header is found among the file lines, and absent when not asked for") {
        val out = "## main...origin/main [ahead 1]\n M src/App.kt\n?? new.txt\n"
        assert(RepoScanner.parseBranch(out)?.ahead == 1)
        assert(RepoScanner.parseBranch(" M src/App.kt\n?? new.txt\n") == null)
    }

    /**
     * The header shares a stream with the file list, and `##` is a legal pair of status codes — so
     * left in, it counts as an index *and* a worktree change to a file named after the branch. Every
     * dirty count on the dashboard would be two too high.
     */
    test("the header is not counted as a changed file") {
        val out = "## main...origin/main [ahead 1, behind 2]\n M src/App.kt\n?? new.txt\n"
        val s = RepoScanner.parseStatus(out)

        assert(s.staged == 0) { "the ## header was counted as a staged change" }
        assert(s.unstaged == 1)
        assert(s.untracked == 1)
        assert(s.files.map { it.path } == listOf("src/App.kt", "new.txt"))
    }
}

/**
 * `git remote -v`, which now answers both "is there a remote at all" and "what is origin's URL" —
 * one command where the scan used to run `remote` and then `remote get-url origin`.
 */
val RemoteListing by testSuite {

    test("origin's fetch URL, and that a remote exists at all") {
        val out = "origin\tgit@github.com:owner/repo.git (fetch)\norigin\tgit@github.com:owner/repo.git (push)\n"
        val (hasRemote, url) = RepoScanner.parseRemotes(out)
        assert(hasRemote)
        assert(url == "git@github.com:owner/repo.git")
    }

    test("origin is picked out from among other remotes, wherever it sits") {
        val out = buildString {
            append("upstream\thttps://example.com/x.git (fetch)\n")
            append("upstream\thttps://example.com/x.git (push)\n")
            append("origin\tgit@github.com:owner/repo.git (fetch)\n")
            append("origin\tgit@github.com:owner/repo.git (push)\n")
        }
        val (hasRemote, url) = RepoScanner.parseRemotes(out)
        assert(hasRemote)
        assert(url == "git@github.com:owner/repo.git") { "picked the wrong remote: $url" }
    }

    /**
     * The two answers are genuinely independent: Push and Fetch are gated on *any* remote existing,
     * while the GitHub links need origin specifically. A repo with a remote under another name has
     * to keep the buttons and lose the links.
     */
    test("a remote that is not origin still counts as having a remote") {
        val out = "backup\thttps://example.com/x.git (fetch)\nbackup\thttps://example.com/x.git (push)\n"
        val (hasRemote, url) = RepoScanner.parseRemotes(out)
        assert(hasRemote)
        assert(url == null)
    }

    test("no remotes at all") {
        val (hasRemote, url) = RepoScanner.parseRemotes("")
        assert(!hasRemote)
        assert(url == null)
    }

    test("a fetch URL containing a space is kept whole") {
        // The suffix is stripped from the last " (fetch)", not the first space — a checkout under a
        // path with a space in it is ordinary on macOS and Windows.
        val out = "origin\t/Users/me/My Repos/thing (fetch)\norigin\t/Users/me/My Repos/thing (push)\n"
        assert(RepoScanner.parseRemotes(out).second == "/Users/me/My Repos/thing")
    }
}
