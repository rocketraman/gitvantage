// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git

import com.gitvantage.git.model.Branch
import com.gitvantage.git.model.OpResult
import com.gitvantage.git.model.RemoteBranch
import com.gitvantage.model.Meta
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lists a repo's branches. For local branches we compute two independent relationships:
 *   - to the mainline (main/master): current / mainline / merged / stale / behind
 *   - to the branch's own tracking upstream: ahead / behind / diverged / in-sync / gone
 * Also lists remote branches (with last author) and switches/creates branches.
 */
object BranchOps {

    private const val SEP = ""

    suspend fun load(repoPath: String): List<Branch> = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        if (!Git.isRepo(dir)) return@withContext emptyList()

        // name, unix date, "*" for HEAD, relative date, upstream short, upstream track
        // ("[ahead N, behind M]"), and the working tree holding the branch (empty unless one does).
        // %(worktreepath) needs git 2.23 — older git fails the whole command and the branch list
        // comes back empty, which is the same way this file already treats a git too old to ask.
        val raw = Git.read(dir, "for-each-ref", "--sort=-committerdate",
            "--format=%(refname:short)$SEP%(committerdate:unix)$SEP%(HEAD)$SEP%(committerdate:relative)$SEP%(upstream:short)$SEP%(upstream:track)$SEP%(worktreepath)",
            "refs/heads")
        val here = runCatching { dir.canonicalPath }.getOrDefault(dir.path)
        val rows = raw.lineSequence().filter { it.isNotBlank() }.map { line ->
            val p = line.split(SEP)
            Row(
                name = p.getOrElse(0) { "" },
                epoch = p.getOrNull(1)?.toLongOrNull() ?: 0L,
                isCurrent = p.getOrNull(2)?.trim() == "*",
                relative = p.getOrElse(3) { "" },
                upstream = p.getOrNull(4)?.takeIf { it.isNotBlank() },
                track = p.getOrElse(5) { "" },
                // git names the current tree here too; only *another* tree blocks a switch, so
                // this repo's own path is dropped rather than reported back at the user.
                worktree = p.getOrNull(6)?.trim()?.takeIf {
                    it.isNotEmpty() && runCatching { File(it).canonicalPath }.getOrDefault(it) != here
                },
            )
        }.filter { it.name.isNotEmpty() && !it.name.startsWith("gitbutler/") }.toList()   // ignore GitButler's virtual branches
        if (rows.isEmpty()) return@withContext emptyList()

        val names = rows.map { it.name }.toSet()
        val mainline = listOf("main", "master").firstOrNull { it in names }
            ?: rows.firstOrNull { it.isCurrent }?.name ?: rows.first().name
        val nowSecs = System.currentTimeMillis() / 1000

        rows.map { r ->
            val isMainline = r.name == mainline
            val behind = if (isMainline) 0 else count(dir, "${r.name}..$mainline")
            val ahead = if (isMainline) 0 else count(dir, "$mainline..${r.name}")
            // merged = every commit on the branch is already in mainline (ancestor), excluding mainline itself
            val merged = !isMainline && Git.exitCode(dir, "merge-base", "--is-ancestor", r.name, mainline) == 0
            val ageDays = (nowSecs - r.epoch) / 86_400
            val stale = !r.isCurrent && !isMainline && ageDays > Meta.STALE_DAYS && behind > Meta.VERY_BEHIND
            val (uAhead, uBehind, gone) = parseTrack(r.track)
            Branch(
                r.name, r.isCurrent, isMainline, behind, ahead, merged, stale, r.relative,
                upstream = r.upstream, upstreamAhead = uAhead, upstreamBehind = uBehind, upstreamGone = gone,
                worktreePath = r.worktree,
            )
        }
    }

    /** Remote branches (those under `refs/remotes`), newest first, each with its tip author. */
    suspend fun loadRemotes(repoPath: String, localNames: Set<String>): List<RemoteBranch> =
        withContext(Dispatchers.IO) {
            val dir = File(repoPath)
            if (!Git.isRepo(dir)) return@withContext emptyList()
            // Mainline to test "merged" against: prefer local main/master, else the remote's.
            val mainline = listOf("main", "master", "origin/main", "origin/master")
                .firstOrNull { Git.read(dir, "rev-parse", "--verify", "-q", it).isNotBlank() }
            val raw = Git.read(dir, "for-each-ref", "--sort=-committerdate",
                "--format=%(refname:short)$SEP%(committerdate:relative)$SEP%(authorname)", "refs/remotes")
            raw.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
                val p = line.split(SEP)
                val name = p.getOrElse(0) { "" }
                // Skip the remote HEAD symref. Git shortens `refs/remotes/origin/HEAD` to just the
                // remote name ("origin"), so a no-slash entry is the symref, not a real branch;
                // some git versions instead keep it as "origin/HEAD".
                if (name.isEmpty() || name.endsWith("/HEAD") || !name.contains('/')) return@mapNotNull null
                val short = name.substringAfter('/')   // drop the remote name prefix
                val merged = mainline != null && mainline.substringAfter('/') != short &&
                    Git.exitCode(dir, "merge-base", "--is-ancestor", name, mainline) == 0
                RemoteBranch(
                    name = name,
                    shortName = short,
                    author = p.getOrElse(2) { "" }.ifBlank { "—" },
                    lastRelative = p.getOrElse(1) { "" },
                    hasLocal = short in localNames,
                    merged = merged,
                )
            }.toList()
        }

    /** Check out an existing local branch. Fails (not throws) if the working tree would be clobbered. */
    suspend fun switch(repoPath: String, name: String): OpResult = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        val res = Git.run(dir, listOf("switch", name))
        if (res.ok) OpResult(repoPath, true, "Switched to $name")
        else OpResult(repoPath, false, res.firstError("switch failed"))
    }

    /** Create a local branch tracking [remoteName] (e.g. "origin/feature/login") and switch to it.
     *  If a local branch of the same short name already exists, just switches to it. */
    suspend fun checkoutRemote(repoPath: String, remoteName: String, hasLocal: Boolean): OpResult =
        withContext(Dispatchers.IO) {
            val dir = File(repoPath)
            val short = remoteName.substringAfter('/')
            val res =
                if (hasLocal) Git.run(dir, listOf("switch", short))
                else Git.run(dir, listOf("switch", "-c", short, "--track", remoteName))
            if (res.ok) OpResult(repoPath, true, "Checked out $short")
            else OpResult(repoPath, false, res.firstError("checkout failed"))
        }

    /**
     * Send a local branch to the remote. A null [upstream] means the branch isn't tracking
     * anything yet, so this publishes it — `push -u origin <name>` — the same operation the
     * repo-level Push does for the current branch, spelled out here for a branch that may not be
     * checked out. That's safe: pushing a ref reads it, so the working tree is never involved and
     * a branch held by another worktree pushes fine.
     *
     * With an upstream we push to exactly the ref the branch tracks, as an explicit refspec: a
     * branch may track a remote branch of a different name, and `push <remote> <name>` would
     * quietly create a second one beside it. Never `--force` — a diverged branch is refused by
     * git rather than overwritten, which is why the caller doesn't offer this on one.
     */
    suspend fun push(repoPath: String, name: String, upstream: String?): OpResult =
        withContext(Dispatchers.IO) {
            val dir = File(repoPath)
            val args =
                if (upstream == null) listOf("push", "-u", "origin", name)
                else listOf("push", upstream.substringBefore('/'), "$name:${upstream.substringAfter('/')}")
            val res = Git.run(dir, args, Git.NETWORK_TIMEOUT)
            if (res.ok) {
                OpResult(repoPath, true, if (upstream == null) "Published $name" else "Pushed $name")
            } else {
                OpResult(repoPath, false, res.firstError("push failed"))
            }
        }

    /** Delete a local branch. [force] uses -D (also deletes unmerged). Never the current branch. */
    suspend fun delete(repoPath: String, name: String, force: Boolean): OpResult = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        val flag = if (force) "-D" else "-d"
        val res = Git.run(dir, listOf("branch", flag, name))
        if (res.ok) OpResult(repoPath, true, "Deleted $name")
        else OpResult(repoPath, false, res.firstError("delete failed"))
    }

    /**
     * Delete a branch *on the remote* — `git push <remote> --delete refs/heads/<branch>`. Unlike
     * the local delete this reaches other people's clones, and git has no undo for it: the ref is
     * gone from the server, and only whoever still holds the commits can put it back.
     *
     * The full `refs/heads/` form rather than the bare name: `--delete foo` asks the remote to
     * resolve "foo", which is ambiguous when a tag of the same name is also there, and git refuses
     * rather than guessing. Spelling out the namespace deletes the branch and never the tag.
     *
     * There's no force flag to mirror — deleting a ref doesn't merge anything, so git accepts it
     * whether or not the branch landed. That's exactly why the caller confirms an unmerged one.
     * The push also prunes the local `refs/remotes/...` copy, so the list is right after a reload.
     */
    suspend fun deleteRemote(repoPath: String, rb: RemoteBranch): OpResult =
        withContext(Dispatchers.IO) {
            val dir = File(repoPath)
            val args = listOf("push", rb.remote, "--delete", "refs/heads/${rb.shortName}")
            val res = Git.run(dir, args, Git.NETWORK_TIMEOUT)
            if (res.ok) OpResult(repoPath, true, "Deleted ${rb.name}")
            else OpResult(repoPath, false, res.firstError("remote delete failed"))
        }

    /** Parse git's `%(upstream:track)` — "[ahead 2, behind 1]", "[gone]", or "". */
    private fun parseTrack(track: String): Triple<Int, Int, Boolean> {
        if ("gone" in track) return Triple(0, 0, true)
        val ahead = Regex("ahead (\\d+)").find(track)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val behind = Regex("behind (\\d+)").find(track)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return Triple(ahead, behind, false)
    }

    private data class Row(
        val name: String, val epoch: Long, val isCurrent: Boolean, val relative: String,
        val upstream: String?, val track: String, val worktree: String? = null,
    )

    private fun count(dir: File, range: String): Int =
        Git.read(dir, "rev-list", "--count", range).trim().toIntOrNull() ?: 0
}
