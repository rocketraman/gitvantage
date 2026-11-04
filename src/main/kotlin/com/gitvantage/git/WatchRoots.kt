// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git

import java.io.File

/** One directory to register with the filesystem watcher, and whether to descend into it. */
data class WatchRoot(val path: String, val recursive: Boolean)

/**
 * A repo's top-level directories as they were when last asked about ([checked]), and which of them
 * git ignores without tracking anything inside ([ignored]) — the ones whose events can be dropped.
 */
data class IgnoredDirs(val checked: Set<String>, val ignored: Set<String>) {
    companion object {
        val NONE = IgnoredDirs(emptySet(), emptySet())
    }
}

/**
 * Which directories of a repo are worth watching.
 *
 * **Currently: one recursive watch on the repo, and nothing clever.** The pruning this file was
 * written for is withdrawn, and the reason is worth keeping because the idea is still right and only
 * the mechanism is unavailable.
 *
 * The idea: registering a recursive watch walks the tree it covers, and in a working repo almost
 * every file on disk is build output — 5,113 ignored of 5,209 on this project, essentially all in
 * `build/`. Watching only what git doesn't ignore skips that walk.
 *
 * The mechanism: neither the watcher nor the library underneath it takes an exclusion list, so the
 * pruning had to be expressed as *what to register* — a shallow watch on the repo plus a recursive
 * watch per non-ignored top-level directory. About seven registrations where there had been one.
 *
 * The mechanism is what failed. Every registration creates its own native watcher, and on Linux a
 * native watcher is an `inotify_init()` — one of a *system-wide* budget shared with every other
 * application. Thirty-six repos went from 36 instances to 258 of a 512 limit, and the desktop
 * started warning that file monitoring was about to stop working. For everything, not just here.
 *
 * That is a bad trade at any exchange rate: what pruning bought was about half a second of startup
 * on a background thread; what it cost was half the machine's capacity to watch files at all.
 *
 * What is still possible without that is to drop the events: the build directory is walked and
 * watched as before, but a write inside it no longer rescans the repo — see [ignoredDirs]. That
 * saves the rescans, which a field report showed are what a build actually costs (one repo's
 * `build/` was 97% of its events and half of all the app's rescans), and leaves the walk.
 *
 * So this stays a single watch until the watcher can either share one inotify instance across
 * registrations or take an exclusion list directly — see NucleusFramework/Nucleus#571. That one is
 * fixed upstream (a shared native watcher) but only on the unreleased 2.6/3.0 line: it is not in
 * any 2.5.x, 2.5.18 included, so check the release it ships in before bringing the pruning back.
 * The `check-ignore` query that worked out what to skip is in the history, and the tests with it.
 */
object WatchRoots {

    /**
     * The watch set for the repo at [repoPath]: one recursive registration, as it was before the
     * pruning experiment. See this file's header for why it is not several.
     */
    fun forRepo(repoPath: String): List<WatchRoot> = listOf(WatchRoot(repoPath, recursive = true))

    /**
     * Which of the top-level directories of the repo at [repoPath] git ignores and holds nothing
     * tracked in — the ones a write inside can't change what a scan finds.
     *
     * Asked of git rather than worked out here. `.gitignore` is a bigger specification than it looks
     * — negation, directory-only patterns, `core.excludesFile`, `.git/info/exclude` — and getting any
     * of it wrong would drop the events of a directory the user is working in. Names go as
     * arguments, not on stdin, because [Git] never writes to a child's stdin.
     *
     * Then `ls-files` over the ignored ones, because ignoring a directory doesn't untrack what was
     * committed there before, or force-added since: an `.idea/` with its shared run configurations
     * checked in is ignored and still has files whose edits `git status` reports.
     *
     * Any failure reads as nothing ignored, which costs rescans and is never wrong. `.git` is never
     * asked about: its events are how commits, checkouts and fetches are noticed at all.
     */
    fun ignoredDirs(repoPath: String): IgnoredDirs {
        val dir = File(repoPath)
        val names = dir.listFiles()?.filter { it.isDirectory && it.name != ".git" }?.map { it.name }?.toSet()
            ?: return IgnoredDirs.NONE
        if (names.isEmpty()) return IgnoredDirs(names, emptySet())
        // check-ignore exits 1 when nothing matched, which [Git.read] reads as "". It takes `-z` only
        // with `--stdin`, so a name git still quotes after quotePath (a quote, a newline) fails the
        // `in names` filter and stays watched — the safe way to be wrong.
        val ignored = Git.read(dir, "-c", "core.quotePath=false", "check-ignore", *names.toTypedArray())
            .lineSequence()
            .map { it.trimEnd('/') }
            .filter { it in names }
            .toSet()
        if (ignored.isEmpty()) return IgnoredDirs(names, ignored)
        val tracked = Git.readOrNull(dir, "ls-files", "-z", "--", *ignored.map { "$it/" }.toTypedArray())
            ?.split('\u0000')?.map { it.substringBefore('/') }?.toSet()
            ?: return IgnoredDirs(names, emptySet())
        return IgnoredDirs(names, ignored - tracked)
    }
}
