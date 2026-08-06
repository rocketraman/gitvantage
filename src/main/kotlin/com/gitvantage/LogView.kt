// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Commit-list overlay for an arbitrary revision range (see [AppState.openRangeLog]). Each row
 * shows the short hash, subject, author · relative date, and any additional message body.
 * Closes on Escape (the root re-grabs focus after every click, like [DiffView]).
 */
@Composable
fun LogView(state: AppState) {
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    // Re-grab focus whenever the diff overlay (which layers on top for a commit's diff) closes, so
    // Escape keeps closing the log afterward — not just on first open.
    LaunchedEffect(state.diffOpen) { if (!state.diffOpen) runCatching { focus.requestFocus() } }
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.fillMaxSize().background(Tokens.scrim).onTap { }
            .focusRequester(focus).focusable()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Final)
                        if (e.type == PointerEventType.Release) runCatching { focus.requestFocus() }
                    }
                }
            }
            .onPreviewKeyEvent {
                if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { state.closeLog(); true } else false
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth(0.66f).fillMaxHeight().padding(24.dp)
                .clip(shape).background(Tokens.surface, shape).border(1.dp, Tokens.borderDc, shape),
        ) {
            Row(
                Modifier.fillMaxWidth().background(Tokens.panelFb).drawBottomBorder(Tokens.borderEd)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Txt(state.logTitle, 14.sp, Tokens.text, FontWeight.Bold)
                Txt("— ${state.logRepoName}", 13.sp, Tokens.muted2)
                Spacer(Modifier.weight(1f))
                if (!state.logLoading && state.logCommits.isNotEmpty()) {
                    val n = state.logCommits.size
                    Txt("$n ${if (n == 1) "commit" else "commits"}", 12.sp, Tokens.muted2)
                }
                Txt("Esc", 11.sp, Tokens.muted2)
                Box(
                    Modifier.size(26.dp).clip(RoundedCornerShape(50)).background(Tokens.segTrack).onTap { state.closeLog() },
                    contentAlignment = Alignment.Center,
                ) { Txt("×", 14.sp, Tokens.secondary) }
            }
            when {
                state.logLoading -> CenteredLogMessage("Loading log…")
                state.logCommits.isEmpty() -> CenteredLogMessage("No commits to show.")
                else -> Box(Modifier.fillMaxWidth().weight(1f)) {
                    LazyColumn(Modifier.fillMaxSize(), state = listState) {
                        items(state.logCommits.size) { i -> CommitRow(state, state.logCommits[i]) }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(listState),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CommitRow(state: AppState, c: LogOps.Commit) {
    val accent = state.accent
    // Only worth offering for commits the remote actually has: a link to a hash that was never
    // pushed lands on GitHub's 404, so the button would be a promise the row can't keep.
    val webBase = state.logWebBase?.takeIf { c.pushed }
    // Held behind hover for the reason the branch and worktree lists are — see [HoverRow]. A log is
    // the longest of these lists, so a permanent pair of pills on every row is the worst offender.
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Column(
        Modifier.fillMaxWidth().background(if (hovered) Tokens.rowHoverBg else Color.Transparent)
            .drawBottomBorder(Tokens.rowBorder).hoverable(source)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt(c.shortHash, 12.sp, accent, FontWeight.SemiBold, font = MonoFont)
            // Refs first, so the eye lands on "where am I / what's tagged" before the message.
            // FlowRow because a commit can carry several (HEAD + branch + remote + tags).
            if (c.refs.isNotEmpty()) {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) { c.refs.forEach { RefChip(it, accent) } }
            }
            Txt(c.subject, 13.sp, Tokens.text, FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 2)
            // Per-commit actions: view its diff, and (GitHub remotes) open the commit on the web.
            // Faded rather than removed when the row isn't hovered, so the subject keeps the same
            // width either way — a subject that reflowed from two lines to one under the pointer
            // would shrink the row and shove the rows below it out from under the cursor. Nothing
            // can be clicked while invisible: the pointer being over a pill *is* what reveals it.
            Row(
                Modifier.alpha(if (hovered) 1f else 0f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LogPill("Diff", accent) { state.openCommitDiff(state.logRepoId, c.fullHash, c.shortHash) }
                if (webBase != null) LogPill("↗ GitHub", accent) { state.openUrl("$webBase/commit/${c.fullHash}") }
            }
        }
        // Merge commits are where history actually branches, and a flat list hides that entirely.
        // Surfacing the parents (and diffing against each) covers what you'd normally go to a
        // commit-graph view for: "what did this merge bring in, and from where".
        //
        // Only merges get this treatment — on an ordinary commit the sole parent is just the next
        // row down, so showing it would be noise on every line.
        if (c.isMerge) {
            Row(
                Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    Modifier.clip(RoundedCornerShape(4.dp)).background(Tokens.tintPurple, RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) { Txt("⑃ merge of ${c.parents.size}", 10.sp, Tokens.purple, FontWeight.Bold) }
                c.parents.forEachIndexed { i, parent ->
                    // Diffing the merge against parent N is what shows the side that came in:
                    // against parent 1 (the branch you were on) you see everything merged in.
                    HoverTip(
                        "What ${c.shortHash} changed relative to parent ${i + 1} (${parent.shortHash}) — " +
                            "for a merge, that's the work the other side brought in.",
                    ) {
                        LogPill("p${i + 1} ${parent.shortHash}", accent) {
                            state.openRangeDiff(
                                state.logRepoId, parent.fullHash, c.fullHash,
                                "Merge ${c.shortHash} vs parent ${i + 1} (${parent.shortHash})",
                                "${parent.shortHash} → ${c.shortHash}",
                            )
                        }
                    }
                }
            }
        }
        Txt("${c.author} · ${c.relDate}", 11.5.sp, Tokens.muted2)
        if (c.body.isNotBlank()) {
            Txt(c.body, 11.5.sp, Tokens.muted, font = MonoFont, modifier = Modifier.padding(top = 2.dp), maxLines = 8)
        }
    }
}

/**
 * A branch/tag pointing at this commit. Colour carries the kind so the list is scannable without
 * reading every label: green = where HEAD is, blue = local branch, grey = remote, amber = tag.
 */
@Composable
private fun RefChip(ref: LogOps.Ref, accent: Color) {
    val shape = RoundedCornerShape(4.dp)
    val (fg, bg) = when (ref.kind) {
        LogOps.RefKind.HEAD -> Tokens.cleanText to Tokens.cleanBg
        LogOps.RefKind.LOCAL_BRANCH -> accent to Tokens.tintBlue
        LogOps.RefKind.REMOTE_BRANCH -> Tokens.muted to Tokens.segTrack
        LogOps.RefKind.TAG -> Tokens.amber to Tokens.tintAmber
    }
    // The glyph distinguishes tags from branches even in a screenshot or for a colour-blind reader.
    val glyph = if (ref.kind == LogOps.RefKind.TAG) "⌂ " else "⎇ "
    Box(
        Modifier.clip(shape).background(bg, shape).padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Txt(
            if (ref.kind == LogOps.RefKind.HEAD) ref.label else "$glyph${ref.label}",
            10.sp, fg, FontWeight.Bold, font = MonoFont, maxLines = 1,
        )
    }
}

@Composable
private fun LogPill(label: String, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(Tokens.tintBlue)
            .border(1.dp, Tokens.accentBorder, RoundedCornerShape(6.dp))
            .pointerHoverIcon(PointerIcon.Hand).onTap(onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) { Txt(label, 10.5.sp, accent, FontWeight.SemiBold) }
}

@Composable
private fun CenteredLogMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Txt(text, 13.sp, Tokens.muted2) }
}
