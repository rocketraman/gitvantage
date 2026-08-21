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
    val merged: Boolean, // fully merged into mainline
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
) {
    /** Held by another working tree — see [worktreePath]. */
    val inOtherWorktree get() = worktreePath != null

    /** Just the folder name of [worktreePath], which is how the worktree list names it too. */
    val worktreeName get() = worktreePath?.let { File(it).name }
}

data class RemoteBranch(
    val name: String, // full short ref, e.g. "origin/feature/login"
    val shortName: String, // without the remote prefix, e.g. "feature/login"
    val author: String, // author of the branch tip
    val lastRelative: String,
    val hasLocal: Boolean, // a local branch of the same short name already exists
    val merged: Boolean, // fully merged into mainline
) {
    /** The remote this branch lives on, e.g. "origin" — the prefix [shortName] drops. */
    val remote get() = name.substringBefore('/')

    /**
     * A shared integration branch (see [Meta.INTEGRATION_BRANCHES]) — no Delete is offered for
     * one. Covers mainline too, so it's the single test the delete action asks.
     */
    val integration get() = Meta.isIntegrationBranch(shortName)
}
