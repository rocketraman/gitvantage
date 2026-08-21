// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git.model

data class Submodule(
    val path: String,
    val url: String, // where the submodule points (from .gitmodules)
    val branch: String?, // configured tracking branch, if any
    val statusChar: Char, // git submodule status: ' ' ok, '+' differs, '-' uninit, 'U' conflict
    val initialized: Boolean,
    val recorded: String, // short sha the parent records for this submodule
    val recordedFull: String, // full sha (for range diffs)
    val remoteRef: String?, // resolved remote-tracking ref used for "behind" / the diff target
    val behind: Int, // commits the submodule's remote is ahead of the recorded pointer
    val dirtyCount: Int, // uncommitted changes inside the submodule (0 = clean)
) {
    val dirty get() = dirtyCount > 0
}
