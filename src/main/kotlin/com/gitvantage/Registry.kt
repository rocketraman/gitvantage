// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.time.Instant

/** Serializes a [java.time.Instant] as an ISO-8601 string (e.g. "2026-07-25T02:24:00Z"),
 *  so timestamps in registry.json stay human-readable and hand-editable. */
object InstantIsoSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

/**
 * Persisted per-repo registry (README § State Management: "Tag edits and notes must
 * persist (registry file / local store)"). Stored as JSON at
 * `$XDG_CONFIG_HOME/gitvantage/registry.json` (falls back to `~/.config`).
 */
@Serializable
data class RegistryEntry(
    val path: String,
    val tags: List<String> = emptyList(),
    val note: String = "",
    val staleThresholdDays: Int? = null, // per-repo "stale after N days"; null = use the global default
    // Escalate staleness from the default informational (amber) signal to an important (red) one,
    // for repos where going quiet really does mean something is wrong.
    val staleImportant: Boolean = false,
    val snoozeUntilEpoch: Long? = null,
    val reminderText: String = "",
    val reminderDueEpoch: Long? = null,
    val dirtySinceEpoch: Long? = null,   // when the working tree first went dirty (for the "Aging" signal)
    val notifyUpstream: Boolean = false, // desktop-notify when this repo's upstream advances (opt-in)
    // When this repo was first tracked (for the "Recently Added" filter). ISO-8601 in JSON.
    @Serializable(with = InstantIsoSerializer::class)
    val addedAt: Instant? = null,
)

/** App-level UI preferences, persisted alongside the repo list. */
@Serializable
data class Settings(
    val windowWidth: Int = 1480,
    val windowHeight: Int = 860,
    val detailPaneWidth: Int = 466,
    val sortBy: String = "name",   // name | commit | attention
)

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
            if (File(dir, ".git").exists()) {          // a repo → record it, stop descending
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
