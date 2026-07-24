// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Lists a repo's branches. For local branches we compute two independent relationships:
 *   - to the mainline (main/master): current / mainline / merged / stale / behind
 *   - to the branch's own tracking upstream: ahead / behind / diverged / in-sync / gone
 * Also lists remote branches (with last author) and switches/creates branches.
 */
object BranchOps {

    private const val SEP = ""

    data class Branch(
        val name: String,
        val isCurrent: Boolean,
        val isMainline: Boolean,
        val behind: Int,        // commits mainline is ahead of this branch
        val ahead: Int,         // commits on this branch not in mainline
        val merged: Boolean,    // fully merged into mainline
        val stale: Boolean,     // old and very behind mainline
        val lastRelative: String,
        val upstream: String?,        // tracking branch short name (e.g. "origin/main"), null if none
        val upstreamAhead: Int,       // local commits the upstream doesn't have
        val upstreamBehind: Int,      // upstream commits the local branch doesn't have
        val upstreamGone: Boolean,    // upstream was configured but no longer exists on the remote
    )

    data class RemoteBranch(
        val name: String,        // full short ref, e.g. "origin/feature/login"
        val shortName: String,   // without the remote prefix, e.g. "feature/login"
        val author: String,      // author of the branch tip
        val lastRelative: String,
        val hasLocal: Boolean,   // a local branch of the same short name already exists
        val merged: Boolean,     // fully merged into mainline
    )

    suspend fun load(repoPath: String): List<Branch> = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        if (!File(dir, ".git").exists()) return@withContext emptyList()

        // name, unix date, "*" for HEAD, relative date, upstream short, upstream track ("[ahead N, behind M]")
        val raw = git(dir, "for-each-ref", "--sort=-committerdate",
            "--format=%(refname:short)$SEP%(committerdate:unix)$SEP%(HEAD)$SEP%(committerdate:relative)$SEP%(upstream:short)$SEP%(upstream:track)",
            "refs/heads")
        val rows = raw.lineSequence().filter { it.isNotBlank() }.map { line ->
            val p = line.split(SEP)
            Row(
                name = p.getOrElse(0) { "" },
                epoch = p.getOrNull(1)?.toLongOrNull() ?: 0L,
                isCurrent = p.getOrNull(2)?.trim() == "*",
                relative = p.getOrElse(3) { "" },
                upstream = p.getOrNull(4)?.takeIf { it.isNotBlank() },
                track = p.getOrElse(5) { "" },
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
            val merged = !isMainline && gitExit(dir, "merge-base", "--is-ancestor", r.name, mainline) == 0
            val ageDays = (nowSecs - r.epoch) / 86_400
            val stale = !r.isCurrent && !isMainline && ageDays > Meta.STALE_DAYS && behind > Meta.VERY_BEHIND
            val (uAhead, uBehind, gone) = parseTrack(r.track)
            Branch(
                r.name, r.isCurrent, isMainline, behind, ahead, merged, stale, r.relative,
                upstream = r.upstream, upstreamAhead = uAhead, upstreamBehind = uBehind, upstreamGone = gone,
            )
        }
    }

    /** Remote branches (those under `refs/remotes`), newest first, each with its tip author. */
    suspend fun loadRemotes(repoPath: String, localNames: Set<String>): List<RemoteBranch> =
        withContext(Dispatchers.IO) {
            val dir = File(repoPath)
            if (!File(dir, ".git").exists()) return@withContext emptyList()
            // Mainline to test "merged" against: prefer local main/master, else the remote's.
            val mainline = listOf("main", "master", "origin/main", "origin/master")
                .firstOrNull { git(dir, "rev-parse", "--verify", "-q", it).isNotBlank() }
            val raw = git(dir, "for-each-ref", "--sort=-committerdate",
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
                    gitExit(dir, "merge-base", "--is-ancestor", name, mainline) == 0
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

    private val ANSI = Regex("\\u001B\\[[0-9;]*m")
    private fun firstErr(err: String, fallback: String) =
        err.lineSequence().map { ANSI.replace(it, "").trim() }.firstOrNull { it.isNotEmpty() } ?: fallback

    /** Check out an existing local branch. Fails (not throws) if the working tree would be clobbered. */
    suspend fun switch(repoPath: String, name: String): GitOps.Result = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        val (code, _, err) = GitLog.exec(dir.name, dir, listOf("switch", name))
        if (code == 0) GitOps.Result(repoPath, true, "Switched to $name")
        else GitOps.Result(repoPath, false, firstErr(err, "switch failed"))
    }

    /** Create a local branch tracking [remoteName] (e.g. "origin/feature/login") and switch to it.
     *  If a local branch of the same short name already exists, just switches to it. */
    suspend fun checkoutRemote(repoPath: String, remoteName: String, hasLocal: Boolean): GitOps.Result =
        withContext(Dispatchers.IO) {
            val dir = File(repoPath)
            val short = remoteName.substringAfter('/')
            val (code, _, err) =
                if (hasLocal) GitLog.exec(dir.name, dir, listOf("switch", short))
                else GitLog.exec(dir.name, dir, listOf("switch", "-c", short, "--track", remoteName))
            if (code == 0) GitOps.Result(repoPath, true, "Checked out $short")
            else GitOps.Result(repoPath, false, firstErr(err, "checkout failed"))
        }

    /** Delete a local branch. [force] uses -D (also deletes unmerged). Never the current branch. */
    suspend fun delete(repoPath: String, name: String, force: Boolean): GitOps.Result = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        val flag = if (force) "-D" else "-d"
        val (code, out, err) = GitLog.exec(dir.name, dir, listOf("branch", flag, name))
        if (code == 0) GitOps.Result(repoPath, true, "Deleted $name")
        else GitOps.Result(repoPath, false, firstErr(err, "delete failed"))
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
        val upstream: String?, val track: String,
    )

    private fun count(dir: File, range: String): Int =
        git(dir, "rev-list", "--count", range).trim().toIntOrNull() ?: 0

    private data class Run(val code: Int, val out: String, val err: String)

    private fun run(dir: File, vararg args: String): Run = try {
        val proc = ProcessBuilder(listOf("git", *args)).directory(dir).start()
        val out = proc.inputStream.bufferedReader().readText()
        val err = proc.errorStream.bufferedReader().readText()
        if (!proc.waitFor(20, TimeUnit.SECONDS)) { proc.destroyForcibly(); Run(-1, out, "timed out") }
        else Run(proc.exitValue(), out, err)
    } catch (e: Exception) { Run(-1, "", e.message ?: "failed") }

    private fun git(dir: File, vararg args: String): String = run(dir, *args).let { if (it.code == 0) it.out else "" }
    private fun gitExit(dir: File, vararg args: String): Int = run(dir, *args).code
}
