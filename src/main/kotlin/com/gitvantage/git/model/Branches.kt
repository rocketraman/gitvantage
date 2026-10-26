// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git.model

import com.gitvantage.model.Meta
import java.io.File

data class Branch(
    val name: String,
    val isCurrent: Boolean,
    val isMainline: Boolean,
    val behind: Int, // commits mainline is ahead of this branch
    val ahead: Int, // commits on this branch not in mainline
    val merged: Boolean, // its own commits have all landed in mainline
    val stale: Boolean, // old and very behind mainline
    val lastRelative: String,
    val upstream: String?, // tracking branch short name (e.g. "origin/main"), null if none
    val upstreamAhead: Int, // local commits the upstream doesn't have
    val upstreamBehind: Int, // upstream commits the local branch doesn't have
    val upstreamGone: Boolean, // upstream was configured but no longer exists on the remote
    /**
     * The working tree that has this branch checked out, when it isn't this one. git allows a
     * branch in exactly one tree at a time and refuses to switch to a branch held elsewhere,
     * so this is what makes "Switch" unavailable rather than merely failing.
     */
    val worktreePath: String? = null,
    /**
     * A working tree sitting on this branch's tip with a detached HEAD, in a folder named for the
     * branch. git doesn't count that as holding the branch — it can still be switched to or deleted
     * from here — so this is a label and nothing more.
     */
    val detachedWorktreePath: String? = null,
    /**
     * No commits of its own: the branch sits on mainline's history, so there is nothing on it that
     * could have been merged. Never true together with [merged].
     */
    val empty: Boolean = false,
    /**
     * Where `git push` sends this branch, when that isn't [upstream] — a fork set up to pull from one
     * remote and push to another (`remote.pushDefault` / `branch.<b>.pushRemote`). Null in the
     * ordinary case, where the two are the same ref and [upstreamAhead] already says what to push.
     */
    val pushRef: String? = null,
    val pushAhead: Int = 0, // local commits [pushRef] doesn't have
    val pushBehind: Int = 0, // [pushRef] commits the local branch doesn't have
    val pushMissing: Boolean = false, // [pushRef] doesn't exist on its remote yet
    /**
     * For the mainline only: the same branch on every *other* remote, e.g. `["upstream/master"]` —
     * the refs it could be set to pull from instead of the one it tracks. A remote named `upstream`
     * sorts first; nothing else about the names is assumed.
     */
    val pullCandidates: List<String> = emptyList(),
    /**
     * [merged] by content rather than history: squash- or rebase-merged, so git itself still calls
     * it unmerged (`git branch -d` refuses it). See BranchOps.landedByContent.
     */
    val squashed: Boolean = false,
) {
    /** Pulls from one remote and pushes to another — see [pushRef]. */
    val isTriangular get() = pushRef != null

    /** Held by another working tree — see [worktreePath]. */
    val inOtherWorktree get() = worktreePath != null

    /** Just the folder name of the tree it's in, which is how the worktree list names it too. */
    val worktreeName get() = (worktreePath ?: detachedWorktreePath)?.let { File(it).name }
}

data class RemoteBranch(
    val name: String, // full short ref, e.g. "origin/feature/login"
    val shortName: String, // without the remote prefix, e.g. "feature/login"
    val author: String, // author of the branch tip
    val lastRelative: String,
    val hasLocal: Boolean, // a local branch of the same short name already exists
    val merged: Boolean, // fully merged into mainline
    val squashed: Boolean = false, // merged by content only — see Branch.squashed
) {
    /** The remote this branch lives on, e.g. "origin" — the prefix [shortName] drops. */
    val remote get() = name.substringBefore('/')

    /**
     * A shared integration branch (see [Meta.INTEGRATION_BRANCHES]) — no Delete is offered for
     * one. Covers mainline too, so it's the single test the delete action asks.
     */
    val integration get() = Meta.isIntegrationBranch(shortName)
}

/**
 * [branches] split by remote, each group keeping its incoming order (newest first). Remotes named
 * in [first] lead in that order — the ones the mainline pushes to and pulls from, so a fork lists
 * your own copy before the project's — and the rest follow alphabetically.
 */
fun groupByRemote(branches: List<RemoteBranch>, first: List<String>): List<Pair<String, List<RemoteBranch>>> {
    val groups = branches.groupBy { it.remote }
    val lead = first.distinct().filter { it in groups }
    return (lead + (groups.keys - lead.toSet()).sorted()).map { it to groups.getValue(it) }
}
