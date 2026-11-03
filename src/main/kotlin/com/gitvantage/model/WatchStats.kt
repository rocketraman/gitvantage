// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.model

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Which repos the filesystem watcher keeps rescanning, and which of their folders the events come
 * from.
 *
 * The totals say how busy the watcher is: one field report had 3.4 events and half a rescan a
 * second. They can't say whether that is one repo with a build running or forty trickling, nor
 * whether the writes are in `build/` — which git ignores, so a rescan finds nothing new — or in the
 * code. The second question decides whether to stop watching ignored folders, and only the folders
 * can answer it.
 *
 * Folders are reported by name only when the name is a generic one — build output, tool state,
 * git's own files. Diagnostics reports get pasted into public issues, and a project's own folder
 * names are its business, so anything else is "(other folder)". Repos are labelled by the caller for
 * the same reason.
 *
 * Recorded only while performance collection is on. Concurrent because the watcher's collector
 * writes while the periodic dump reads.
 */
object WatchStats {

    private class RepoStats {
        val events = AtomicLong()
        val rescans = AtomicLong()
        val folders = ConcurrentHashMap<String, AtomicLong>()
    }

    private val byRepo = ConcurrentHashMap<String, RepoStats>()

    /** Folder names common enough to say nothing about the project they're in. */
    private val GENERIC = setOf(
        "build", "out", "dist", "target", "bin", "obj", "node_modules", "vendor", "coverage", "tmp", "logs",
        "__pycache__", ".gradle", ".idea", ".vscode", ".claude", ".venv", "venv", ".next", ".nuxt", ".cache",
        ".kotlin", ".turbo", ".pytest_cache", ".mypy_cache", ".terraform",
    )

    /**
     * Where [path] sits in the repo rooted at [root], as a report label: `.git/<entry>` for git's own
     * files, the folder's name when [GENERIC], `(root files)` for a file at the top, and
     * `(other folder)` otherwise. `(outside)` for a path not under [root] at all.
     */
    fun folderOf(root: Path, path: Path): String {
        if (!path.startsWith(root)) return "(outside)"
        val rel = root.relativize(path)
        if (rel.nameCount == 0 || rel.toString().isEmpty()) return "(root files)"
        val first = rel.getName(0).toString()
        return when {
            first == ".git" -> if (rel.nameCount > 1) ".git/${rel.getName(1)}" else ".git"
            rel.nameCount == 1 -> "(root files)"
            first in GENERIC -> "$first/"
            else -> "(other folder)"
        }
    }

    /** One filesystem event for [repo], in [folder] as [folderOf] named it. */
    fun event(repo: String, folder: String) {
        if (!Perf.enabled) return
        val s = byRepo.computeIfAbsent(repo) { RepoStats() }
        s.events.incrementAndGet()
        s.folders.computeIfAbsent(folder) { AtomicLong() }.incrementAndGet()
    }

    /** One rescan of [repo] that the watcher caused. */
    fun rescan(repo: String) {
        if (!Perf.enabled) return
        byRepo.computeIfAbsent(repo) { RepoStats() }.rescans.incrementAndGet()
    }

    /**
     * The busiest [top] repos by events, one line each, labelled by [label] — which is how the caller
     * keeps paths out of the report. Each lists its rescans and where its events came from, the
     * biggest share first.
     */
    fun report(top: Int = 5, label: (String) -> String): List<String> {
        val busiest = byRepo.entries.sortedByDescending { it.value.events.get() }.take(top)
        if (busiest.isEmpty()) return emptyList()
        return listOf("  busiest watched repos (events · rescans · where the events were)") +
            busiest.map { (repo, s) ->
                val events = s.events.get()
                val folders = s.folders.entries
                    .sortedByDescending { it.value.get() }
                    .take(5)
                    .joinToString(" · ") { (f, n) -> "$f ${n.get() * 100 / events.coerceAtLeast(1)}%" }
                "    %-26s events=%-7d rescans=%-5d %s".format(label(repo), events, s.rescans.get(), folders)
            }
    }

    fun reset() = byRepo.clear()
}
