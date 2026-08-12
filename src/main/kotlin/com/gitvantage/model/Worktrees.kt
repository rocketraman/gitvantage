// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.model

import java.io.File
import kotlinx.serialization.Serializable

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
    val lastRelative: String = "",    // git's relative date for the worktree's HEAD commit
    val lastAuthor: String = "",      // who made that commit
    // Epoch *seconds* of that commit, as git reports them. Carried alongside the relative string
    // rather than derived from it, so the card strip's "2h" is computed from a timestamp instead of
    // being parsed back out of git's prose.
    val lastEpoch: Long? = null,
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

    /**
     * How this worktree's state reads in one phrase, for the surfaces that have room for a verdict
     * and not for a row of badges (the card view's strip). Ordered by what would be lost: work
     * still in the tree first, then commits that haven't landed, then the fact that its branch has.
     */
    val verdict: String get() = when {
        missing -> "folder gone"
        dirtyCount > 0 -> "$dirtyCount uncommitted"
        unmerged > 0 && !branchMerged -> "↑$unmerged unlanded"
        branchMerged -> "merged"
        else -> "clean"
    }
}

/**
 * One changed file inside a worktree, with the diffstat the inline Changes list shows.
 *
 * Separate from [com.gitvantage.model.ChangedFile] because that one is a scan product for a
 * *tracked* repo and carries no line counts; a worktree is read on demand and the +N −N is the
 * reason the list is worth opening at all.
 */
data class WorktreeChange(
    val path: String,
    val untracked: Boolean,
    val added: Int = 0,
    val deleted: Int = 0,
)

/**
 * The alerts a worktree can answer for itself instead of inheriting from its parent checkout.
 *
 * Only these three, and deliberately not the rest of the repo's settings. Everything else a repo
 * carries — tags, staleness, issue tracking, hidden branches — is a property of the *repository*,
 * which every working tree shares one of; there is nothing per-tree to say about them. Alerts are
 * the exception because they're about the work sitting in a particular folder, and "stop telling me
 * about that abandoned checkout" is a sentence with no repo-level equivalent.
 */
enum class WorktreeAlert(val label: String) {
    AGING("Aging"),
    UNLANDED("Unlanded work"),
    REMINDERS("Reminders"),
}

/**
 * One worktree's alert overrides, plus its own snooze. Persisted on the *parent's* registry entry,
 * keyed by the worktree's path — a worktree has no registry row of its own any more.
 *
 * Every alert is a nullable tri-state: null means inherit (the default, and what nearly every
 * worktree stays on), true and false are answers of its own. Nullable rather than an enum with an
 * INHERIT case so an absent key and an explicit "inherit" are the same value — there is no way to
 * store a worktree that both inherits and doesn't.
 */
@Serializable
data class WorktreeAlerts(
    val aging: Boolean? = null,
    val unlanded: Boolean? = null,
    val reminders: Boolean? = null,
    val snoozeUntilEpoch: Long? = null,
) {
    operator fun get(alert: WorktreeAlert): Boolean? = when (alert) {
        WorktreeAlert.AGING -> aging
        WorktreeAlert.UNLANDED -> unlanded
        WorktreeAlert.REMINDERS -> reminders
    }

    fun with(alert: WorktreeAlert, value: Boolean?): WorktreeAlerts = when (alert) {
        WorktreeAlert.AGING -> copy(aging = value)
        WorktreeAlert.UNLANDED -> copy(unlanded = value)
        WorktreeAlert.REMINDERS -> copy(reminders = value)
    }

    /** The alerts this worktree answers for itself — what the ⚙ indicator's tooltip lists. */
    val overridden: List<WorktreeAlert> get() = WorktreeAlert.entries.filter { get(it) != null }

    /** True when nothing has been set here, so the entry can be dropped rather than stored empty. */
    val isDefault: Boolean get() = overridden.isEmpty() && snoozeUntilEpoch == null
}
