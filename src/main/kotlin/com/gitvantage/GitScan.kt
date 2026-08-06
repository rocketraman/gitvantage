// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Scans a registered repo into a [Repo] by shelling out to `git`. All process work
 * runs on [Dispatchers.IO] so the UI thread is never blocked (README § State
 * Management: "shell out to git … ideally off the UI thread").
 *
 * Git surface used: `git fetch` (optional), `symbolic-ref`, `rev-list` (ahead/behind),
 * `status --porcelain`, `stash list`, `log -1`.
 */
object RepoScanner {

    private const val SEP = "\u001F" // %x1f delimiter between log fields

    data class StatusResult(
        val files: List<ChangedFile>,
        val staged: Int,
        val unstaged: Int,
        val untracked: Int,
    )

    suspend fun scan(entry: RegistryEntry, fetch: Boolean): Repo = withContext(Dispatchers.IO) {
        val dir = File(entry.path)
        val name = dir.name.ifEmpty { entry.path }
        val id = entry.path
        val baseTags = entry.tags
        val note = entry.note.ifEmpty { null }
        val now = System.currentTimeMillis()
        val (snoozed, snoozedFor) = Meta.snoozeState(entry, now)
        val reminder = Meta.reminder(entry, now)

        if (!File(dir, ".git").exists()) {
            return@withContext Repo(
                id = id, name = name, branch = "—", tags = baseTags,
                isGitRepo = false, warning = "Not a git repo", note = note,
                snoozed = snoozed, snoozedFor = snoozedFor, reminder = reminder,
            )
        }

        if (fetch) GitLog.exec(name, dir, listOf("fetch")) // logged; best-effort, ignore offline/errors

        // Whether the repo has ANY remote configured. Gates Push/Fetch/Fast-forward:
        // a branch can be pushable (via `push -u origin`) even without a tracking upstream,
        // as long as a remote exists.
        val hasRemote = gitOrNull(dir, "remote")?.lineSequence()?.any { it.isNotBlank() } ?: false
        // Remote web base (for GitHub commit/issue/PR/actions links). Prefer origin.
        val remoteUrl = gitOrNull(dir, "remote", "get-url", "origin")?.trim()?.takeIf { it.isNotEmpty() }
        val (webBase, isGitHub) = Meta.webBase(remoteUrl)
        val hasWorkflows = File(dir, ".github/workflows").isDirectory

        // Branch (empty/null when detached)
        val symbolic = gitOrNull(dir, "symbolic-ref", "--short", "-q", "HEAD")?.trim().orEmpty()
        val detached = symbolic.isEmpty()
        val branch = if (detached) DETACHED_BRANCH else symbolic

        // Upstream + ahead/behind. Prefer a configured @{upstream}; else fall back to
        // origin/<branch> if it exists (a branch that was pushed but never `-u`-tracked).
        var upstream = gitOrNull(dir, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}")
            ?.trim()?.takeIf { it.isNotEmpty() && !it.endsWith("@{upstream}") }
        if (upstream == null && !detached) {
            val implied = "origin/$symbolic"
            if (gitOrNull(dir, "rev-parse", "--verify", "-q", implied) != null) upstream = implied
        }
        var ahead = 0
        var behind = 0
        if (upstream != null) {
            gitOrNull(dir, "rev-list", "--left-right", "--count", "HEAD...$upstream")?.trim()?.let { line ->
                val parts = line.split(Regex("\\s+"))
                ahead = parts.getOrNull(0)?.toIntOrNull() ?: 0
                behind = parts.getOrNull(1)?.toIntOrNull() ?: 0
            }
        }

        val warning = when {
            detached -> "Detached HEAD"
            upstream == null -> "No upstream"
            else -> null
        }

        val status = parseStatus(gitOrNull(dir, "status", "--porcelain").orEmpty())
        // "Dirty for how long": the registry records when the tree first went dirty; keep it only
        // while still dirty (AppState reconciles/persists the timestamp after each scan).
        val dirtyNow = status.staged + status.unstaged + status.untracked > 0
        val dirtySince = entry.dirtySinceEpoch?.takeIf { dirtyNow }
        val dirtyForLabel = dirtySince?.let { Meta.compactDuration(now - it) }
        // Newest working-tree change, for the "Recently Modified" filter. Uses the mtime of the
        // currently-changed files rather than "when it first went dirty" (dirtySince), so a repo
        // you're actively editing stays "recently modified" even if the tree went dirty days ago.
        // Deleted paths no longer exist on disk and simply drop out of the max.
        val modifiedAt = status.files.asSequence()
            .map { File(dir, it.path) }
            .filter { it.exists() }
            .map { it.lastModified() }
            .maxOrNull()
            ?.takeIf { it > 0 }

        val stashes = gitOrNull(dir, "stash", "list").orEmpty()
            .lineSequence().filter { it.isNotBlank() }
            .map { line ->
                // "stash@{0}: WIP on main: 1a2b3c message"
                val label = line.substringBefore(":").trim()
                val rest = line.substringAfter(":", "").trim()
                Stash(label.ifEmpty { "stash" }, rest.ifEmpty { line })
            }.toList()

        // Working trees sharing this repository. One cheap call when there are none but this one;
        // when there are others it also looks inside them, because work sitting in another
        // checkout is invisible from here and the row has no other way to learn of it. The rest of
        // the per-worktree detail (last commits, merged-ness) stays lazy — see WorktreeOps.load.
        val worktrees = WorktreeOps.listWithWork(id)
        val currentTree = worktrees.firstOrNull { it.isCurrent }
        val isWorktree = currentTree != null && !currentTree.isMain
        // A bare main repo isn't a working tree, so it doesn't count towards "how many checkouts".
        val worktreeCount = worktrees.count { !it.bare }
        val worktreesUnlanded = worktrees.count { !it.isCurrent && !it.bare && !it.missing && it.unlanded }
        val worktreeMain = if (isWorktree) worktrees.firstOrNull { it.isMain }?.path else null

        // A linked worktree's folder is named for the branch it holds, and a branch name alone says
        // nothing about which project it belongs to — "fix-login" sitting in the repo list is a
        // guess. Tracked worktrees are shown as "<main checkout>/<folder>", which both supplies the
        // missing context and sorts them next to the repo they were added from. A bare main repo
        // loses its ".git" suffix — "gitvantage.git/fix-login" reads like a path, not a name — and a
        // worktree folder that already matches its parent isn't doubled up.
        val displayName = worktreeMain
            ?.let { File(it).name.removeSuffix(".git") }
            ?.takeIf { it.isNotEmpty() && it != name }
            ?.let { "$it/$name" }
            ?: name

        val log = gitOrNull(dir, "log", "-1", "--format=%cr%x1f%an%x1f%ct")?.trim().orEmpty()
        val logParts = log.split(SEP)
        val lastRel = logParts.getOrNull(0)?.trim().orEmpty()
        val author = logParts.getOrNull(1)?.trim().orEmpty()
        val commitEpoch = logParts.getOrNull(2)?.trim()?.toLongOrNull()
        // Repo is "stale" when it hasn't been committed to in a long time. The threshold is the
        // per-repo override (staleThresholdDays) or the global default (Meta.STALE_DAYS).
        // Meta.STALE_NEVER opts the repo out entirely, however old its last commit is.
        val staleDays = entry.staleThresholdDays ?: Meta.STALE_DAYS.toInt()
        val isStale = staleDays != Meta.STALE_NEVER &&
            commitEpoch != null && (now / 1000 - commitEpoch) > staleDays.toLong() * 86_400

        Repo(
            id = id, name = displayName, branch = branch, tags = baseTags,
            ahead = ahead, behind = behind,
            staged = status.staged, unstaged = status.unstaged, untracked = status.untracked,
            stash = stashes.size,
            last = lastRel.ifEmpty { "—" }, lastCommitEpoch = commitEpoch, author = author.ifEmpty { "—" },
            dirtyFor = dirtyForLabel, dirtySince = dirtySince, modifiedAt = modifiedAt,
            upstream = upstream, hasRemote = hasRemote,
            webBase = webBase, isGitHub = isGitHub, hasWorkflows = hasWorkflows,
            hasSubmodules = File(dir, ".gitmodules").exists(),
            superproject = gitOrNull(dir, "rev-parse", "--show-superproject-working-tree")
                ?.trim()?.takeIf { it.isNotEmpty() },
            worktreeCount = worktreeCount, isWorktree = isWorktree,
            worktreeMain = worktreeMain,
            worktreesUnlanded = worktreesUnlanded,
            warning = warning, stale = isStale, staleDays = staleDays,
            staleImportant = entry.staleImportant,
            snoozed = snoozed, snoozedFor = snoozedFor, reminder = reminder, note = note,
            files = status.files, stashes = stashes,
        )
    }

    /** Parse `git status --porcelain` (v1). X = index (staged), Y = worktree (unstaged). */
    fun parseStatus(out: String): StatusResult {
        val files = mutableListOf<ChangedFile>()
        var staged = 0; var unstaged = 0; var untracked = 0
        out.lineSequence().forEach { raw ->
            if (raw.length < 3) return@forEach
            val x = raw[0]; val y = raw[1]
            var path = raw.substring(3)
            if (" -> " in path) path = path.substringAfter(" -> ")   // rename/copy
            path = path.trim('"')

            if (x == '?' && y == '?') {
                untracked++
                files += ChangedFile("?", "untracked", path)
                return@forEach
            }
            if (x != ' ' && x != '?') {   // staged (index) change
                staged++
                files += ChangedFile(x.toString(), "staged", path)
            }
            if (y != ' ' && y != '?') {   // unstaged (worktree) change
                unstaged++
                files += ChangedFile(y.toString(), "unstaged", path)
            }
        }
        return StatusResult(files, staged, unstaged, untracked)
    }

    /** Run `git <args>` in [dir]; return stdout, or null on failure/non-zero/timeout. */
    private fun gitOrNull(dir: File, vararg args: String): String? = try {
        val proc = ProcessBuilder(listOf("git", *args))
            .directory(dir)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(15, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            null
        } else if (proc.exitValue() != 0) {
            null
        } else {
            out
        }
    } catch (e: Exception) {
        null
    }
}
