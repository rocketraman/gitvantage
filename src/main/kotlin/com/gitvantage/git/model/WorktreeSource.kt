// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git.model

/**
 * What a new worktree is made from — the ref a row handed to the New worktree dialog, or what a
 * ref typed into it resolved to.
 *
 * Split by kind because each one asks git for something different: a local branch is checked out
 * as itself, a remote branch needs a local branch made to track it, and a tag or commit has no
 * branch at all unless one is named. A pull request is a fourth thing again — `gh` fetches it
 * into the folder after git has made one.
 */
sealed interface WorktreeSource {
    /** What the dialog's subtitle and the folder name are made from. */
    val label: String

    /** An existing local branch, checked out as itself. git refuses it if another tree holds it. */
    data class LocalBranch(val name: String) : WorktreeSource {
        override val label get() = name
    }

    /** A remote-tracking branch with [name] like "origin/fix-dpi"; a local branch is made to track it. */
    data class RemoteBranch(val name: String) : WorktreeSource {
        override val label get() = name
        val shortName get() = name.substringAfter('/')
    }

    /** A tag or bare commit. [rev] is what git is given; [label] is how it was named — the tag, or the short hash. */
    data class Commit(val rev: String, override val label: String) : WorktreeSource

    /** A pull request, checked out into the new folder by `gh pr checkout`, which picks its branch name. */
    data class PullRequest(val number: Int, val title: String) : WorktreeSource {
        override val label get() = "PR #$number"
    }
}
