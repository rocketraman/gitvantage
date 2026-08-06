// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.app

import com.gitvantage.git.Git
import com.gitvantage.model.RegistryEntry
import com.gitvantage.model.Settings
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class RegistryFile(
    val repos: List<RegistryEntry> = emptyList(),
    val settings: Settings = Settings(),
)

object Registry {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    val file: File by lazy {
        val base = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
            ?: (System.getProperty("user.home") + "/.config")
        File(base, "gitvantage/registry.json")
    }

    // In-memory copy so repo edits and settings edits don't clobber each other on save.
    private var current = RegistryFile()
    private var loaded = false

    private fun read(): RegistryFile = runCatching {
        if (file.exists()) json.decodeFromString<RegistryFile>(file.readText()) else RegistryFile()
    }.getOrDefault(RegistryFile())

    private fun ensureLoaded() { if (!loaded) { current = read(); loaded = true } }

    private fun write() = runCatching {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(current))
    }

    fun load(): List<RegistryEntry> {
        current = read(); loaded = true
        return current.repos
    }

    fun save(entries: List<RegistryEntry>) {
        ensureLoaded()
        current = current.copy(repos = entries)
        write()
    }

    fun settings(): Settings { ensureLoaded(); return current.settings }

    fun saveSettings(s: Settings) {
        ensureLoaded()
        current = current.copy(settings = s)
        write()
    }

    /**
     * Discover git repos at or under [root] for the "Add repo" chooser. Any directory
     * containing a `.git` is a repo; we record it and DON'T descend into it — a `.git`
     * means "this is a repo," so we never walk its working tree or pick up its submodules
     * / vendored inner repos. Non-repo subdirectories are recursed into, up to [maxDepth]
     * levels below [root].
     *
     * Skips hidden directories (`.config`, `.cache`, …) and doesn't follow symlinks, so a
     * stray link can't send the walk off across the filesystem; [maxDepth] bounds it either
     * way. No result cap: this runs off the UI thread only when the user deliberately picks
     * a folder, and the chooser list scrolls — so we surface everything found.
     */
    fun discover(root: File, maxDepth: Int = 8): List<RegistryEntry> {
        if (!root.isDirectory) return emptyList()
        val out = mutableListOf<RegistryEntry>()
        fun walk(dir: File, depth: Int) {
            if (Git.isRepo(dir)) {          // a repo → record it, stop descending
                out += RegistryEntry(dir.absolutePath)
                return
            }
            if (depth >= maxDepth) return
            dir.listFiles()
                ?.filter { it.isDirectory && !it.isHidden && !Files.isSymbolicLink(it.toPath()) }
                ?.forEach { walk(it, depth + 1) }
        }
        walk(root, 0)
        return out.sortedBy { it.path.lowercase() }
    }

    /** Default discovery root: `$GITVANTAGE_ROOT`, else the folder containing the app's cwd. */
    fun defaultRoot(): File {
        System.getenv("GITVANTAGE_ROOT")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        val cwd = File(System.getProperty("user.dir"))
        return cwd.parentFile ?: cwd
    }
}
