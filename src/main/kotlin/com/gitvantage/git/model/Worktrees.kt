// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git.model

import java.io.File

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
    val dirtyCount: Int = 0,          // uncommitted changes inside (filled in by listWithWork)
    val unmerged: Int = 0,            // commits here that mainline hasn't got (ditto)
    val mainline: String? = null,     // the ref [unmerged] and [branchMerged] were measured against
    val branchMerged: Boolean = false,   // its branch is fully contained in mainline (only load)
    val lastRelative: String = "",    // git's relative date for the worktree's HEAD commit (only load)
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
