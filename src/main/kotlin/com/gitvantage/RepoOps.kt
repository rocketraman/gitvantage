// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Repo-level side-effecting git operations — the ones that act on the repository as a whole
 * rather than on a particular branch, worktree or submodule, which are [BranchOps],
 * [WorktreeOps] and [SubmoduleOps]. Read-only state comes from [RepoScanner]; every command
 * here is spawned by [Git] and recorded by [GitLog].
 *
 * Everything runs on [Dispatchers.IO]; callers re-scan the affected repos afterwards so
 * the dashboard reflects the new state (e.g. ahead → 0 after a push).
 */
object RepoOps {

    /** `git push` in the repo. If the current branch has no tracking upstream, publishes it
     *  with `push -u origin HEAD` (sets the upstream too). Fails (not throws) on rejection,
     *  auth, offline, or detached HEAD. */
    suspend fun push(entry: RegistryEntry): OpResult = withContext(Dispatchers.IO) {
        val dir = File(entry.path)
        if (!Git.isRepo(dir)) return@withContext OpResult(entry.path, false, "Not a git repo")
        val name = dir.name
        // Unlogged: this only asks a question, and the console is a record of changes.
        val hasUpstream = Git.run(
            dir, listOf("rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}"), log = false,
        ).ok
        val res =
            if (hasUpstream) Git.run(dir, listOf("push"), Git.NETWORK_TIMEOUT, repoName = name)
            else Git.run(dir, listOf("push", "-u", "origin", "HEAD"), Git.NETWORK_TIMEOUT, repoName = name)
        if (res.ok) {
            OpResult(entry.path, true, "$name pushed")
        } else {
            OpResult(entry.path, false, "$name: ${res.firstError("push failed")}")
        }
    }

    /** Stage (optionally) then commit with [title] (subject) + optional [body]. */
    suspend fun commit(entry: RegistryEntry, title: String, body: String, stageAll: Boolean): OpResult =
        withContext(Dispatchers.IO) {
            val dir = File(entry.path)
            val name = dir.name
            if (!Git.isRepo(dir)) return@withContext OpResult(entry.path, false, "$name: not a git repo")
            if (stageAll) {
                val add = Git.run(dir, listOf("add", "-A"), repoName = name)
                if (!add.ok) return@withContext OpResult(entry.path, false, "$name: ${add.firstError("git add failed")}")
            }
            // -m subject [-m body]: git inserts the blank line between them.
            val args = buildList {
                add("commit"); add("-m"); add(title.trim())
                if (body.isNotBlank()) { add("-m"); add(body.trim()) }
            }
            val res = Git.run(dir, args, repoName = name)
            if (res.ok) OpResult(entry.path, true, "$name committed")
            else OpResult(entry.path, false, "$name: ${res.firstError("commit failed")}")
        }

    /** `git merge --ff-only @{upstream}` — advances the branch to its upstream when possible. */
    suspend fun fastForward(entry: RegistryEntry): OpResult = withContext(Dispatchers.IO) {
        val dir = File(entry.path)
        val name = dir.name
        if (!Git.isRepo(dir)) return@withContext OpResult(entry.path, false, "$name: not a git repo")
        val res = Git.run(dir, listOf("merge", "--ff-only", "@{upstream}"), repoName = name)
        if (res.ok) OpResult(entry.path, true, "$name fast-forwarded")
        else OpResult(entry.path, false, "$name: ${res.firstError("fast-forward failed")}")
    }

    /** `git stash apply <ref>` — restores the stash's changes, keeping the stash. */
    suspend fun stashApply(entry: RegistryEntry, ref: String): OpResult = withContext(Dispatchers.IO) {
        val res = Git.run(File(entry.path), listOf("stash", "apply", ref))
        if (res.ok) OpResult(entry.path, true, "Applied $ref")
        else OpResult(entry.path, false, res.firstError("stash apply failed"))
    }

    /** `git stash drop <ref>` — deletes the stash. */
    suspend fun stashDrop(entry: RegistryEntry, ref: String): OpResult = withContext(Dispatchers.IO) {
        val res = Git.run(File(entry.path), listOf("stash", "drop", ref))
        if (res.ok) OpResult(entry.path, true, "Dropped $ref")
        else OpResult(entry.path, false, res.firstError("stash drop failed"))
    }
}
