// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.gitvantage.app.AppState
import com.gitvantage.app.Badge
import com.gitvantage.app.MonoFont
import com.gitvantage.app.Popup as AppPopup
import com.gitvantage.app.RepoView
import com.gitvantage.app.Tokens
import com.gitvantage.app.WorktreeView
import com.gitvantage.model.Meta
import com.gitvantage.model.Worktree
import com.gitvantage.model.WorktreeAlert
import com.gitvantage.model.WorktreeChange

/**
 * Every surface a linked worktree appears on: an indented sub-row under its repo in the table, a
 * compact strip on that repo's card, an expandable card in its detail pane, and the alerts popover
 * all three can open.
 *
 * They are together because they are one idea rendered at three densities. A worktree is a *branch
 * this repository has checked out somewhere else* — not a repository — so every one of these is
 * branch-first, none of them carries a repo-level control (no checkbox, no tags, no staleness), and
 * all of them reach git the same way: `git -C <worktree-path>`, from the parent's tracked entry, with
 * nothing registered of their own.
 *
 * What is deliberately *not* here is anything repo-wide. Branches, remotes, issues and staleness are
 * shared with the main checkout — one set of refs between them — so they are managed once, on the
 * parent, and the worktree section says so in its footer rather than offering a second copy.
 */

// ---------- table sub-row (§1b / §2a) ----------

/**
 * One worktree, as an indented row under its repo.
 *
 * Reads as a continuation of the parent rather than a row of its own: the parent's left accent bar
 * runs through it, the background steps back to [Tokens.panelFa], the identity is indented past
 * where a repo name starts, and a `└` marks the branch. That's what stops the eye counting it as a
 * fifth repository — which is exactly what it used to be.
 *
 * No checkbox, no tag chips, no primary-action pill. Those are all repo-level state, and a worktree
 * has none: you can't bulk-tag a branch's folder, and "Push" belongs to the checkout that owns the
 * remote.
 */
@Composable
fun WorktreeSubRow(state: AppState, rv: RepoView, wtv: WorktreeView, barWidth: Float) {
    val wt = wtv.wt
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth()
            .background(if (hovered) Tokens.rowHoverBg else Tokens.panelFa)
            .drawBottomBorder(Tokens.rowBorder)
            .drawLeftBar(rv.accent, barWidth)
            .hoverable(source)
            .onTap { state.openWorktreeInPane(rv.id, wt.path) }
            .padding(start = 41.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Txt("└", 13.sp, Tokens.worktreeChip.b, font = MonoFont)
        StatusDot(wtv.accent, 8)
        // Identity: the branch is the name, the folder is the footnote — the inverse of the old
        // worktree row, where a generated slug like "fix-rounding-9f3ab2" was the headline.
        Column(Modifier.width(238.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Txt("⑂ ${wtv.label}", 12.5.sp, Tokens.text, FontWeight.SemiBold, font = MonoFont)
            PathTip(wt.path) { Txt(shortPath(wt.path), 10.5.sp, Tokens.muted2, font = MonoFont) }
        }
        Box(Modifier.weight(1f)) { WorktreeBadges(state, wtv) }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Txt(lastCommitLine(wt), 12.sp, Tokens.muted, maxLines = 1)
        }
        // Actions. Held back until the pointer arrives, the same bargain the detail pane's list rows
        // make — a column of identical "Diff Log Terminal" down every sub-row says nothing about any
        // of them, and buries the badges that do.
        Row(
            Modifier.width(200.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hovered) {
                if (!wt.missing) {
                    RowAction("Diff") { state.openWorktreeDiff(wt.path) }
                    RowAction("Log") { state.openWorktreeLog(wt.path) }
                    RowAction("Terminal") { state.openTerminal(wt.path) }
                }
                if (canRemoveWithBranch(wt)) {
                    RowAction("Remove + branch", danger = true) { confirmRemove(state, rv, wt, alsoBranch = true) }
                }
            }
            WorktreeSnoozeChip(state, rv, wtv)
        }
    }
}

/**
 * The badges a worktree sub-row carries, in the order they'd cost you something: work still in the
 * tree, commits that haven't landed, then what kind of worktree this is.
 *
 * Two pill shapes on purpose, the same split the branch rows use: the counts are git numbers and get
 * the mono [BadgeView], while "agent" and "merged" are one-word verdicts about the branch and get the
 * rounder [BranchBadge].
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WorktreeBadges(state: AppState, wtv: WorktreeView) {
    val wt = wtv.wt
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (wt.missing) BranchBadge("missing", Tokens.redText, Tokens.tintRed)
        if (wt.dirtyCount > 0) BadgeView(Badge("${wt.dirtyCount} modified", Tokens.modifiedHdr, Tokens.tintAmber))
        wtv.vsMainline?.let { BadgeView(Badge(it, state.accent, Tokens.tintBlue)) }
        if (wt.agent) BranchBadge("agent", state.accent, Tokens.tintBlue)
        if (wt.branchMerged) BranchBadge("merged", Tokens.purple, Tokens.tintPurple)
        if (wt.locked) BranchBadge("locked", Tokens.purple, Tokens.tintPurple)
    }
}

// ---------- card strip (§1e / §2b) ----------

/**
 * The worktree strip on a repo card: one line per worktree, verdict on the right.
 *
 * A card has no room for the table's badge lane, so each row states the *one* thing worth knowing
 * about that checkout instead — see [Worktree.verdict]. Clicking one opens the parent's detail pane
 * with that worktree already expanded, which is what makes the strip a way in rather than a label.
 */
@Composable
fun WorktreeStrip(state: AppState, rv: RepoView) {
    if (rv.worktrees.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().drawTopBorder(Tokens.rowBorder).padding(top = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Txt(
            "⑂ WORKTREES · ${rv.worktrees.size}", 10.5.sp, Tokens.worktreeChip.c, FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
        rv.worktrees.forEach { wtv ->
            Row(
                Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand)
                    .onTap { state.openWorktreeInPane(rv.id, wtv.path) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                StatusDot(wtv.accent, 7)
                Txt(wtv.label, 11.sp, Tokens.text2, font = MonoFont, modifier = Modifier.weight(1f))
                Txt(wtv.wt.verdict, 10.5.sp, verdictColor(wtv.wt), FontWeight.SemiBold)
                compactAgo(wtv.wt)?.let { Txt(it, 10.5.sp, Tokens.muted2) }
            }
        }
    }
}

private fun verdictColor(wt: Worktree): Color = when {
    wt.missing -> Tokens.redText
    wt.dirtyCount > 0 || (wt.unmerged > 0 && !wt.branchMerged) -> Tokens.modifiedHdr
    wt.branchMerged -> Tokens.purple
    else -> Tokens.cleanText
}

// ---------- detail pane section (§1f / §2a) ----------

/**
 * The parent's worktree section: one expandable card per linked worktree.
 *
 * This is where a worktree's *contents* are reachable — its uncommitted files with a diffstat, its
 * recent commits, a commit dialog, a terminal in it — and reaching them from the parent's pane is
 * the whole point of the redesign. Under the old model you got there by tracking the worktree as a
 * separate repository, which bought a second copy of every repo-wide setting to answer the one
 * question "what's in that folder".
 */
@Composable
fun WorktreesPaneSection(state: AppState, rv: RepoView) {
    // Nothing to load or draw for a repo with no linked worktrees, which is nearly all of them. The
    // scan already answered that, so this costs no git: without the guard, selecting any repo would
    // run a `worktree list` to rediscover what the row it was clicked on already knew.
    if (rv.worktrees.isEmpty()) return
    LaunchedEffect(rv.id) { state.loadWorktrees(rv.id) }
    // The scan's list until the richer one lands, so the section doesn't blink in a frame late — it
    // has every field these cards show except "has its branch landed", which only adds a badge.
    val loaded = if (state.worktreesRepo == rv.id && state.worktrees.isNotEmpty()) {
        state.worktreeViews(rv.id, rv.repo.snoozed, state.worktrees)
    } else {
        rv.worktrees
    }
    if (loaded.isEmpty()) return
    val stale = state.worktrees.count { it.prunable || it.missing }
    Column(Modifier.fillMaxWidth().padding(top = 20.dp).drawTopBorder(Tokens.sectionBorder).padding(top = 14.dp)) {
        Row(
            Modifier.padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Txt("Worktrees", 12.5.sp, Tokens.text, FontWeight.Bold)
            Txt("— ${loaded.size}", 11.sp, Tokens.muted2)
            InfoTip(
                "Branches this repository has checked out in other folders. They share one set of " +
                    "commits, branches, remotes and stashes with this checkout — so what's per-worktree " +
                    "is only what's sitting in the folder, which is what each card opens.",
            )
            Spacer(Modifier.weight(1f))
            // `git worktree prune` takes no path — it drops every stale entry at once — so this is
            // a section action rather than a per-card one.
            if (stale > 0) {
                FlatAction(if (state.worktreesBusy) "Pruning…" else "Prune $stale stale", size = 12.sp) {
                    if (state.worktreesBusy) return@FlatAction
                    state.popup = AppPopup.Confirm(
                        "Prune stale worktree entries?",
                        "Forgets $stale worktree${if (stale == 1) "" else "s"} whose folder is gone. " +
                            "Nothing on disk is deleted — the branches and commits they held are untouched.",
                        "Prune", danger = false,
                    ) { state.pruneWorktrees(rv.id) }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            loaded.forEach { wtv -> WorktreeCard(state, rv, wtv) }
        }
        Txt(
            "Branches, remotes, issues and staleness are shared with the main checkout — managed " +
                "once, below.",
            11.sp, Tokens.muted2, maxLines = 2, modifier = Modifier.padding(top = 9.dp),
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WorktreeCard(state: AppState, rv: RepoView, wtv: WorktreeView) {
    val wt = wtv.wt
    val expanded = state.wtExpanded == wt.path
    val shape = RoundedCornerShape(8.dp)
    // Scrolls itself into view when it opens, which is also what "click a sub-row to reach that
    // worktree" resolves to: the pane selects the repo, the card expands, and the card brings itself
    // to where the eye is. Doing it here rather than in the click handler means it works the same
    // whether the expand came from the table, a card strip, or a click on the card itself.
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(expanded) { if (expanded) runCatching { bring.bringIntoView() } }
    Column(
        Modifier.fillMaxWidth().bringIntoViewRequester(bring)
            .clip(shape).border(1.dp, Tokens.borderE6, shape),
    ) {
        // Header — the whole strip toggles, so the chevron is an indicator rather than the target.
        Column(
            Modifier.fillMaxWidth()
                .background(if (expanded) Tokens.panelFb else Tokens.surface)
                .pointerHoverIcon(PointerIcon.Hand)
                .onTap { state.toggleWorktreeCard(wt.path) }
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Txt(if (expanded) "▾" else "▸", 10.sp, Tokens.muted2)
                // Branch and sha travel as one weighted group so the branch gets *all* the slack the
                // badges leave. Weighted individually against a spacer they split it evenly, and a
                // branch like "claude/clear-tag-filters-button" came out as "clau…" beside half a
                // card of white space.
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Txt(
                        "⑂ ${wtv.label}", 12.5.sp,
                        if (expanded) Tokens.text else Tokens.text2,
                        if (expanded) FontWeight.Bold else FontWeight.SemiBold,
                        font = MonoFont, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (wt.head.isNotEmpty()) Txt("@${wt.head.take(7)}", 10.5.sp, Tokens.muted2, font = MonoFont)
                }
                WorktreeStateBadges(state, wtv)
            }
            // Where it lives and how it stands, on one indented line under the branch it holds.
            PathTip(wt.path, Modifier.padding(start = 18.dp, top = 2.dp)) {
                Txt(metaLine(wtv), 11.sp, Tokens.muted, font = MonoFont, maxLines = 1)
            }
        }
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().drawTopBorder(Tokens.borderEd)
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Segmented(
                    listOf("changes" to "Changes", "log" to "Log"),
                    state.wtTab, state::setWorktreeTab, fontSize = 11.5.sp,
                )
                when {
                    state.wtLoading -> Txt("Loading…", 11.5.sp, Tokens.muted2)
                    state.wtTab == "log" -> WorktreeLogRows(state, wt)
                    else -> WorktreeChangeRows(state.wtChanges)
                }
            }
        }
        // Always present, expanded or not: this is the only route to Alerts ▾, and a control you must
        // open a card to reach is a control nobody finds.
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!wt.missing) {
                if (wt.dirtyCount > 0) {
                    RowAction("Open Diff") { state.openWorktreeDiff(wt.path) }
                    RowAction("Commit…") { state.popup = AppPopup.CommitWorktree(rv.id, wt.path, wtv.label) }
                }
                RowAction("Log") { state.openWorktreeLog(wt.path) }
                RowAction("Terminal") { state.openTerminal(wt.path) }
                RowAction("Folder") { state.openFolder(wt.path) }
            }
            AlertsAnchor(state, rv, wtv)
            if (!state.worktreesBusy && !wt.missing) {
                RowAction("Remove", danger = true) { confirmRemove(state, rv, wt, alsoBranch = false) }
                if (canRemoveWithBranch(wt)) {
                    RowAction("Remove + branch", danger = true) { confirmRemove(state, rv, wt, alsoBranch = true) }
                }
            }
        }
    }
}

/** The card header's verdict badges — the ones that say something has gone wrong keep their tint. */
@Composable
private fun WorktreeStateBadges(state: AppState, wtv: WorktreeView) {
    val wt = wtv.wt
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            wt.missing -> BranchBadge("missing", Tokens.redText, Tokens.tintRed)
            wt.prunable -> BranchBadge("prunable", Tokens.amber, Tokens.tintAmber)
            wt.dirtyCount > 0 -> BranchBadge("${wt.dirtyCount} uncommitted", Tokens.modifiedHdr, Tokens.tintAmber)
            else -> {}
        }
        if (wt.locked) {
            HoverTip(
                "Locked against pruning" + (wt.lockReason?.let { ": $it" } ?: "") +
                    ". Unlock it with `git worktree unlock` if you want git to reclaim it.",
            ) { BranchBadge("locked", Tokens.purple, Tokens.tintPurple) }
        }
        // Which of these you made and which a coding session made, since the folder name — a
        // generated slug like "copy-branch-names-clipboard-84cbcf" — can't tell you.
        if (wt.agent) {
            HoverTip(
                "Created by a Claude Code session, under the repo's .claude/worktrees/. Sessions " +
                    "offer to remove theirs on exit; the ones kept stay here, holding whatever was " +
                    "left in them.",
            ) { BranchBadge("agent", state.accent, Tokens.tintBlue) }
        }
        if (wt.branchMerged) BranchBadge("merged", Tokens.purple, Tokens.tintPurple)
    }
}

@Composable
private fun WorktreeChangeRows(changes: List<WorktreeChange>) {
    if (changes.isEmpty()) {
        Txt("Nothing uncommitted in this worktree", 11.5.sp, Tokens.muted2)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        changes.forEach { c ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusDot(if (c.untracked) Tokens.untrackedDot else Tokens.modifiedDot, 7)
                PathTip(c.path, Modifier.weight(1f)) {
                    Txt(c.path, 11.5.sp, Tokens.text2, font = MonoFont, maxLines = 1)
                }
                // Untracked files have no diffstat to show — git has nothing to compare them
                // against — so the row says which kind of nothing that is.
                if (c.untracked) {
                    Txt("untracked", 10.5.sp, Tokens.muted2)
                } else {
                    Txt("+${c.added}", 10.5.sp, Tokens.addFg, FontWeight.SemiBold, font = MonoFont)
                    Txt("−${c.deleted}", 10.5.sp, Tokens.delFg, FontWeight.SemiBold, font = MonoFont)
                }
            }
        }
    }
}

@Composable
private fun WorktreeLogRows(state: AppState, wt: Worktree) {
    if (state.wtCommits.isEmpty()) {
        Txt("No commits", 11.5.sp, Tokens.muted2)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        state.wtCommits.take(WT_LOG_ROWS).forEach { c ->
            Row(
                Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand)
                    .onTap { state.openCommitDiff(wt.path, c.fullHash, c.shortHash) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Txt(c.shortHash, 10.5.sp, Tokens.muted2, font = MonoFont)
                Txt(c.subject, 11.5.sp, Tokens.text2, maxLines = 1, modifier = Modifier.weight(1f))
                Txt(c.relDate, 10.5.sp, Tokens.muted2, maxLines = 1)
            }
        }
        // The overlay is the place for a full history; this is a peek, and a peek that grew to
        // fifty rows would push the Branches section off the pane.
        if (state.wtCommits.size > WT_LOG_ROWS) {
            FlatAction("Open full log →", size = 11.5.sp) { state.openWorktreeLog(wt.path) }
        }
    }
}

/** Commits shown inline on a worktree card before deferring to the log overlay. */
private const val WT_LOG_ROWS = 6

// ---------- alerts (§3b) ----------

/**
 * The `Alerts ▾` action and the popover it opens.
 *
 * An anchored popover rather than a modal, because it is a set of preset pills and nothing else:
 * every click applies instantly, so there is nothing to confirm and no reason to take over the
 * window. Esc or a click anywhere else closes it, the way the pane's other pill groups need no
 * closing at all.
 */
@Composable
private fun AlertsAnchor(state: AppState, rv: RepoView, wtv: WorktreeView) {
    var open by remember(wtv.path) { mutableStateOf(false) }
    val below = with(LocalDensity.current) { 20.dp.roundToPx() }
    Box {
        RowAction(if (wtv.overridden) "Alerts ▾ ⚙" else "Alerts ▾") { open = !open }
        if (open) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, below),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) { AlertsCard(state, rv, wtv) { open = false } }
        }
    }
}

@Composable
private fun AlertsCard(state: AppState, rv: RepoView, wtv: WorktreeView, close: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val parent = rv.repo.name
    val overrides = wtv.alerts.overridden
    Column(
        Modifier.width(340.dp).shadow(10.dp, shape).clip(shape)
            .background(Tokens.surface, shape).border(1.dp, Tokens.borderDc, shape)
            .dismissOnEscape(close)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Txt("Alerts — ⑂ ${wtv.label}", 12.5.sp, Tokens.text, FontWeight.Bold, maxLines = 1)
            Txt(
                if (overrides.isEmpty()) "Inherits from $parent unless overridden here."
                else "${overrides.size} override${if (overrides.size == 1) "" else "s"} · everything else follows $parent.",
                11.sp, Tokens.muted2, maxLines = 2,
            )
        }
        // While snoozed the controls stay live but recede: the snooze is the answer in force, and
        // editing an inheritance rule underneath it is a legitimate thing to do — it just won't
        // change anything until the snooze ends. Same treatment as the repo-level snooze banner.
        if (wtv.snoozed) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Tokens.snoozeBannerBg)
                    .border(1.dp, Tokens.snoozeBannerBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Txt("☾ Snoozed for ${wtv.snoozedFor ?: "a while"}", 11.5.sp, Tokens.snoozeBannerText,
                    FontWeight.SemiBold, modifier = Modifier.weight(1f))
                FlatAction("Resume now", size = 11.5.sp) { state.setWorktreeSnooze(rv.id, wtv.path, null) }
            }
        }
        WorktreeAlert.entries.forEach { alert ->
            val override = wtv.alerts[alert]
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(alert.label, 11.5.sp, Tokens.text2, FontWeight.Medium, modifier = Modifier.width(104.dp))
                // The effective parent value is spelled out on the Inherit pill so the answer is
                // readable without opening anything else — "Inherit" alone tells you the rule and
                // not the outcome, and the outcome is the question.
                PresetPill(
                    "Inherit · ${if (wtv.parentAlertsOn) "on" else "off"}",
                    override == null, state.accent, dimmed = wtv.snoozed,
                ) { state.setWorktreeAlert(rv.id, wtv.path, alert, null) }
                PresetPill("On", override == true, state.accent, dimmed = wtv.snoozed) {
                    state.setWorktreeAlert(rv.id, wtv.path, alert, true)
                }
                PresetPill("Off", override == false, state.accent, dimmed = wtv.snoozed) {
                    state.setWorktreeAlert(rv.id, wtv.path, alert, false)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val shapeS = RoundedCornerShape(8.dp)
            Box(
                Modifier.clip(shapeS).background(Tokens.snoozeBtnBg, shapeS)
                    .border(1.dp, Tokens.snoozeBtnBorder, shapeS)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .onTap { close(); state.popup = AppPopup.SnoozeWorktree(rv.id, wtv.path, wtv.label) }
                    .padding(horizontal = 11.dp, vertical = 5.dp),
            ) { Txt("☾ Snooze this worktree ▾", 11.5.sp, Tokens.snoozeBtnText, FontWeight.SemiBold) }
            Spacer(Modifier.weight(1f))
            // Only while there is something to reset — an always-present Reset on a worktree that
            // inherits everything would be a button whose only possible effect is nothing.
            if (overrides.isNotEmpty()) {
                FlatAction("Reset to inherit", size = 11.5.sp) { state.resetWorktreeAlerts(rv.id, wtv.path) }
            }
        }
    }
}

/**
 * A worktree's snooze state as a chip, with a ⚙ when it answers for itself on anything.
 *
 * Status, not a button — the same call [SnoozeToggle] makes for a repo row. Snoozing happens in the
 * alerts popover, where it sits next to the inheritance rules it interacts with, and the tooltip
 * here says so rather than leaving a moon glyph looking like a mystery action.
 */
@Composable
private fun WorktreeSnoozeChip(state: AppState, rv: RepoView, wtv: WorktreeView, size: Int = 27) {
    val shape = RoundedCornerShape(7.dp)
    val on = wtv.snoozed
    val gear = wtv.alerts.overridden.isNotEmpty()
    val label = buildString {
        append("☾")
        if (on) wtv.snoozedFor?.let { append(" $it") }
        if (gear) append(" ⚙")
    }
    val tip = if (wtv.overridden) {
        wtv.overrideSummary(rv.repo.name) + ". Open “Alerts ▾” on this worktree to change it."
    } else {
        "This worktree follows ${rv.repo.name}'s alerts. Use “Alerts ▾” on it to override or snooze " +
            "just this one."
    }
    HoverTip(tip) {
        Box(
            Modifier.height(size.dp).widthIn(min = size.dp).clip(shape)
                .background(if (on) Tokens.snoozeChipBg else Tokens.surface, shape)
                .border(1.dp, if (on) Tokens.snoozeChipBorder else Tokens.borderDc, shape)
                .padding(horizontal = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Txt(label, 11.sp, if (on) Tokens.snoozeChipText else Tokens.secondary, FontWeight.SemiBold)
        }
    }
}

// ---------- shared bits ----------

/**
 * `git worktree remove` deliberately keeps the branch, which is right for a worktree you made — but
 * an agent worktree's `claude/…` branch existed only to hold that session's work, so once it has
 * landed the branch is residue that "Remove" alone leaves behind forever. Offered as its own action
 * rather than folded into Remove: the plain one promises the branch survives, and a button that
 * quietly stopped honouring that promise would be the worse design.
 */
private fun canRemoveWithBranch(wt: Worktree) =
    wt.agent && wt.branchMerged && wt.branch != null && !wt.missing

private fun confirmRemove(state: AppState, rv: RepoView, wt: Worktree, alsoBranch: Boolean) {
    val branch = wt.branch?.takeIf { alsoBranch }
    state.popup = AppPopup.Confirm(
        if (branch != null) "Remove worktree “${wt.branch}” and its branch?" else "Remove worktree “${wt.branch ?: wt.name}”?",
        removeWorktreeDetail(wt, alsoBranch = branch),
        if (branch != null) "Remove both" else "Remove", danger = true,
    ) { state.removeWorktree(rv.id, wt, removeBranch = alsoBranch) }
}

/**
 * What removing this worktree actually costs, in the order it matters: the folder goes, anything
 * uncommitted in it goes with it, and — the reassurance that stops this reading as "delete my
 * branch" — the branch itself stays. Each clause is conditional so the dialog only ever claims what's
 * true of *this* worktree.
 */
private fun removeWorktreeDetail(wt: Worktree, alsoBranch: String? = null): String = buildString {
    // The dialog clamps to three lines, so this names the folder rather than spelling out its path —
    // the full path is on the row the Remove action sits in, and an absolute path here would eat two
    // of those lines and push the consequences off the bottom.
    append("Deletes the folder “${wt.name}” and everything in it.")
    if (wt.dirtyCount > 0) {
        append(" ${wt.dirtyCount} uncommitted change${if (wt.dirtyCount == 1) "" else "s"} there will be lost.")
    }
    if (wt.locked) append(" It's locked — removing overrides that.")
    // The reassurance flips into the extra consequence when the branch goes too; naming it as
    // already-merged is what makes that safe to agree to at a glance.
    if (alsoBranch != null) append(" The branch “$alsoBranch” is deleted too — it's already merged.")
    else wt.branch?.let { append(" The branch “$it” is kept.") }
}

/** "→ ~/work/checkout-service-retry · 2 hours ago · ↑2 vs main" — the card's meta line. */
private fun metaLine(wtv: WorktreeView): String = buildString {
    append("→ ${shortPath(wtv.path)}")
    wtv.wt.lastRelative.takeIf { it.isNotEmpty() }?.let { append(" · $it") }
    wtv.vsMainline?.let { append(" · $it") }
    if (wtv.wt.missing) append(" · folder gone")
}

private fun lastCommitLine(wt: Worktree): String = when {
    wt.missing -> "folder no longer on disk"
    wt.lastRelative.isEmpty() -> "—"
    wt.lastAuthor.isEmpty() -> wt.lastRelative
    else -> "${wt.lastRelative} · ${wt.lastAuthor}"
}

/** Git's relative date compacted to the strip's width — "2 hours ago" reads as "2h". */
private fun compactAgo(wt: Worktree): String? =
    wt.lastEpoch?.let { Meta.compactDuration(System.currentTimeMillis() - it * 1000) }

/** An absolute path with `$HOME` folded back to `~`, which is how the user thinks of it. */
private fun shortPath(path: String): String {
    val home = System.getProperty("user.home").orEmpty()
    return if (home.isNotEmpty() && path.startsWith(home)) "~" + path.removePrefix(home) else path
}
