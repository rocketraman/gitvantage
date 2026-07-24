// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

import androidx.compose.ui.graphics.Color

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

/** Emphasis for "needs attention" rows: subtle → thin bar, medium → bar, loud → tinted row. */
enum class Emphasis { SUBTLE, MEDIUM, LOUD }
enum class ViewMode { TABLE, CARDS }

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
) {
    val id get() = repo.id
    val changed get() = repo.staged + repo.unstaged + repo.untracked
}

/** Contextual primary action pill. */
data class Primary(val label: String, val bg: Color, val color: Color, val border: Color)

fun deriveView(repo: Repo, accent: Color, tags: List<String>): RepoView {
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
        repo.reminder?.let { rem ->
            val col = if (rem.overdue) C.remOverdue else C.remTeal
            val bg = if (rem.overdue) C.remOverdueBg else C.remTealBg
            add(Badge("◷ ${rem.due}", col, bg))
        }
    }

    val acc: Color; val accBg: Color
    when {
        // Red is reserved for things that are actually wrong: detached HEAD, no upstream, not a repo.
        // Amber is work sitting on this machine — uncommitted changes or unpushed commits — plus
        // staleness on repos explicitly marked "important", where going quiet is a real signal.
        // Blue is "nothing to do locally, just so you know": the remote moved on and there are
        // commits to pull, or the repo has simply been quiet (stable code sits untouched).
        repo.warning != null -> { acc = C.red; accBg = C.tintRed }
        isDirty || repo.ahead > 0 || staleImportant -> { acc = C.amber; accBg = C.tintAmber }
        repo.behind > 0 || repo.stale -> { acc = accent; accBg = C.tintBlue }
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
        repo.ahead > 0 -> Primary("Push", C.tintBlue, accent, hex("#c3dcf8"))
        else -> null
    }

    val attention = (repo.warning != null || repo.stale || (repo.ahead > 0 && !isDirty) || aging) && !snoozed

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
    )
}

data class RepoGroup(
    val name: String,
    val nsLabel: String,
    val showHeader: Boolean,
    val repos: List<RepoView>,
)

/** File-status letter color by change type (detail panel). */
fun fileTagColor(f: ChangedFile): Color = hex(f.tagColor)
