// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.app

import androidx.compose.ui.graphics.Color
import com.gitvantage.git.RepoScanner
import com.gitvantage.git.model.Commit
import com.gitvantage.model.ChangedFile
import com.gitvantage.model.Meta
import com.gitvantage.model.Repo
import com.gitvantage.model.Worktree
import com.gitvantage.model.WorktreeAlert
import com.gitvantage.model.WorktreeAlerts

/**
 * Derived, presentation-ready values. Mirrors the reference component's `view(r)`
 * (per-repo) and `renderVals()` (app-wide filtering/grouping) so the visuals and
 * semantics match the handoff exactly.
 */

data class TagChip(
    val key: String,
    val ns: String,
    val value: String,
    val label: String,
    val color: Color,
    val bg: Color,
    val border: Color,
)

fun tagStyle(t: String): TagChip {
    val i = t.indexOf(':')
    val ns = if (i >= 0) t.substring(0, i) else "tag"
    val value = if (i >= 0) t.substring(i + 1) else t
    val p = nsPalette(ns)
    return TagChip(key = t, ns = ns, value = value, label = value, color = p.c, bg = p.t, border = p.b)
}

data class Badge(val txt: String, val color: Color, val bg: Color)
data class Segment(val fraction: Float, val color: Color)

/**
 * How loudly a repo's open GitHub issues/PRs should signal, after the per-repo escalation
 * toggle. Deliberately named for the *tier* rather than the color, because the same tier is
 * consumed by three places that must stay in step: the accent cascade and badge in
 * [deriveView], and `AppState.attentionRank`.
 */
enum class IssueLevel { NONE, INFO, IMPORTANT, CRITICAL }

/**
 * A repo's open-issue picture, already filtered by the "only mine" setting and already
 * resolved against the per-repo tracking/escalation toggles. Assembled by `AppState` (which
 * owns both the fetched [GitHub.RepoState] and the registry flags) and handed to [deriveView],
 * rather than living on [Repo] — [Repo] is rebuilt from scratch by [RepoScanner] on every
 * filesystem event, which would throw away network data several times a minute.
 */
data class GhSummary(
    val openIssues: Int,
    val openPrs: Int,
    /** Of the counted items, those awaiting a response from you. */
    val awaiting: Int,
    /** The counted items, newest-updated first — the detail panel's list. */
    val items: List<GitHub.Item>,
    /** True when more were open than one fetch inspects, so [awaiting] is a floor, not a count. */
    val truncated: Boolean = false,
    /**
     * True when [openIssues]/[openPrs] are themselves floors rather than exact. Only happens
     * under "only mine": that count can't come from GitHub's `totalCount` (which can't be
     * filtered server-side), so it's the size of the fetched-then-filtered list — and the fetch
     * is capped. Without "only mine" the counts are GitHub's own totals and always exact.
     */
    val countIsFloor: Boolean = false,
    val important: Boolean = false,
    val error: String? = null,
) {
    val open get() = openIssues + openPrs

    val level: IssueLevel get() = when {
        awaiting > 0 -> if (important) IssueLevel.CRITICAL else IssueLevel.IMPORTANT
        open > 0 -> if (important) IssueLevel.IMPORTANT else IssueLevel.INFO
        else -> IssueLevel.NONE
    }
}

/** Emphasis for "needs attention" rows: subtle → thin bar, medium → bar, loud → tinted row. */
enum class Emphasis { SUBTLE, MEDIUM, LOUD }
enum class ViewMode { TABLE, CARDS }

/**
 * One of a repo's linked worktrees, resolved for display: the git facts plus the answer to "will
 * this worktree's problems reach the parent row".
 *
 * Assembled by [AppState] rather than derived here from [Repo] alone, for the same reason
 * [GhSummary] is: the override that decides [effective] lives in the registry, which the scanner
 * neither reads nor preserves.
 *
 * [parentAlertsOn] is one flag for all three alerts because at the repo level they *are* one flag —
 * a repo either has alerts running or is snoozed. That's what makes "Inherit · on" readable without
 * opening anything: there is a single parent answer to inherit.
 */
data class WorktreeView(
    val wt: Worktree,
    val repoId: String,
    val alerts: WorktreeAlerts,
    /** This worktree's own snooze, independent of the parent's. */
    val snoozed: Boolean,
    val snoozedFor: String?,
    /** Whether the parent checkout's alerts are running — what an inheriting alert resolves to. */
    val parentAlertsOn: Boolean,
) {
    val path get() = wt.path
    val branch get() = wt.branch

    /** The branch name is this row's identity; a detached worktree has only its folder to go on. */
    val label: String get() = wt.branch ?: if (wt.bare) wt.name else "${wt.name} (detached)"

    /** Whether [alert] fires for this worktree, after its own snooze, its override, and the parent. */
    fun effective(alert: WorktreeAlert): Boolean = when {
        snoozed -> false
        else -> alerts[alert] ?: parentAlertsOn
    }

    /** Answers for itself on something — the ⚙ beside its ☾ chip on every surface. */
    val overridden: Boolean get() = alerts.overridden.isNotEmpty() || snoozed

    /**
     * Whether this worktree's unlanded work pulls the parent row into Attention.
     *
     * The one worktree fact that escapes its own row, and the reason the override exists: a folder
     * holding work git would delete with it is the parent's problem too, right up until the user
     * says "not this one".
     */
    val rollsUp: Boolean get() = wt.unlanded && effective(WorktreeAlert.UNLANDED)

    /** Dot colour on the sub-row and the card strip: red gone, amber holding work, green clean. */
    val accent: Color get() = when {
        wt.missing -> Tokens.red
        wt.unlanded -> Tokens.amber
        else -> Tokens.green
    }

    /** "↑2 vs main" — how far ahead of the ref its unmerged count was measured against. */
    val vsMainline: String? get() =
        if (wt.unmerged > 0 && !wt.branchMerged) "↑${wt.unmerged} vs ${wt.mainline?.substringAfterLast('/') ?: "mainline"}"
        else null

    /** What the ⚙ tooltip spells out: which alerts this worktree answers for itself. */
    fun overrideSummary(repoName: String): String {
        val parts = alerts.overridden.map { "${it.label.lowercase()}: ${if (alerts[it] == true) "on" else "off"}" }
        val snooze = snoozedFor?.let { "snoozed $it" }
        val all = (listOfNotNull(snooze) + parts).joinToString(" · ")
        return if (all.isEmpty()) "Inherits everything from $repoName."
        else "$all · everything else follows $repoName"
    }
}

/** Fully-derived view of one repo, ready to render. */
data class RepoView(
    val repo: Repo,
    val tags: List<String>,
    val tagChips: List<TagChip>,
    val badges: List<Badge>,
    val accent: Color,       // state color (dot / left bar / status)
    val accentBg: Color,     // matching tint
    val statusLabel: String,
    val segments: List<Segment>,
    val ahead: Int,
    val behind: Int,
    val upstream: String,
    val isDirty: Boolean,
    val isStale: Boolean,
    val hasIssue: Boolean,
    val hasReminder: Boolean,
    val attention: Boolean,
    val aging: Boolean,
    val snoozed: Boolean,
    val primary: Primary?,   // contextual Commit / Push
    val gh: GhSummary? = null,        // open GitHub issues/PRs, null when not tracked
    val issueLevel: IssueLevel = IssueLevel.NONE,   // gh's level, forced to NONE while snoozed
    /** This repo's linked worktrees, alert overrides already resolved. Empty for nearly every repo. */
    val worktrees: List<WorktreeView> = emptyList(),
) {
    val id get() = repo.id
    val changed get() = repo.staged + repo.unstaged + repo.untracked
    /** Open issues/PRs awaiting a response from you (0 while snoozed). */
    val awaitingYou get() = if (issueLevel == IssueLevel.NONE) 0 else (gh?.awaiting ?: 0)

    /**
     * Open issues/PRs for the status filter and its count — 0 while snoozed, mirroring how
     * [isStale] and [aging] drop a snoozed repo out of their filters. Use [gh] directly (not
     * this) wherever the real number should still be shown, e.g. the detail panel.
     */
    val openIssues get() = if (snoozed) 0 else (gh?.open ?: 0)

    /**
     * Worktrees whose unlanded work reaches this row — the effective count, not the scanned one.
     * [Repo.worktreesUnlanded] counts folders; this counts the ones still allowed to speak up.
     */
    val worktreesUnlanded get() = worktrees.count { it.rollsUp }
}

/** Contextual primary action pill. */
data class Primary(val label: String, val bg: Color, val color: Color, val border: Color)

fun deriveView(
    repo: Repo,
    accent: Color,
    tags: List<String>,
    gh: GhSummary? = null,
    worktrees: List<WorktreeView> = emptyList(),
): RepoView {
    val C = Tokens
    val staged = repo.staged; val unstaged = repo.unstaged; val untracked = repo.untracked; val stash = repo.stash
    val changed = staged + unstaged + untracked
    val isDirty = changed > 0
    val snoozed = repo.snoozed
    // "Aging": uncommitted work that's been sitting past the threshold — a distinct attention signal.
    val aging = isDirty && repo.dirtySince != null &&
        (System.currentTimeMillis() - repo.dirtySince) >= Meta.AGING_MS && !snoozed
    // Stale, on a repo the user marked "important" — escalates the signal from amber to red.
    val staleImportant = repo.stale && repo.staleImportant
    // Open issues/PRs contribute to the row's color, but a snooze silences them like every other
    // signal — the counts stay visible in the detail panel, they just stop shouting from the list.
    val issueLevel = if (snoozed) IssueLevel.NONE else (gh?.level ?: IssueLevel.NONE)
    // Worktrees still holding work that's allowed to reach this row — see [WorktreeView.rollsUp].
    // Counted here rather than taken from [Repo.worktreesUnlanded] because the scanner can't know
    // which of them the user has silenced.
    val unlandedTrees = worktrees.count { it.rollsUp }

    val badges = buildList {
        if (staged > 0) add(Badge("$staged staged", C.green, C.tintGreen))
        if (unstaged > 0) add(Badge("$unstaged modified", C.amber, C.tintAmber))
        if (untracked > 0) add(Badge("$untracked untracked", C.gray, C.tintGray))
        if (stash > 0) add(Badge("⚑ $stash ${if (stash > 1) "stashes" else "stash"}", C.purple, C.tintPurple))
        repo.warning?.let { add(Badge(it, C.redText, C.tintRed)) }
        // Sync status as pills (prominent, and in-line with the other badges) rather than a faint
        // arrow tucked onto the branch line.
        if (repo.ahead > 0) add(Badge("↑${repo.ahead} ahead", accent, C.tintBlue))
        if (repo.behind > 0) add(Badge("↓${repo.behind} behind", C.snoozeBtnText, C.snoozeBtnBg))
        if (repo.stale) {
            if (staleImportant) add(Badge("stale", C.amber, C.tintAmber))
            else add(Badge("stale", accent, C.tintBlue))
        }
        if (aging) add(Badge("aging ${repo.dirtyFor ?: ""}".trim(), C.amber, C.tintAmber))
        // Working trees. Structural information, never part of the accent cascade — extra checkouts
        // are a workflow, not a problem — so this wears the indigo the Worktrees filter chip does
        // rather than the accent blue, which is spoken for by "behind" and "open issues". A linked
        // worktree says so instead of showing the count: "you are not on the main checkout" is the
        // fact that changes how you read the row.
        val wtPalette = C.worktreeChip
        if (repo.isWorktree) add(Badge("⑂ worktree", wtPalette.c, wtPalette.t))
        else if (worktrees.isNotEmpty()) {
            add(Badge("⑂ ${worktrees.size} ${if (worktrees.size == 1) "worktree" else "worktrees"}", wtPalette.c, wtPalette.t))
        }
        // The exception to "worktrees are workflow, not a problem": another checkout holding
        // uncommitted or unmerged work. That work is invisible from this row's changed-file counts
        // and a `worktree remove` would take it with it, so it gets its own amber badge rather than
        // recolouring the indigo count — "2 worktrees" and "1 of them has work in it" are different
        // facts, the same way the issue count and "awaiting you" are kept apart above. It stays
        // visible while snoozed; only its pull on the row's colour is silenced.
        if (unlandedTrees > 0) add(Badge("⑂ $unlandedTrees unlanded", C.amber, C.tintAmber))
        // Open issues/PRs. Two badges at most: the count (always, when tracked) and — when some
        // are waiting on you — a separate louder one, because "12 open" and "1 of them needs you"
        // are different facts and collapsing them into one number hides the actionable half.
        if (gh != null && gh.open > 0) {
            // Each badge carries its own tier, escalated once on "important" repos: the count is
            // blue → amber, and "awaiting you" is amber → red. The count badge deliberately does
            // NOT turn amber just because something is awaiting — that's the other badge's job,
            // and doubling the color would say the same thing twice.
            //
            // A snooze drops both back to the quiet end. Without this the row's dot went green
            // (issueLevel is NONE while snoozed) but a red "awaiting you" chip stayed on it —
            // the loudest thing on a row that is supposed to have been silenced.
            val loud = gh.important && !snoozed
            val (col, bg) = if (loud) C.amber to C.tintAmber else accent to C.tintBlue
            // "45+" under "only mine", where the count is the filtered fetch and the fetch is
            // capped; without it the badge states a number it can't actually stand behind.
            val more = if (gh.countIsFloor) "+" else ""
            val parts = buildList {
                if (gh.openIssues > 0) add("${gh.openIssues}$more open")
                if (gh.openPrs > 0) add("${gh.openPrs}$more PR${if (gh.openPrs > 1) "s" else ""}")
            }
            add(Badge("◎ ${parts.joinToString(" · ")}", col, bg))
            // Suppressed entirely while snoozed, the same way the "aging" badge is — this one is
            // a call to action, and a snooze is the user saying "not now".
            if (gh.awaiting > 0 && !snoozed) {
                add(
                    Badge(
                        // "3+" when more were open than one fetch inspects: "awaiting you" is only
                        // known for the items actually examined, so it's a floor, not a count.
                        "↩ ${gh.awaiting}${if (gh.truncated) "+" else ""} awaiting you",
                        if (loud) C.redText else C.amber,
                        if (loud) C.tintRed else C.tintAmber,
                    ),
                )
            }
        }
        repo.reminder?.let { rem ->
            val col = if (rem.overdue) C.remOverdue else C.remTeal
            val bg = if (rem.overdue) C.remOverdueBg else C.remTealBg
            add(Badge("◷ ${rem.due}", col, bg))
        }
    }

    val acc: Color; val accBg: Color
    when {
        // Red is reserved for things that are actually wrong: detached HEAD, no upstream, not a repo
        // — plus, on repos where issues are marked important, an issue or PR waiting on your reply.
        // Amber is work sitting on this machine — uncommitted changes or unpushed commits — plus
        // staleness on repos explicitly marked "important", where going quiet is a real signal, and
        // anything on GitHub that's waiting on a response from you.
        // Blue is "nothing to do locally, just so you know": the remote moved on and there are
        // commits to pull, the repo has simply been quiet (stable code sits untouched), or it has
        // open issues nobody is waiting on you for.
        repo.warning != null || issueLevel == IssueLevel.CRITICAL -> { acc = C.red; accBg = C.tintRed }
        // Unlanded work in another checkout is amber for the reason the tier exists — it is work
        // sitting on this machine — even though it isn't sitting in *this* folder. Snoozing drops
        // it out, like every other call to action.
        isDirty || repo.ahead > 0 || staleImportant ||
            (unlandedTrees > 0 && !snoozed) ||
            issueLevel == IssueLevel.IMPORTANT -> { acc = C.amber; accBg = C.tintAmber }
        repo.behind > 0 || repo.stale || issueLevel == IssueLevel.INFO -> { acc = accent; accBg = C.tintBlue }
        else -> { acc = C.green; accBg = C.tintGreen }
    }

    val statusLabel = when {
        repo.warning != null -> repo.warning
        isDirty -> "$changed changed"
        repo.ahead > 0 -> "${repo.ahead} to push"
        repo.behind > 0 -> "${repo.behind} behind"
        else -> "Clean"
    }

    val segments = if (changed > 0) {
        listOf(
            Triple(staged, C.green, 0), Triple(unstaged, C.amber, 1), Triple(untracked, C.untrackedSeg, 2),
        ).filter { it.first > 0 }.map { Segment(it.first.toFloat() / changed, it.second) }
    } else {
        listOf(Segment(1f, C.green))
    }

    val primary = when {
        isDirty -> Primary("Commit", C.snoozeBtnBg, C.snoozeBtnText, C.snoozeBtnBorder)
        repo.ahead > 0 -> Primary("Push", C.tintBlue, accent, Tokens.accentBorder)
        else -> null
    }

    // Open issues alone aren't "attention" — a healthy repo always has some. Only the ones
    // actually waiting on you are, which is what everything above IssueLevel.INFO means.
    // Unlanded work in a linked worktree is attention for the same reason aging is: something is
    // sitting on this machine that a `worktree remove` would take with it, and the parent row is the
    // only place it can be said. A worktree whose Unlanded-work alert is Off has already dropped out
    // of [unlandedTrees], so silencing one silences its pull here too.
    val attention = (
        repo.warning != null || repo.stale || (repo.ahead > 0 && !isDirty) || aging ||
            unlandedTrees > 0 ||
            issueLevel == IssueLevel.IMPORTANT || issueLevel == IssueLevel.CRITICAL
        ) && !snoozed

    return RepoView(
        repo = repo,
        tags = tags,
        tagChips = tags.map { tagStyle(it) },
        badges = badges,
        accent = acc,
        accentBg = accBg,
        statusLabel = statusLabel,
        segments = segments,
        ahead = repo.ahead,
        behind = repo.behind,
        upstream = repo.upstream ?: if (repo.warning != null) "—" else "origin/${repo.branch}",
        isDirty = isDirty,
        isStale = repo.stale && !snoozed,
        hasIssue = repo.warning != null,
        hasReminder = repo.reminder != null,
        attention = attention,
        aging = aging,
        snoozed = snoozed,
        primary = primary,
        gh = gh,
        issueLevel = issueLevel,
        worktrees = worktrees,
    )
}

data class RepoGroup(
    val name: String,
    val nsLabel: String,
    val showHeader: Boolean,
    val repos: List<RepoView>,
)

/**
 * File-status letter color by change type (detail panel).
 *
 * Derived here rather than stamped onto [ChangedFile] by the scanner: the scanner runs on every
 * filesystem event and its output outlives a theme switch, so a color baked in at scan time would
 * still be the old theme's until the repo happened to change on disk.
 */
fun fileTagColor(f: ChangedFile): Color = when {
    f.section == "untracked" -> Tokens.gray
    f.tag == "D" -> Tokens.red
    f.section == "unstaged" -> Tokens.amber
    f.tag == "R" || f.tag == "C" -> Tokens.accent
    else -> Tokens.green
}
