// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git.model

import com.gitvantage.model.ChangedFile

/** Parsed `git status --porcelain`: the changed files plus the per-section counts. */
data class StatusResult(val files: List<ChangedFile>, val staged: Int, val unstaged: Int, val untracked: Int)

/**
 * The `## ` header `git status --branch` puts above the file list: which branch HEAD is on, what it
 * tracks, and how far the two have diverged.
 *
 * Four separate commands' worth of answer, which is the point — see `RepoScanner.parseBranch`.
 */
data class BranchStatus(
    /** The current branch, or null when HEAD is detached. */
    val branch: String?,
    /** The tracked branch, or null when none is configured *or* the one configured is gone. */
    val upstream: String?,
    val ahead: Int = 0,
    val behind: Int = 0,
)
