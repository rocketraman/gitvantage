// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.model

/**
 * Per-repo model (README § State Management). Tags are "namespace:value" strings.
 * Instances are produced by the git layer's RepoScanner from live `git` output,
 * merged with persisted tags/notes from the [Registry].
 */
data class ChangedFile(
    val tag: String,        // M / A / D / ? / R
    val section: String,    // staged | unstaged | untracked
    val path: String,
)

data class Stash(val label: String, val msg: String)

/** A git repo discovered under a picked parent folder, shown in the "Add repo" chooser. */
data class RepoCandidate(val path: String, val name: String, val alreadyTracked: Boolean)

data class Reminder(val text: String, val due: String, val overdue: Boolean = false)

/** What [Repo.branch] reads when HEAD isn't on a branch — a label for the UI, not a branch name. */
const val DETACHED_BRANCH = "(detached)"

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
    val isWorktree: Boolean = false,    // this checkout is a linked worktree, not the main one
    val worktreeMain: String? = null,   // the main working tree's path, when this is a linked worktree
    /**
     * The linked working trees attached to this repository — every tree but this one, and never a
     * bare main repo, which has no files to report on.
     *
     * Carried on the repo rather than fetched per-surface because worktrees are no longer tracked
     * repos of their own: they are sub-rows of this one in the table, a strip on its card, and cards
     * in its detail pane, and all three read the same list. The scan already had it — the git
     * layer's worktree lister builds it to derive [worktreesUnlanded] — so this costs it nothing.
     *
     * Missing the per-tree "has its branch landed" flag, which needs a merge-base each and stays
     * with the detail panel's own load.
     */
    val worktrees: List<Worktree> = emptyList(),
    /**
     * Absolute paths of the *other* working trees that live inside this checkout's folder — a
     * coding session's `.claude/worktrees/<slug>`, or any worktree added under the repo by hand.
     *
     * Not presentational: it exists for the filesystem watcher. git ignores these directories, but
     * the recursive watch registered for the repo does not, so without it every file written in
     * another checkout arrives looking like a change to this one.
     */
    val nestedWorktrees: List<String> = emptyList(),
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
    /**
     * The changed files, **capped** — see `RepoScanner.MAX_FILES`. Use [changedCount] for how many
     * there are; this is the sample kept to be shown, not the tally.
     */
    val files: List<ChangedFile> = emptyList(),
    val stashes: List<Stash> = emptyList(),
) {
    /**
     * True when [branch] is an actual branch name rather than a stand-in — "(detached)" for a
     * detached HEAD, "—" for a folder that isn't a git repo. Those read fine as labels but are
     * meaningless to copy or hand back to git.
     */
    val hasNamedBranch get() = isGitRepo && branch != DETACHED_BRANCH

    /**
     * How many changed entries the working tree actually has — always exact, where [files] may have
     * been capped. One entry per file *per section*, matching [files]: a file both staged and
     * modified is counted in each, because that is how it is listed.
     */
    val changedCount get() = staged + unstaged + untracked

    /** Whether [files] is a sample rather than the whole list, so a reader can be told as much. */
    val filesTruncated get() = files.size < changedCount
}
