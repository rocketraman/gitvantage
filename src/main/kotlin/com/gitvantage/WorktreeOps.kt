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
 * Two entry points, deliberately split by cost:
 *  - [list] is one `git worktree list --porcelain` call and runs on every scan (see GitScan.kt),
 *    just to know how many working trees there are and which one this repo is.
 *  - [load] adds a status + last-commit read *inside* each worktree, and only runs when the
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
        val dirtyCount: Int = 0,          // uncommitted changes inside (only filled in by [load])
        val lastRelative: String = "",    // git's relative date for the worktree's HEAD commit
    ) {
        val detached get() = branch == null && !bare
        val name: String get() = File(path).name
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

    /** [list] plus, for each worktree still on disk, its uncommitted-change count and last commit. */
    suspend fun load(repoPath: String): List<Worktree> = withContext(Dispatchers.IO) {
        list(repoPath).map { wt ->
            val dir = File(wt.path)
            if (wt.bare || !dir.isDirectory) return@map wt.copy(missing = !wt.bare && !dir.isDirectory)
            wt.copy(
                dirtyCount = git(dir, "status", "--porcelain").lineSequence().count { it.isNotBlank() },
                lastRelative = git(dir, "log", "-1", "--format=%cr").trim(),
            )
        }
    }

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
     */
    suspend fun remove(runFrom: String, path: String, force: Boolean, locked: Boolean): GitOps.Result =
        withContext(Dispatchers.IO) {
            val dir = File(runFrom)
            val args = buildList {
                add("worktree"); add("remove")
                if (force || locked) add("--force")
                if (locked) add("--force")
                add(path)
            }
            val (code, _, err) = GitLog.exec(dir.name, dir, args)
            if (code == 0) GitOps.Result(runFrom, true, "Removed worktree ${File(path).name}")
            else GitOps.Result(runFrom, false, firstErr(err, "remove failed"))
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

    private fun git(dir: File, vararg args: String): String = try {
        val proc = ProcessBuilder(listOf("git", *args)).directory(dir)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(20, TimeUnit.SECONDS)) { proc.destroyForcibly(); "" }
        else if (proc.exitValue() != 0) "" else out
    } catch (e: Exception) { "" }
}
