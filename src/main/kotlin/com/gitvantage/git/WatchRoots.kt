// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git

import java.io.File

/** One directory to register with the filesystem watcher, and whether to descend into it. */
data class WatchRoot(val path: String, val recursive: Boolean)

/**
 * Which directories of a repo are worth watching — everything except the ones git is ignoring.
 *
 * Registering a repo used to be one recursive watch on its folder, and registering a recursive watch
 * means walking the tree: on Linux to add an inotify watch per directory, on macOS to build the
 * debouncer's file-id map, which also *retains* every path it finds. Either way the cost is the
 * number of files on disk, and for a working repo that number is mostly build output.
 *
 * Measured on this repo: 5,209 files in the working tree, 5,113 of them ignored, essentially all in
 * `build/`. Ninety-six files are worth watching and roughly six thousand get walked, per repo, at
 * startup — the tracked set is not the problem, the artefacts sitting beside it are.
 *
 * Neither the watcher nor the library underneath it takes an exclusion list, so the pruning has to
 * be expressed as *what to register* rather than what to skip: a shallow watch on the repo folder,
 * which catches files and new directories at the top, plus a recursive watch on each top-level
 * directory git isn't ignoring.
 *
 * Top level only. An ignored directory nested deeper — a `node_modules` under each package of a
 * monorepo — is still walked, and pruning those would mean descending the tree here to find them,
 * which is the walk this exists to avoid. The big offenders are top-level by convention (`build`,
 * `target`, `node_modules`, `dist`, `.venv`), and one shallow answer gets nearly all of it.
 */
object WatchRoots {

    /**
     * The watch set for the repo at [repoPath].
     *
     * `.git` is included deliberately, and is not something git reports as ignored: `HEAD`, `refs/`
     * and the index are how a commit, a checkout or a fetch becomes visible to the app at all, and a
     * repo whose `.git` went unwatched would sit on a stale branch name until the next poll.
     *
     * Falls back to the single recursive watch this replaces whenever the layout can't be read —
     * watching too much is slow, and watching too little is wrong.
     */
    fun forRepo(repoPath: String): List<WatchRoot> {
        val dir = File(repoPath)
        val children = dir.listFiles()?.filter { it.isDirectory } ?: return listOf(WatchRoot(repoPath, true))
        if (children.isEmpty()) return listOf(WatchRoot(repoPath, true))
        val ignored = ignoredNames(dir, children.map { it.name })
        return buildList {
            // Shallow: top-level files, and the arrival of a directory that will need its own watch.
            add(WatchRoot(repoPath, recursive = false))
            children.forEach { if (it.name !in ignored) add(WatchRoot(it.path, recursive = true)) }
        }
    }

    /** The immediate subdirectory names of [repoPath] — what [forRepo]'s answer is keyed on. */
    fun childDirNames(repoPath: String): Set<String> =
        File(repoPath).listFiles()?.filter { it.isDirectory }?.map { it.name }?.toSet().orEmpty()

    /**
     * Which of [names] git ignores, asked of git rather than worked out here.
     *
     * `.gitignore` is a bigger specification than it looks — negation, per-directory files,
     * directory-only patterns, `core.excludesFile`, `.git/info/exclude` — and a reimplementation
     * that got any of it wrong would silently stop watching a directory the user is working in.
     * `check-ignore` is the same answer git itself acts on.
     *
     * Names are passed as arguments rather than on stdin: [Git] does not write to a child's stdin,
     * and a `--stdin` that never receives anything would wait for the timeout. The list is a repo's
     * top-level directories, so it comfortably fits a command line.
     *
     * Any failure reads as "nothing is ignored". `check-ignore` exits non-zero both when no path
     * matched and when something went wrong, and the two are not worth telling apart here: both end
     * in watching more than strictly necessary, which is the direction that stays correct.
     */
    private fun ignoredNames(dir: File, names: List<String>): Set<String> =
        Git.read(dir, "check-ignore", *names.toTypedArray())
            .lineSequence()
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotEmpty() }
            .toSet()
}
