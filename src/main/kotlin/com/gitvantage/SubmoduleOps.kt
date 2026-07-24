// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Git-submodule inspection and operations for a parent repo. Reads `.gitmodules` +
 * `git submodule status` to show, for each submodule: the upstream it points to, the
 * recorded pointer, how far behind that pointer is from the submodule's remote, and whether
 * the submodule's working tree is dirty. Fetching and pointer updates run through [GitLog]
 * so they show in the console.
 */
object SubmoduleOps {

    data class Submodule(
        val path: String,
        val url: String,           // where the submodule points (from .gitmodules)
        val branch: String?,       // configured tracking branch, if any
        val statusChar: Char,      // git submodule status: ' ' ok, '+' differs, '-' uninit, 'U' conflict
        val initialized: Boolean,
        val recorded: String,      // short sha the parent records for this submodule
        val recordedFull: String,  // full sha (for range diffs)
        val remoteRef: String?,    // resolved remote-tracking ref used for "behind" / the diff target
        val behind: Int,           // commits the submodule's remote is ahead of the recorded pointer
        val dirtyCount: Int,       // uncommitted changes inside the submodule (0 = clean)
    ) {
        val dirty get() = dirtyCount > 0
    }

    fun hasSubmodules(repoPath: String): Boolean = File(repoPath, ".gitmodules").exists()

    suspend fun load(repoPath: String): List<Submodule> = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        if (!File(dir, ".gitmodules").exists()) return@withContext emptyList()

        // Parse .gitmodules into name -> (path, url, branch).
        data class Cfg(var path: String? = null, var url: String? = null, var branch: String? = null)
        val byName = linkedMapOf<String, Cfg>()
        git(dir, "config", "--file", ".gitmodules", "--list").lineSequence().forEach { line ->
            val m = Regex("^submodule\\.(.+)\\.(path|url|branch)=(.*)$").find(line) ?: return@forEach
            val (name, key, value) = m.destructured
            val c = byName.getOrPut(name) { Cfg() }
            when (key) { "path" -> c.path = value; "url" -> c.url = value; "branch" -> c.branch = value }
        }

        // git submodule status → status char per path.
        val statusByPath = hashMapOf<String, Char>()
        git(dir, "submodule", "status").lineSequence().filter { it.isNotBlank() }.forEach { raw ->
            val ch = raw[0]
            val body = raw.substring(1).trim()
            val path = body.substringAfter(' ').substringBefore(" (").trim()
            if (path.isNotEmpty()) statusByPath[path] = ch
        }

        byName.values.mapNotNull { c ->
            val path = c.path ?: return@mapNotNull null
            val statusChar = statusByPath[path] ?: '-'
            val subDir = File(dir, path)
            val initialized = statusChar != '-' && File(subDir, ".git").exists()
            val recordedFull = git(dir, "rev-parse", "HEAD:$path").trim()
            var behind = 0
            var dirtyCount = 0
            var remoteRef: String? = null
            if (initialized) {
                dirtyCount = git(subDir, "status", "--porcelain").lineSequence().count { it.isNotBlank() }
                remoteRef = c.branch?.let { "origin/$it" }
                    ?: git(subDir, "symbolic-ref", "--short", "-q", "refs/remotes/origin/HEAD").trim().ifEmpty { null }
                if (remoteRef != null && recordedFull.isNotEmpty()) {
                    behind = git(subDir, "rev-list", "--count", "$recordedFull..$remoteRef").trim().toIntOrNull() ?: 0
                }
            }
            // Note: `git submodule sync` (URL re-point) is intentionally not surfaced — .gitmodules
            // often uses relative URLs that resolve to the absolute remote, so a reliable "needs sync"
            // check would have to replicate git's URL resolution. Use the Terminal for that rare case.
            Submodule(path, c.url ?: "?", c.branch, statusChar, initialized, recordedFull.take(10), recordedFull, remoteRef, behind, dirtyCount)
        }
    }

    /** `git fetch` inside one submodule (logged) so its "behind" count refreshes. */
    suspend fun fetch(repoPath: String, path: String): GitOps.Result = withContext(Dispatchers.IO) {
        val subDir = File(repoPath, path)
        if (!File(subDir, ".git").exists()) return@withContext GitOps.Result(repoPath, false, "$path not initialized")
        val (code, _, err) = GitLog.exec("${File(repoPath).name}/$path", subDir, listOf("fetch"))
        if (code == 0) GitOps.Result(repoPath, true, "Fetched $path") else GitOps.Result(repoPath, false, firstErr(err, "fetch failed"))
    }

    /** Fetch every initialized submodule. */
    suspend fun fetchAll(repoPath: String, subs: List<Submodule>): GitOps.Result = withContext(Dispatchers.IO) {
        val inited = subs.filter { it.initialized }
        if (inited.isEmpty()) return@withContext GitOps.Result(repoPath, false, "No initialized submodules")
        var ok = 0
        inited.forEach { if (fetch(repoPath, it.path).ok) ok++ }
        GitOps.Result(repoPath, true, "Fetched $ok/${inited.size} submodules")
    }

    /** Initialize + check out a submodule that isn't yet present. */
    suspend fun init(repoPath: String, path: String): GitOps.Result = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        val (code, _, err) = GitLog.exec(dir.name, dir, listOf("submodule", "update", "--init", path))
        if (code == 0) GitOps.Result(repoPath, true, "Initialized $path") else GitOps.Result(repoPath, false, firstErr(err, "init failed"))
    }

    /** `git submodule sync <path>` — re-point the submodule's remote at the URL in `.gitmodules`
     *  (use after the URL there changes). */
    suspend fun sync(repoPath: String, path: String): GitOps.Result = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        val (code, _, err) = GitLog.exec(dir.name, dir, listOf("submodule", "sync", "--", path))
        if (code == 0) GitOps.Result(repoPath, true, "Synced $path URL") else GitOps.Result(repoPath, false, firstErr(err, "sync failed"))
    }

    /** `git submodule deinit -f <path>` — remove the submodule's working tree (it can be re-inited). */
    suspend fun deinit(repoPath: String, path: String): GitOps.Result = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        val (code, _, err) = GitLog.exec(dir.name, dir, listOf("submodule", "deinit", "-f", "--", path))
        if (code == 0) GitOps.Result(repoPath, true, "Deinitialized $path") else GitOps.Result(repoPath, false, firstErr(err, "deinit failed"))
    }

    /** Advance a submodule to the latest commit on its remote branch and stage the new pointer
     *  in the parent — the user then commits the parent to record it. */
    suspend fun updatePointer(repoPath: String, path: String): GitOps.Result = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        val name = dir.name
        val (code, _, err) = GitLog.exec("$name/$path", dir, listOf("submodule", "update", "--remote", path))
        if (code != 0) return@withContext GitOps.Result(repoPath, false, firstErr(err, "update failed"))
        GitLog.exec(name, dir, listOf("add", path))   // stage the new gitlink for committing
        GitOps.Result(repoPath, true, "Advanced $path — commit the parent to record it")
    }

    private val ANSI = Regex("\\u001B\\[[0-9;]*m")
    private fun firstErr(err: String, fallback: String) =
        err.lineSequence().map { ANSI.replace(it, "").trim() }.firstOrNull { it.isNotEmpty() } ?: fallback

    private fun git(dir: File, vararg args: String): String = try {
        val proc = ProcessBuilder(listOf("git", *args)).directory(dir)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(20, TimeUnit.SECONDS)) { proc.destroyForcibly(); "" }
        else if (proc.exitValue() != 0) "" else out
    } catch (e: Exception) { "" }
}
