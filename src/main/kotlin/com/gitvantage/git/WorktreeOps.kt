// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git

import com.gitvantage.git.model.OpResult
import com.gitvantage.model.Worktree
import com.gitvantage.model.WorktreeChange
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Git-worktree inspection for a repo: every working tree attached to the same repository —
 * the main checkout plus each linked worktree — with the branch it holds, whether it's
 * locked, prunable, or gone from disk, and how dirty it is.
 *
 * Three entry points, deliberately split by cost:
 *  - [list] is one `git worktree list --porcelain` call — just how many working trees there are
 *    and which one this repo is.
 *  - [listWithWork] looks *inside* each other worktree: four reads per tree (status, rev-list,
 *    last commit, merge-base), and runs on every scan (see GitScan.kt). Repos with no linked
 *    worktrees — nearly all of them — pay nothing, since there is nothing to look into.
 *  - [load] is the same over every tree including the current one, off the UI thread, for the
 *    detail panel.
 *
 * Everything is read on the scan path because the *list* views show it now, not only the panel:
 * a worktree is a sub-row under its repo, and that row's badges are "3 modified", "↑2 vs main",
 * "merged", its right-hand column is "2 hours ago · Ada Okafor", and whether its branch has landed
 * is what decides if "Remove + branch" is offered at all. A lazily-loaded field would mean a row
 * that silently omits a badge and hides an action until something else happened to open the pane.
 *
 * That makes a repo with linked worktrees about twice the scan it used to be. It buys the four
 * questions the row asks, it is bounded by the number of worktrees the repo actually has, and it is
 * still nothing at all for a repo without any.
 */
object WorktreeOps {

    /** `%x1f` delimiter between fields of the one-line log read, as GitScan uses for the same job. */
    private const val LOG_SEP = "\u001F"

    /**
     * Parse `git worktree list --porcelain`. Records are blank-line separated and start with a
     * `worktree <path>` line; the main working tree is always listed first.
     */
    fun parse(out: String): List<Worktree> {
        val result = mutableListOf<Worktree>()
        var path: String? = null
        var head = ""
        var branch: String? = null
        var bare = false
        var locked = false
        var lockReason: String? = null
        var prunable = false
        var prunableReason: String? = null

        fun flush() {
            val p = path ?: return
            result += Worktree(
                path = p, head = head, branch = branch, bare = bare,
                locked = locked, lockReason = lockReason,
                prunable = prunable, prunableReason = prunableReason,
                isMain = result.isEmpty(),
            )
            path = null; head = ""; branch = null; bare = false
            locked = false; lockReason = null; prunable = false; prunableReason = null
        }

        out.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            val key = line.substringBefore(' ')
            val value = line.substringAfter(' ', "").trim().ifEmpty { null }
            when {
                line.isEmpty() -> flush()
                key == "worktree" -> { flush(); path = value }
                key == "HEAD" -> head = value.orEmpty()
                // "branch refs/heads/x" for an attached worktree; a bare "detached" line otherwise.
                key == "branch" -> branch = value?.removePrefix("refs/heads/")
                key == "bare" -> bare = true
                key == "locked" -> { locked = true; lockReason = value }
                key == "prunable" -> { prunable = true; prunableReason = value }
            }
        }
        flush()
        return result
    }

    /**
     * Every working tree attached to the repo at [repoPath], with the one at [repoPath] marked
     * [Worktree.isCurrent]. Empty when the path isn't a repo, or on a git too old for the command.
     */
    fun list(repoPath: String): List<Worktree> {
        val trees = parse(Git.read(File(repoPath), "worktree", "list", "--porcelain"))
        if (trees.isEmpty()) return trees
        val here = canonical(repoPath)
        return trees.map { it.copy(isCurrent = canonical(it.path) == here) }
    }

    /**
     * [list] plus, for the *other* working trees still on disk, everything this checkout can't see
     * about them: uncommitted changes, commits mainline hasn't got, the last commit, and whether the
     * branch they hold has landed.
     *
     * The current tree is skipped on purpose — GitScan already ran `git status` for it, and
     * re-deriving the same numbers here would cost every scan a duplicate of work it just did. A repo
     * with no linked worktrees runs no extra commands at all, which is the common case.
     */
    fun listWithWork(repoPath: String): List<Worktree> = enrich(list(repoPath), repoPath, includeCurrent = false)

    /**
     * Whether the checkout at [dir] might have working trees other than itself — false only when the
     * filesystem rules it out, which it can do without spawning anything.
     *
     * git records every linked worktree as a directory under the main checkout's `.git/worktrees`.
     * So a `.git` that is a real directory with no such entries has exactly one working tree, and
     * `git worktree list` can only confirm what the stat already established. That is the common
     * case — nearly every repo — and it runs on every scan of every repo.
     *
     * A `.git` that is a *file* is not covered: it means this checkout is itself a linked worktree
     * or a submodule, and where the real gitdir sits, and what else hangs off it, is git's question
     * to answer. Absence of proof, so this answers true and the caller pays for the command.
     */
    fun mayHaveOtherWorktrees(dir: File): Boolean {
        val dotGit = File(dir, ".git")
        if (!dotGit.isDirectory) return true          // a gitfile — this checkout is linked to something
        val admin = File(dotGit, "worktrees").list() ?: return false
        return admin.isNotEmpty()
    }

    /** [listWithWork] over every tree including the current one, off the UI thread. */
    suspend fun load(repoPath: String): List<Worktree> = withContext(Dispatchers.IO) {
        enrich(list(repoPath), repoPath, includeCurrent = true)
    }

    /**
     * Fill in what `worktree list` can't know without entering each tree. Bare trees and ones whose
     * folder is gone are only marked [Worktree.missing] — there's nothing in them to read.
     *
     * Mainline is resolved once, from the repo the trees share: they have one object store and one
     * set of refs between them, so "how far from main is this" means the same thing everywhere.
     * When neither main nor master exists the counts stay 0 rather than guessing at a baseline —
     * a wrong "3 commits unlanded" is worse than none.
     */
    private fun enrich(trees: List<Worktree>, repoPath: String, includeCurrent: Boolean): List<Worktree> {
        fun wanted(wt: Worktree) = !wt.bare && (includeCurrent || !wt.isCurrent)
        if (trees.none { wanted(it) }) return trees
        val mainline = mainlineRef(File(repoPath))
        return trees.map { wt ->
            if (!wanted(wt)) return@map wt
            val dir = File(wt.path)
            if (!dir.isDirectory) return@map wt.copy(missing = true)
            val log = Git.read(dir, "log", "-1", "--format=%cr%x1f%an%x1f%ct").trim().split(LOG_SEP)
            wt.copy(
                dirtyCount = Git.read(dir, "status", "--porcelain").lineSequence().count { it.isNotBlank() },
                unmerged = mainline?.let { count(dir, "$it..HEAD") } ?: 0,
                mainline = mainline,
                lastRelative = log.getOrNull(0)?.trim().orEmpty(),
                lastAuthor = log.getOrNull(1)?.trim().orEmpty(),
                lastEpoch = log.getOrNull(2)?.trim()?.toLongOrNull(),
                // A branch can only be "merged" against something, and never against itself: mainline
                // is trivially its own ancestor, and calling the main checkout's branch merged would
                // offer to delete it.
                branchMerged = wt.branch != null && mainline != null &&
                    wt.branch != mainline.substringAfter('/') &&
                    Git.exitCode(dir, "merge-base", "--is-ancestor", wt.branch, mainline) == 0,
            )
        }
    }

    /** The ref to measure "landed" against: a local main/master if there is one, else the remote's. */
    private fun mainlineRef(dir: File): String? =
        listOf("main", "master", "origin/main", "origin/master")
            .firstOrNull { Git.read(dir, "rev-parse", "--verify", "-q", it).isNotBlank() }

    private fun count(dir: File, range: String): Int =
        Git.read(dir, "rev-list", "--count", range).trim().toIntOrNull() ?: 0

    /**
     * What's uncommitted inside the worktree at [path], with a per-file diffstat — the inline
     * "Changes" list on a worktree card.
     *
     * Two reads rather than one, because neither command answers the whole question. `status
     * --porcelain` is the only one that lists untracked files, and `diff --numstat HEAD` is the only
     * one that counts lines; the file list comes from the first so an untracked file still gets a
     * row, and the counts are joined on from the second, which is why they come back 0 for one.
     *
     * `--numstat` reports `-` for a binary file: a real answer ("this changed, and lines aren't the
     * unit"), not a parse failure, and 0/0 is how the row renders it.
     *
     * Empty when the path isn't a working tree, so a worktree that vanished between the list and
     * the click shows an empty body rather than failing.
     */
    suspend fun changes(path: String): List<WorktreeChange> = withContext(Dispatchers.IO) {
        val dir = File(path)
        if (!dir.isDirectory) return@withContext emptyList()
        val stat = Git.read(dir, "diff", "--numstat", "HEAD").lineSequence()
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 3) return@mapNotNull null
                parts[2] to (parts[0].toIntOrNull().orZero() to parts[1].toIntOrNull().orZero())
            }.toMap()
        Git.read(dir, "status", "--porcelain").lineSequence()
            .filter { it.length > 3 }
            .map { line ->
                // "XY <path>"; a rename reads "R  old -> new", and the new name is the one on disk.
                val file = line.substring(3).trim().substringAfterLast(" -> ")
                val counts = stat[file]
                WorktreeChange(
                    path = file,
                    untracked = line.startsWith("??"),
                    added = counts?.first ?: 0,
                    deleted = counts?.second ?: 0,
                )
            }
            .toList()
    }

    private fun Int?.orZero() = this ?: 0

    /**
     * `git worktree remove` — deletes the worktree's folder and its files. The branch it held is
     * untouched. Destructive: callers confirm first.
     *
     * [runFrom] must be a *different* working tree (or the bare repo) — git removes the directory,
     * and running from inside the one being deleted pulls the ground out from under the process.
     * git also refuses to remove the main working tree at all.
     *
     * Force flags mirror git's own rules: one `--force` for a tree with uncommitted changes, two
     * for a locked one. They're passed from the state the user was shown and agreed to, so a
     * confirmed removal doesn't then fail on a technicality and ask again.
     *
     * [alsoBranch] additionally deletes the branch the worktree held, with a plain `branch -d` —
     * never `-D`. git refuses an unmerged branch, and that refusal is the safety net: the caller
     * only offers this for a branch it has already seen land, and if that changed underneath us
     * the branch survives and the message says so. Removal itself still counts as success — the
     * folder is gone either way, and reporting failure would invite a retry that can't help.
     */
    suspend fun remove(
        runFrom: String,
        path: String,
        force: Boolean,
        locked: Boolean,
        alsoBranch: String? = null,
    ): OpResult = withContext(Dispatchers.IO) {
        val dir = File(runFrom)
        val args = buildList {
            add("worktree"); add("remove")
            if (force || locked) add("--force")
            if (locked) add("--force")
            add(path)
        }
        val res = Git.run(dir, args)
        if (!res.ok) return@withContext OpResult(runFrom, false, res.firstError("remove failed"))

        val name = File(path).name
        tidyAgentParent(path)
        if (alsoBranch == null) return@withContext OpResult(runFrom, true, "Removed worktree $name")
        val branchRes = Git.run(dir, listOf("branch", "-d", alsoBranch))
        OpResult(
            runFrom, true,
            if (branchRes.ok) "Removed worktree $name and branch $alsoBranch"
            else "Removed worktree $name — branch kept: ${branchRes.firstError("delete failed")}",
        )
    }

    /**
     * Drop the `.claude/worktrees/` folder a Claude Code session made, once its last worktree is
     * gone. `git worktree remove` only deletes the tree's own directory, so the empty parent is
     * left sitting in the repo forever — the residue you can see in repos that used a worktree
     * months ago. Only ever an empty directory, only ever the one git just emptied, and only when
     * it's the agent path: [File.delete] on a directory fails rather than recursing, so a
     * miscount here can't take anything with it.
     */
    private fun tidyAgentParent(path: String) {
        val parent = File(path).parentFile ?: return
        if (parent.name != "worktrees" || parent.parentFile?.name != ".claude") return
        if (parent.list()?.isEmpty() == true) runCatching { parent.delete() }
    }

    /** `git worktree prune` — drop the administrative entries for worktrees whose directory is gone. */
    suspend fun prune(repoPath: String): OpResult = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        val res = Git.run(dir, listOf("worktree", "prune", "-v"))
        if (res.ok) OpResult(repoPath, true, "Pruned stale worktree entries")
        else OpResult(repoPath, false, res.firstError("prune failed"))
    }

    private fun canonical(path: String): String = runCatching { File(path).canonicalPath }.getOrDefault(path)
}
