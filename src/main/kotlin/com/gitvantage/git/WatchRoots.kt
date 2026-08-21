// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git

/** One directory to register with the filesystem watcher, and whether to descend into it. */
data class WatchRoot(val path: String, val recursive: Boolean)

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
 * So this stays a single watch until the watcher can either share one inotify instance across
 * registrations or take an exclusion list directly — see NucleusFramework/Nucleus#570. The
 * `check-ignore` query that worked out what to skip is in the history, and the tests with it.
 */
object WatchRoots {

    /**
     * The watch set for the repo at [repoPath]: one recursive registration, as it was before the
     * pruning experiment. See this file's header for why it is not several.
     */
    fun forRepo(repoPath: String): List<WatchRoot> = listOf(WatchRoot(repoPath, recursive = true))
}
