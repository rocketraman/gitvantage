// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git

import com.gitvantage.git.model.Branch
import com.gitvantage.git.model.StatusResult
import com.gitvantage.model.ChangedFile
import com.gitvantage.model.DETACHED_BRANCH
import com.gitvantage.model.Meta
import com.gitvantage.model.RegistryEntry
import com.gitvantage.model.Repo
import com.gitvantage.model.Stash
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scans a registered repo into a [Repo]. Every command goes through [Git]; all process work
 * runs on [Dispatchers.IO] so the UI thread is never blocked (README § State
 * Management: "shell out to git … ideally off the UI thread").
 *
 * Git surface used: `git fetch` (optional — the one mutating command here, so the only one
 * that can be logged), `symbolic-ref`, `rev-list` (ahead/behind), `status --porcelain`,
 * `stash list`, `log -1`. The reads run on every poll, which is why they stay out of [GitLog].
 */
object RepoScanner {

    private const val SEP = "\u001F" // %x1f delimiter between log fields

    /**
     * Scan one repo. [fetch] adds a `git fetch` first; [userInitiated] says whether that fetch
     * came from something the user did.
     *
     * Only the trigger can answer that, which is why it's a parameter rather than a property of
     * the command: the identical `git fetch` is worth a console entry when it came from the
     * Refresh button, and is pure noise when it came from the 5-minute timer — which fires per
     * repo, forever, and would otherwise evict every real entry from a 500-deep console within
     * a couple of idle hours.
     */
    suspend fun scan(entry: RegistryEntry, fetch: Boolean, userInitiated: Boolean = true): Repo = withContext(Dispatchers.IO) {
        val dir = File(entry.path)
        val name = dir.name.ifEmpty { entry.path }
        val id = entry.path
        val baseTags = entry.tags
        val note = entry.note.ifEmpty { null }
        val now = System.currentTimeMillis()
        val (snoozed, snoozedFor) = Meta.snoozeState(entry, now)
        val reminder = Meta.reminder(entry, now)

        if (!Git.isRepo(dir)) {
            return@withContext Repo(
                id = id, name = name, branch = "—", tags = baseTags,
                isGitRepo = false, warning = "Not a git repo", note = note,
                snoozed = snoozed, snoozedFor = snoozedFor, reminder = reminder,
            )
        }

        // Best-effort: ignore offline/errors. Logged only when the user asked for it (see [scan]).
        if (fetch) Git.run(dir, listOf("fetch"), Git.NETWORK_TIMEOUT, log = userInitiated, repoName = name)

        // Whether the repo has ANY remote configured. Gates Push/Fetch/Fast-forward:
        // a branch can be pushable (via `push -u origin`) even without a tracking upstream,
        // as long as a remote exists.
        val hasRemote = Git.readOrNull(dir, "remote")?.lineSequence()?.any { it.isNotBlank() } ?: false
        // Remote web base (for GitHub commit/issue/PR/actions links). Prefer origin.
        val remoteUrl = Git.readOrNull(dir, "remote", "get-url", "origin")?.trim()?.takeIf { it.isNotEmpty() }
        val (webBase, isGitHub) = Meta.webBase(remoteUrl)
        val hasWorkflows = File(dir, ".github/workflows").isDirectory

        // Branch (empty/null when detached)
        val symbolic = Git.readOrNull(dir, "symbolic-ref", "--short", "-q", "HEAD")?.trim().orEmpty()
        val detached = symbolic.isEmpty()
        val branch = if (detached) DETACHED_BRANCH else symbolic

        // Upstream + ahead/behind. Prefer a configured @{upstream}; else fall back to
        // origin/<branch> if it exists (a branch that was pushed but never `-u`-tracked).
        var upstream = Git.readOrNull(dir, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}")
            ?.trim()?.takeIf { it.isNotEmpty() && !it.endsWith("@{upstream}") }
        if (upstream == null && !detached) {
            val implied = "origin/$symbolic"
            if (Git.readOrNull(dir, "rev-parse", "--verify", "-q", implied) != null) upstream = implied
        }
        var ahead = 0
        var behind = 0
        if (upstream != null) {
            Git.readOrNull(dir, "rev-list", "--left-right", "--count", "HEAD...$upstream")?.trim()?.let { line ->
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

        val status = parseStatus(Git.readOrNull(dir, "status", "--porcelain").orEmpty())
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

        val stashes = Git.readOrNull(dir, "stash", "list").orEmpty()
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
        val worktreeMain = if (isWorktree) worktrees.firstOrNull { it.isMain }?.path else null
        // The other working trees that sit *inside* this one's folder. Only these are ambiguous to
        // the filesystem watcher: a worktree elsewhere on disk is outside the watch root and can't
        // be mistaken for this repo changing, while one under `.claude/worktrees/` is gitignored —
        // invisible to every git command here — yet fully visible to a recursive watch.
        // Stored canonical, because that is the spelling they get compared against: the watcher is
        // handed each repo's real path and echoes event paths back under it, so a worktree reached
        // through a symlink would otherwise never match and its churn would count as this repo's.
        val nestedWorktrees = worktrees
            .filter { !it.isCurrent && !it.bare }
            .filter { isUnder(dir, it.path) }
            .map { runCatching { File(it.path).canonicalPath }.getOrDefault(it.path) }

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

        val log = Git.readOrNull(dir, "log", "-1", "--format=%cr%x1f%an%x1f%ct")?.trim().orEmpty()
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
            superproject = Git.readOrNull(dir, "rev-parse", "--show-superproject-working-tree")
                ?.trim()?.takeIf { it.isNotEmpty() },
            isWorktree = isWorktree,
            worktreeMain = worktreeMain,
            // The list the row's sub-rows, the card's strip and the pane's cards all render. This
            // checkout is left out on purpose: it is the row they hang under, and a tree that
            // appeared as its own sub-row is exactly the duplication this redesign removes. A bare
            // main repo goes too — there are no files in it to report on.
            worktrees = worktrees.filter { !it.isCurrent && !it.bare },
            nestedWorktrees = nestedWorktrees,
            warning = warning, stale = isStale, staleDays = staleDays,
            staleImportant = entry.staleImportant,
            snoozed = snoozed, snoozedFor = snoozedFor, reminder = reminder, note = note,
            files = status.files, stashes = stashes,
        )
    }

    /**
     * Whether [path] names something inside [root] (and not [root] itself).
     *
     * Both sides are canonicalized first: `git worktree list` reports real paths, while a repo is
     * registered under whatever path the user picked, and on a machine where either crosses a
     * symlink — /tmp and /var on macOS, any checkout the user symlinked — the two spellings of the
     * same directory would otherwise fail to match.
     */
    fun isUnder(root: File, path: String): Boolean {
        fun canon(f: File) = runCatching { f.canonicalFile }.getOrDefault(f.absoluteFile)
        val r = canon(root)
        val p = canon(File(path))
        return p != r && generateSequence(p.parentFile) { it.parentFile }.any { it == r }
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
}
