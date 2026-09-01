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
    val head: String = "", // sha the submodule's working tree is actually on ("" if uninitialized)
    // The gitlink in the parent's *index* — the commit `git submodule update` checks out, and what
    // [statusChar]'s '+' is measured against. Identical to [recordedFull] except while a pointer
    // move is staged but not yet committed, which is exactly when the two must not be confused.
    val indexSha: String = "",
) {
    val dirty get() = dirtyCount > 0

    /** The working tree sits on a different commit than the parent points at (`+` in git's status). */
    val moved get() = statusChar == '+'
}
