// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

/**
 * Per-repo model (README § State Management). Tags are "namespace:value" strings.
 * Instances are produced by [RepoScanner] from live `git` output (see GitScan.kt),
 * merged with persisted tags/notes from the [Registry].
 */
data class ChangedFile(
    val tag: String,        // M / A / D / ? / R
    val section: String,    // staged | unstaged | untracked
    val path: String,
    val tagColor: String,   // hex, per change type
)

data class Stash(val label: String, val msg: String)

/** A git repo discovered under a picked parent folder, shown in the "Add repo" chooser. */
data class RepoCandidate(val path: String, val name: String, val alreadyTracked: Boolean)

data class Reminder(val text: String, val due: String, val overdue: Boolean = false)

data class Repo(
    val id: String,
    val name: String,
    val branch: String,
    val tags: List<String> = emptyList(),
    val ahead: Int = 0,
    val behind: Int = 0,
    val staged: Int = 0,
    val unstaged: Int = 0,
    val untracked: Int = 0,
    val stash: Int = 0,
    val last: String = "",
    val lastCommitEpoch: Long? = null,
    val author: String = "",
    val upstream: String? = null,
    val hasRemote: Boolean = false,
    val webBase: String? = null,        // browsable https base for the remote, e.g. https://github.com/owner/repo
    val isGitHub: Boolean = false,      // remote host is github.com (enables commit/issue/PR/actions links)
    val hasWorkflows: Boolean = false,  // repo defines .github/workflows (enables the Actions link)
    val hasSubmodules: Boolean = false,
    val superproject: String? = null,   // parent working-tree path if this repo is itself a submodule
    val worktreeCount: Int = 0,         // working trees sharing this repository (main + linked, excluding a bare main)
    val isWorktree: Boolean = false,    // this checkout is a linked worktree, not the main one
    val worktreeMain: String? = null,   // the main working tree's path, when this is a linked worktree
    val isGitRepo: Boolean = true,
    val warning: String? = null,
    val stale: Boolean = false,
    val staleDays: Int = 30,        // effective "stale after N days" threshold (per-repo override or global)
    val staleImportant: Boolean = false,   // treat staleness as important (red) rather than informational (amber)
    val snoozed: Boolean = false,
    val snoozedFor: String? = null,
    val dirtyFor: String? = null,
    val dirtySince: Long? = null,   // epoch millis the tree first went dirty (from the registry)
    val modifiedAt: Long? = null,   // epoch millis of the newest change in the working tree (mtime)
    val reminder: Reminder? = null,
    val note: String? = null,
    val files: List<ChangedFile> = emptyList(),
    val stashes: List<Stash> = emptyList(),
) {
    /**
     * True when this checkout shares its repository with other working trees — either it *is* a
     * linked worktree, or it's the checkout others were added from. Written this way rather than
     * `worktreeCount > 1` so a bare main repo with a single linked worktree still counts: the
     * bare one isn't a working tree, so the count there is 1.
     */
    val hasWorktrees get() = isWorktree || worktreeCount > 1
}
