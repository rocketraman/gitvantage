// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git

import com.gitvantage.git.model.OpResult
import com.gitvantage.git.model.Submodule
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Git-submodule inspection and operations for a parent repo. Reads `.gitmodules` +
 * `git submodule status` to show, for each submodule: the upstream it points to, the
 * recorded pointer, how far behind that pointer is from the submodule's remote, and whether
 * the submodule's working tree is dirty. Every command is spawned by [Git]; the fetches and
 * pointer updates are the mutating ones, so those are the ones recorded in [GitLog].
 */
object SubmoduleOps {

    fun hasSubmodules(repoPath: String): Boolean = File(repoPath, ".gitmodules").exists()

    suspend fun load(repoPath: String): List<Submodule> = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        if (!File(dir, ".gitmodules").exists()) return@withContext emptyList()

        // Parse .gitmodules into name -> (path, url, branch).
        data class Cfg(var path: String? = null, var url: String? = null, var branch: String? = null)
        val byName = linkedMapOf<String, Cfg>()
        Git.read(dir, "config", "--file", ".gitmodules", "--list").lineSequence().forEach { line ->
            val m = Regex("^submodule\\.(.+)\\.(path|url|branch)=(.*)$").find(line) ?: return@forEach
            val (name, key, value) = m.destructured
            val c = byName.getOrPut(name) { Cfg() }
            when (key) { "path" -> c.path = value; "url" -> c.url = value; "branch" -> c.branch = value }
        }

        // git submodule status → status char per path.
        val statusByPath = hashMapOf<String, Char>()
        Git.read(dir, "submodule", "status").lineSequence().filter { it.isNotBlank() }.forEach { raw ->
            val ch = raw[0]
            val body = raw.substring(1).trim()
            val path = body.substringAfter(' ').substringBefore(" (").trim()
            if (path.isNotEmpty()) statusByPath[path] = ch
        }

        byName.values.mapNotNull { c ->
            val path = c.path ?: return@mapNotNull null
            val statusChar = statusByPath[path] ?: '-'
            val subDir = File(dir, path)
            val initialized = statusChar != '-' && Git.isRepo(subDir)
            val recordedFull = Git.read(dir, "rev-parse", "HEAD:$path").trim()
            var behind = 0
            var dirtyCount = 0
            var remoteRef: String? = null
            if (initialized) {
                dirtyCount = Git.read(subDir, "status", "--porcelain").lineSequence().count { it.isNotBlank() }
                remoteRef = c.branch?.let { "origin/$it" }
                    ?: Git.read(subDir, "symbolic-ref", "--short", "-q", "refs/remotes/origin/HEAD").trim().ifEmpty { null }
                if (remoteRef != null && recordedFull.isNotEmpty()) {
                    behind = Git.read(subDir, "rev-list", "--count", "$recordedFull..$remoteRef").trim().toIntOrNull() ?: 0
                }
            }
            // Note: `git submodule sync` (URL re-point) is intentionally not surfaced — .gitmodules
            // often uses relative URLs that resolve to the absolute remote, so a reliable "needs sync"
            // check would have to replicate git's URL resolution. Use the Terminal for that rare case.
            Submodule(path, c.url ?: "?", c.branch, statusChar, initialized, recordedFull.take(10), recordedFull, remoteRef, behind, dirtyCount)
        }
    }

    /** `git fetch` inside one submodule (logged) so its "behind" count refreshes. */
    suspend fun fetch(repoPath: String, path: String): OpResult = withContext(Dispatchers.IO) {
        val subDir = File(repoPath, path)
        if (!Git.isRepo(subDir)) return@withContext OpResult(repoPath, false, "$path not initialized")
        val res = Git.run(subDir, listOf("fetch"), Git.NETWORK_TIMEOUT, repoName = "${File(repoPath).name}/$path")
        if (res.ok) OpResult(repoPath, true, "Fetched $path") else OpResult(repoPath, false, res.firstError("fetch failed"))
    }

    /** Fetch every initialized submodule. */
    suspend fun fetchAll(repoPath: String, subs: List<Submodule>): OpResult = withContext(Dispatchers.IO) {
        val inited = subs.filter { it.initialized }
        if (inited.isEmpty()) return@withContext OpResult(repoPath, false, "No initialized submodules")
        var ok = 0
        inited.forEach { if (fetch(repoPath, it.path).ok) ok++ }
        OpResult(repoPath, true, "Fetched $ok/${inited.size} submodules")
    }

    /** Initialize + check out a submodule that isn't yet present. */
    suspend fun init(repoPath: String, path: String): OpResult = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        val res = Git.run(dir, listOf("submodule", "update", "--init", path), Git.NETWORK_TIMEOUT)
        if (res.ok) OpResult(repoPath, true, "Initialized $path") else OpResult(repoPath, false, res.firstError("init failed"))
    }

    /** `git submodule sync <path>` — re-point the submodule's remote at the URL in `.gitmodules`
     *  (use after the URL there changes). */
    suspend fun sync(repoPath: String, path: String): OpResult = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        val res = Git.run(dir, listOf("submodule", "sync", "--", path))
        if (res.ok) OpResult(repoPath, true, "Synced $path URL") else OpResult(repoPath, false, res.firstError("sync failed"))
    }

    /** `git submodule deinit -f <path>` — remove the submodule's working tree (it can be re-inited). */
    suspend fun deinit(repoPath: String, path: String): OpResult = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        val res = Git.run(dir, listOf("submodule", "deinit", "-f", "--", path))
        if (res.ok) OpResult(repoPath, true, "Deinitialized $path") else OpResult(repoPath, false, res.firstError("deinit failed"))
    }

    /** Advance a submodule to the latest commit on its remote branch and stage the new pointer
     *  in the parent — the user then commits the parent to record it. */
    suspend fun updatePointer(repoPath: String, path: String): OpResult = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        val name = dir.name
        val res = Git.run(dir, listOf("submodule", "update", "--remote", path), Git.NETWORK_TIMEOUT, repoName = "$name/$path")
        if (!res.ok) return@withContext OpResult(repoPath, false, res.firstError("update failed"))
        // Still `ok` if staging fails: the submodule *did* advance and no retry undoes that. But the
        // message must not go on telling the user to commit the parent, which would record nothing.
        val add = Git.run(dir, listOf("add", path))   // stage the new gitlink for committing
        OpResult(
            repoPath, true,
            if (add.ok) "Advanced $path — commit the parent to record it"
            else "Advanced $path — not staged: ${add.firstError("git add failed")}",
        )
    }
}
