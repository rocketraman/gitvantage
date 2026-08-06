// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git.model

import com.gitvantage.model.ChangedFile

/** Parsed `git status --porcelain`: the changed files plus the per-section counts. */
data class StatusResult(
    val files: List<ChangedFile>,
    val staged: Int,
    val unstaged: Int,
    val untracked: Int,
)
