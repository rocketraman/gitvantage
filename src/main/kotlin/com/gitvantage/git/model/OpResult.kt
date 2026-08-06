// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git.model

/**
 * The outcome of one side-effecting git operation, as the UI needs it: which repo it touched,
 * whether it worked, and the line to put in a toast.
 *
 * Shared by every `*Ops` object in the git package — it belongs to none of them individually,
 * which is why it sits here rather than nested inside whichever one happened to declare it first.
 *
 * [ok] is "the operation did what it said", not "nothing went wrong": a worktree removal whose
 * branch delete was refused still reports true, because the worktree is gone and a retry can't help.
 */
data class OpResult(val id: String, val ok: Boolean, val message: String)
