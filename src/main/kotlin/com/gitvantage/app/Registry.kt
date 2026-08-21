// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.app

import com.gitvantage.git.Git
import com.gitvantage.model.RegistryEntry
import com.gitvantage.model.Settings
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

    /**
     * Guards [current], [loaded] and the write itself.
     *
     * Everything here used to run on the UI thread, which serialized it for free. Repo writes now
     * happen on a background thread (see `AppState.persist`) while settings writes still come from
     * the UI, so the two can genuinely overlap — and both read-modify-write [current] and then race
     * for the same fixed-name temp file. Two writers interleaving there is how the file this whole
     * class is careful about ends up half-written after all.
     */
    private val lock = Any()

    /**
     * What [read] did about a registry file that was there but couldn't be understood, phrased for
     * the user; null after a clean load, including a first run with no file at all. [AppState]
     * reads it once at startup and says so — a load that quietly comes back empty is exactly how
     * someone loses every repo they track without being told.
     */
    var loadFailure: String? = null
        private set

    /** Set when an unreadable registry couldn't even be moved aside; blocks [write]. */
    private var unreadable = false

    /**
     * Load the registry, refusing to let an unreadable one become an empty one.
     *
     * The dangerous case is a file that exists but doesn't parse. Answering "no repos, then" — as
     * this used to — is not a neutral answer: the app carries on, the next save writes that empty
     * document out, and a registry a human could probably have rescued by hand is gone. So the
     * broken file is moved aside first, under a name that is never already taken, and only then do
     * we start from empty. If it can't even be moved, nothing is overwritten at all: [unreadable]
     * blocks every subsequent write, and the user is told why.
     *
     * A missing file is not a failure — that's a first run, and an empty registry is the right
     * answer. We deliberately never auto-discover repos to fill it (see [AppState]'s init).
     */
    private fun read(): RegistryFile {
        loadFailure = null
        unreadable = false
        if (!file.exists()) return RegistryFile()

        val parsed = runCatching { json.decodeFromString<RegistryFile>(file.readText()) }
        parsed.getOrNull()?.let { return it }

        val cause = parsed.exceptionOrNull()
        val reason = cause?.message?.lineSequence()?.firstOrNull()?.take(120)
            ?: cause?.let { it::class.simpleName } ?: "unreadable"
        val aside = quarantineTarget()
        // No REPLACE_EXISTING, and [quarantineTarget] hands back an unused name: between them,
        // rescuing this file can never cost us an earlier rescued copy.
        val moved = runCatching { Files.move(file.toPath(), aside.toPath()) }.isSuccess
        unreadable = !moved
        loadFailure =
            if (moved) "Couldn't read ${file.name} ($reason) — moved it to ${aside.name} and started empty"
            else "Couldn't read ${file.name} ($reason) — not saving until it's repaired or removed"
        return RegistryFile()
    }

    /** First unused `registry.json.corrupt[.N]`, so quarantining never overwrites an earlier one. */
    private fun quarantineTarget(): File {
        val dir = file.absoluteFile.parentFile
        val first = File(dir, "${file.name}.corrupt")
        if (!first.exists()) return first
        // Bounded: past this we hand back a name that's taken, the move fails, and [unreadable]
        // stops us writing — which beats silently cycling over someone's earlier copies.
        var n = 2
        while (n < 100 && File(dir, "${file.name}.corrupt.$n").exists()) n++
        return File(dir, "${file.name}.corrupt.$n")
    }

    private fun ensureLoaded() { if (!loaded) { current = read(); loaded = true } }

    /**
     * Write the registry out, replacing it atomically.
     *
     * Writing in place would truncate first, so a crash, a kill or a full disk mid-write leaves a
     * half-written file — and a half-written file does not fail loudly, because [read] can only
     * parse it as "no repos at all". Every tracked repo would silently vanish. So: write the whole
     * document to a sibling temp file, force it to the platter, then rename it over the target.
     * The rename is atomic, so a reader sees either the old registry or the new one, never a
     * partial one. The temp file is a sibling deliberately — it has to be on the same filesystem
     * for the rename to be a rename rather than a copy.
     *
     * The [Result] is returned rather than swallowed: this is the only point at which the user's
     * tags, notes and reminders reach disk, and a write that fails here loses them. Callers decide
     * what to say about it — see [AppState.persist], which turns a failure into a toast.
     */
    private fun write(): Result<Unit> = runCatching {
        // A registry we failed to read and failed to move aside is still sitting there. Whatever
        // is in it, it is more than the empty document [read] handed back, so it does not get
        // overwritten — the caller surfaces this and the user decides what to do about the file.
        check(!unreadable) { "refusing to overwrite ${file.name}: it exists but could not be read" }
        val dir = file.absoluteFile.parentFile
        dir.mkdirs()
        // A fixed name, not createTempFile: a process killed between writing and renaming leaves
        // one stray file that the next write reuses, rather than an accumulating pile of them.
        val tmp = File(dir, "${file.name}.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(json.encodeToString(current).toByteArray())
                // Without this the rename can land before the bytes do, and a crash in between
                // atomically replaces the registry with an empty file — the exact loss the
                // rename is here to prevent.
                out.fd.sync()
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            tmp.delete()
        }
    }

    /**
     * The tracked repos, from the same one-time read every other accessor goes through.
     *
     * Deliberately not a fresh read. [settings] is reached first — the window restores its saved
     * size before [AppState] is even built — so by the time this runs the file has usually already
     * been loaded, and re-reading it would report on a registry the earlier read has already dealt
     * with: the quarantined file is gone by then, so the second read succeeds and [loadFailure]
     * resets to null, losing the one chance to tell the user their registry didn't load.
     */
    fun load(): List<RegistryEntry> = synchronized(lock) {
        ensureLoaded()
        current.repos
    }

    fun settings(): Settings = synchronized(lock) { ensureLoaded(); current.settings }

    fun saveSettings(s: Settings): Result<Unit> = synchronized(lock) {
        ensureLoaded()
        current = current.copy(settings = s)
        write()
    }

    // ---- deferred writes ---------------------------------------------------------------------
    //
    // Recording and writing are separate so a caller can update the document now and pay for the
    // disk later. `write` ends in an fsync, which is unbounded on a busy or networked filesystem,
    // and the callers that matter here run on the UI thread: every scan that finds a tree has gone
    // dirty, and every frame of a window resize. See `AppState.persist`.
    //
    // The split is what makes deferring safe. Were a caller to defer by capturing a document and
    // writing it later, any change made in between would be captured from the stale copy and lost;
    // recording keeps [current] authoritative at all times, so a deferred write is only ever late,
    // never wrong.

    /** Record the repo list in memory. The disk write is the caller's to ask for, via [flush]. */
    fun recordRepos(entries: List<RegistryEntry>) = synchronized(lock) {
        ensureLoaded()
        current = current.copy(repos = entries)
    }

    /**
     * Apply [edit] to the settings in memory, leaving the disk write to [flush].
     *
     * Takes a function rather than a [Settings] on purpose: read-modify-write through
     * [settings] leaves a window in which another writer's change can be read, discarded and
     * written back over. Applying the edit under the lock closes it.
     */
    fun recordSettings(edit: (Settings) -> Settings) = synchronized(lock) {
        ensureLoaded()
        current = current.copy(settings = edit(current.settings))
    }

    /**
     * Write whatever has been recorded to disk.
     *
     * Idempotent, and safe to call concurrently with itself or any of the recording functions: it
     * writes [current] rather than anything it was handed, so two flushes racing write the same
     * document and neither can be the stale one.
     */
    fun flush(): Result<Unit> = synchronized(lock) { ensureLoaded(); write() }

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
