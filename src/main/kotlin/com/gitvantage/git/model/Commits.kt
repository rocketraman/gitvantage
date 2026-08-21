// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git.model

/** What a ref pointing at a commit is, so the UI can style them differently. */
enum class RefKind { HEAD, LOCAL_BRANCH, REMOTE_BRANCH, TAG }

data class CommitRef(val label: String, val kind: RefKind)

/** A commit's parent: full hash for git commands, git's own abbreviation for display. */
data class CommitParent(val fullHash: String, val shortHash: String)

data class Commit(
    val fullHash: String,
    val shortHash: String,
    val author: String,
    val relDate: String, // "3 days ago" (committer, relative)
    val isoDate: String, // absolute ISO-8601 (committer)
    val subject: String,
    val body: String, // remaining message lines (may be blank)
    val refs: List<CommitRef> = emptyList(), // branches/tags pointing here (git's %D decoration)
    val parents: List<CommitParent> = emptyList(),
    // Reachable from one of origin's remote-tracking refs — i.e. the commit exists on the
    // remote as of the last fetch, so a link to it on the web will actually resolve.
    val pushed: Boolean = true,
) {
    /** More than one parent means this commit merged history together. */
    val isMerge: Boolean get() = parents.size > 1
}
