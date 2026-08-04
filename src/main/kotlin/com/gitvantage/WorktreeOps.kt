// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Git-worktree inspection for a repo: every working tree attached to the same repository —
 * the main checkout plus each linked worktree — with the branch it holds, whether it's
 * locked, prunable, or gone from disk, and how dirty it is.
 *
 * Three entry points, deliberately split by cost:
 *  - [list] is one `git worktree list --porcelain` call — just how many working trees there are
 *    and which one this repo is.
 *  - [listWithWork] adds a status + rev-list read inside each *other* worktree, and runs on every
 *    scan (see GitScan.kt): unlanded work in another checkout is invisible from this one, so the
 *    repo row can't warn about it without looking. Repos with no linked worktrees — nearly all of
 *    them — pay nothing, since there is nothing to look into.
 *  - [load] adds each tree's last commit and whether its branch has landed, and only runs when the
 *    detail panel is open — the same lazy shape as [SubmoduleOps].
 */
object WorktreeOps {

    data class Worktree(
        val path: String,
        val head: String,             // full sha the worktree has checked out ("" when bare)
        val branch: String?,          // short branch name; null when detached or bare
        val bare: Boolean,
        val locked: Boolean,
        val lockReason: String?,      // git's reason, when one was given with `worktree lock --reason`
        val prunable: Boolean,        // git considers the administrative entry stale
        val prunableReason: String?,
        val isMain: Boolean,          // the repository's main working tree (git lists it first)
        val isCurrent: Boolean = false,   // the repo being viewed *is* this worktree
        val missing: Boolean = false,     // the directory is gone from disk (the usual cause of prunable)
        val dirtyCount: Int = 0,          // uncommitted changes inside (filled in by [listWithWork])
        val unmerged: Int = 0,            // commits here that mainline hasn't got (ditto)
        val mainline: String? = null,     // the ref [unmerged] and [branchMerged] were measured against
        val branchMerged: Boolean = false,   // its branch is fully contained in mainline (only [load])
        val lastRelative: String = "",    // git's relative date for the worktree's HEAD commit (only [load])
    ) {
        val detached get() = branch == null && !bare
        val name: String get() = File(path).name

        /**
         * Made by a Claude Code session rather than by hand. Those land under the repo's
         * `.claude/worktrees/` on a `claude/…` branch, and either signal alone is enough: a tree
         * moved out of that folder still reads as one, as does a hand-added tree on a `claude/`
         * branch. Purely a label — nothing about how a worktree is listed, counted, or removed
         * turns on it. Their folder names are generated slugs, so without this the list can't say
         * which trees a session left behind and which the user made deliberately.
         */
        val agent: Boolean get() =
            path.replace('\\', '/').contains("/.claude/worktrees/") || branch?.startsWith("claude/") == true

        /**
         * Holds work that deleting this folder would destroy — uncommitted changes, or commits
         * mainline never got. The one worktree fact worth carrying up to the repo row: an
         * abandoned checkout only matters when something is still in it.
         */
        val unlanded: Boolean get() = dirtyCount > 0 || unmerged > 0
    }

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
        val trees = parse(git(File(repoPath), "worktree", "list", "--porcelain"))
        if (trees.isEmpty()) return trees
        val here = canonical(repoPath)
        return trees.map { it.copy(isCurrent = canonical(it.path) == here) }
    }

    /**
     * [list] plus, for the *other* working trees still on disk, the work in them this checkout
     * can't see: uncommitted changes, and commits mainline hasn't got.
     *
     * The current tree is skipped on purpose — GitScan already ran `git status` for it, and
     * re-deriving the same number here would double the cost of every scan. A repo with no linked
     * worktrees runs no extra commands at all, which is the common case.
     */
    fun listWithWork(repoPath: String): List<Worktree> = enrich(list(repoPath), repoPath, detail = false)

    /**
     * [listWithWork] over every tree including the current one, plus each tree's last commit and
     * whether the branch it holds has already landed on mainline. The extra reads are why this is
     * the detail panel's call and not the scan's.
     */
    suspend fun load(repoPath: String): List<Worktree> = withContext(Dispatchers.IO) {
        enrich(list(repoPath), repoPath, detail = true)
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
    private fun enrich(trees: List<Worktree>, repoPath: String, detail: Boolean): List<Worktree> {
        fun wanted(wt: Worktree) = !wt.bare && (detail || !wt.isCurrent)
        if (trees.none { wanted(it) }) return trees
        val mainline = mainlineRef(File(repoPath))
        return trees.map { wt ->
            if (!wanted(wt)) return@map wt
            val dir = File(wt.path)
            if (!dir.isDirectory) return@map wt.copy(missing = true)
            wt.copy(
                dirtyCount = git(dir, "status", "--porcelain").lineSequence().count { it.isNotBlank() },
                unmerged = mainline?.let { count(dir, "$it..HEAD") } ?: 0,
                mainline = mainline,
                lastRelative = if (detail) git(dir, "log", "-1", "--format=%cr").trim() else "",
                // A branch can only be "merged" against something, and never against itself: mainline
                // is trivially its own ancestor, and calling the main checkout's branch merged would
                // offer to delete it.
                branchMerged = detail && wt.branch != null && mainline != null &&
                    wt.branch != mainline.substringAfter('/') &&
                    gitExit(dir, "merge-base", "--is-ancestor", wt.branch, mainline) == 0,
            )
        }
    }

    /** The ref to measure "landed" against: a local main/master if there is one, else the remote's. */
    private fun mainlineRef(dir: File): String? =
        listOf("main", "master", "origin/main", "origin/master")
            .firstOrNull { git(dir, "rev-parse", "--verify", "-q", it).isNotBlank() }

    private fun count(dir: File, range: String): Int =
        git(dir, "rev-list", "--count", range).trim().toIntOrNull() ?: 0

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
    ): GitOps.Result = withContext(Dispatchers.IO) {
        val dir = File(runFrom)
        val args = buildList {
            add("worktree"); add("remove")
            if (force || locked) add("--force")
            if (locked) add("--force")
            add(path)
        }
        val (code, _, err) = GitLog.exec(dir.name, dir, args)
        if (code != 0) return@withContext GitOps.Result(runFrom, false, firstErr(err, "remove failed"))

        val name = File(path).name
        tidyAgentParent(path)
        if (alsoBranch == null) return@withContext GitOps.Result(runFrom, true, "Removed worktree $name")
        val (bCode, _, bErr) = GitLog.exec(dir.name, dir, listOf("branch", "-d", alsoBranch))
        GitOps.Result(
            runFrom, true,
            if (bCode == 0) "Removed worktree $name and branch $alsoBranch"
            else "Removed worktree $name — branch kept: ${firstErr(bErr, "delete failed")}",
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
    suspend fun prune(repoPath: String): GitOps.Result = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        val (code, _, err) = GitLog.exec(dir.name, dir, listOf("worktree", "prune", "-v"))
        if (code == 0) GitOps.Result(repoPath, true, "Pruned stale worktree entries")
        else GitOps.Result(repoPath, false, firstErr(err, "prune failed"))
    }

    private fun canonical(path: String): String = runCatching { File(path).canonicalPath }.getOrDefault(path)

    private val ANSI = Regex("\\u001B\\[[0-9;]*m")
    private fun firstErr(err: String, fallback: String) =
        err.lineSequence().map { ANSI.replace(it, "").trim() }.firstOrNull { it.isNotEmpty() } ?: fallback

    /** Exit code only, for git's yes/no questions (`merge-base --is-ancestor`). -1 if it never ran. */
    private fun gitExit(dir: File, vararg args: String): Int = try {
        val proc = ProcessBuilder(listOf("git", *args)).directory(dir)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        if (!proc.waitFor(20, TimeUnit.SECONDS)) { proc.destroyForcibly(); -1 } else proc.exitValue()
    } catch (e: Exception) { -1 }

    private fun git(dir: File, vararg args: String): String = try {
        val proc = ProcessBuilder(listOf("git", *args)).directory(dir)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(20, TimeUnit.SECONDS)) { proc.destroyForcibly(); "" }
        else if (proc.exitValue() != 0) "" else out
    } catch (e: Exception) { "" }
}
