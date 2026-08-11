// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gitvantage.app.AppState
import com.gitvantage.app.Emphasis
import com.gitvantage.app.MonoFont
import com.gitvantage.app.Primary
import com.gitvantage.app.RepoGroup
import com.gitvantage.app.RepoView
import com.gitvantage.app.Segment
import com.gitvantage.app.TagChip
import com.gitvantage.app.Tokens
import com.gitvantage.app.ViewMode

/** Left list body: switches between Table and Cards, or shows the empty state. */
@Composable
fun RepoListBody(state: AppState) {
    val groups = state.groups()
    if (state.filtered().isEmpty()) {
        EmptyState(registryEmpty = state.repos.isEmpty(), onAdd = state::pickAndAddRepo)
        return
    }
    when (state.view) {
        ViewMode.TABLE -> TableView(state, groups)
        ViewMode.CARDS -> CardsView(state, groups)
    }
}

/**
 * The centre-of-screen placeholder, in its two flavours: nothing tracked yet, and nothing matching
 * the current filters.
 *
 * The first flavour is a **button**, and it has to be. A large ＋ tile with "No repositories yet"
 * under it reads as the thing you press to get started — a first-run user reported pressing it,
 * finding it dead, and concluding the app's Add-repo flow was broken, without ever noticing the
 * real toolbar button. Wiring it to the same [AppState.pickAndAddRepo] costs nothing and removes
 * a first-run dead end.
 *
 * Only the tile and its label take the click, not the whole empty pane: a click target the size of
 * the window would fire a native folder dialog on any stray click into blank space.
 *
 * The "no matches" flavour stays inert — there is no single obvious action behind it (which filter
 * did you mean to clear?), so it explains instead.
 */
@Composable
private fun EmptyState(registryEmpty: Boolean, onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(60.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier
                .then(
                    if (registryEmpty) {
                        Modifier.clip(RoundedCornerShape(14.dp)).onTap(onAdd)
                            .pointerHoverIcon(PointerIcon.Hand).padding(16.dp)
                    } else {
                        Modifier
                    },
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Tokens.segTrack),
                contentAlignment = Alignment.Center,
            ) { Txt(if (registryEmpty) "＋" else "◍", 20.sp, Tokens.emptyGlyph) }
            if (registryEmpty) {
                Txt("No repositories yet", 15.sp, Tokens.secondary, FontWeight.Bold)
                Txt("Click here to pick a folder, or use + Add repo above.", 13.sp, Tokens.muted2)
            } else {
                Txt("No repositories match", 15.sp, Tokens.secondary, FontWeight.Bold)
                Txt("Try clearing a tag or status filter.", 13.sp, Tokens.muted2)
            }
        }
    }
}

// ---------- Table ----------

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TableView(state: AppState, groups: List<RepoGroup>) {
    LazyColumn(Modifier.fillMaxSize()) {
        groups.forEach { group ->
            if (group.showHeader) {
                stickyHeader(key = "h-${group.nsLabel}-${group.name}") { GroupHeaderTable(group) }
            }
            group.repos.forEach { rv ->
                item(key = "${group.name}-${rv.id}") { RepoRow(state, rv) }
            }
        }
    }
}

@Composable
private fun GroupHeaderTable(group: RepoGroup) {
    Row(
        Modifier.fillMaxWidth().background(Tokens.groupHeaderBg)
            .drawBottomBorder(Tokens.groupHeaderBorder)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Txt(group.name, 12.5.sp, Tokens.text, FontWeight.Bold)
        Txt(group.nsLabel, 11.sp, Tokens.muted2)
        Txt("· ${group.repos.size}", 11.5.sp, Tokens.muted2)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RepoRow(state: AppState, rv: RepoView) {
    val selected = rv.id == state.selectedId
    val checked = rv.id in state.bulkSelected
    val rowBg = when {
        selected -> state.accent.copy(alpha = 0x14 / 255f)
        checked -> state.accent.copy(alpha = 0x0A / 255f)   // subtle multi-select tint
        state.emphasis == Emphasis.LOUD && rv.attention -> rv.accentBg
        else -> Color.Transparent
    }
    val barW = if (state.emphasis == Emphasis.SUBTLE) 2f else 3f
    Row(
        Modifier.fillMaxWidth()
            .background(rowBg)
            .drawBottomBorder(Tokens.rowBorder)
            .drawLeftBar(rv.accent, barW)
            .onModifierClick(rv.id) { ctrl, shift -> state.clickRow(rv.id, ctrl, shift) }
            .padding(start = 11.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Col 0 — bulk-select checkbox (own tap target, so it doesn't open the detail panel)
        Box(Modifier.onTap { state.toggleBulk(rv.id) }.padding(vertical = 2.dp, horizontal = 1.dp)) {
            SelectBox(checked = checked)
        }
        // Col 1 — status dot
        Box(Modifier.width(12.dp), contentAlignment = Alignment.CenterStart) {
            StatusDot(rv.accent, 9)
        }
        // Name stays tight on the left; the badges and last-commit columns share the row's slack by
        // weight, so on wide windows the space sits as one balanced center gap rather than a single
        // huge empty box — and the last-commit column is wide enough to never truncate.
        // Col 2 — name / branch / tags
        Column(Modifier.width(262.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(rv.repo.name, 13.5.sp, Tokens.text, FontWeight.Bold)
            Txt("⎇ ${rv.repo.branch}", 11.5.sp, Tokens.muted, font = MonoFont)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                rv.tagChips.take(3).forEach { SmallTagChip(it) }
                if (rv.tagChips.size > 3) {
                    Txt("+${rv.tagChips.size - 3}", 10.sp, Tokens.muted2, FontWeight.SemiBold)
                }
            }
        }
        // Col 3 — badges (+ a "note" chip when the repo has a working note)
        Box(Modifier.weight(1f)) { Badges(rv.badges, hasNote = state.noteOf(rv.id).isNotBlank()) }
        // Col 4 — last commit: right-aligned so it sits beside the actions, and wide enough (weighted)
        // that the "… ago · author" is never truncated while there's whitespace to spare.
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Txt("${rv.repo.last} · ${rv.repo.author}", 12.sp, Tokens.muted, maxLines = 1)
        }
        // Col 6 — actions
        Row(
            Modifier.width(172.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            rv.primary?.let { PrimaryPill(it, small = false) }
            IconButton27(">_", 11, tip = TERMINAL_TIP) { state.openTerminal(rv.id) }
            SnoozeToggle(rv)
        }
    }
}

// ---------- Cards ----------

@Composable
private fun CardsView(state: AppState, groups: List<RepoGroup>) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 310.dp),
        modifier = Modifier.fillMaxSize().background(Tokens.panelF7),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        groups.forEach { group ->
            if (group.showHeader) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "h-${group.nsLabel}-${group.name}") {
                    GroupHeaderCards(group)
                }
            }
            group.repos.forEach { rv ->
                item(key = "${group.name}-${rv.id}") { RepoCard(state, rv) }
            }
        }
    }
}

@Composable
private fun GroupHeaderCards(group: RepoGroup) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Txt(group.name, 13.sp, Tokens.text, FontWeight.Bold)
        Txt("${group.nsLabel} · ${group.repos.size}", 11.sp, Tokens.muted2)
        Box(Modifier.weight(1f).height(1.dp).background(Tokens.groupHeaderBorder))
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RepoCard(state: AppState, rv: RepoView) {
    val selected = rv.id == state.selectedId
    val shape = RoundedCornerShape(10.dp)
    var m = Modifier.clip(shape)
    m = if (selected) m.border(2.dp, state.accent, shape)
    else m.shadow(1.dp, shape).border(1.dp, Tokens.borderE2, shape)
    m = m.background(Tokens.surface, shape).onModifierClick(rv.id) { ctrl, shift -> state.clickRow(rv.id, ctrl, shift) }
    Column(m) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(rv.accent))
        Column(
            Modifier.padding(start = 15.dp, end = 15.dp, top = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            // Header
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.onTap { state.toggleBulk(rv.id) }.padding(top = 2.dp)) {
                    SelectBox(checked = rv.id in state.bulkSelected)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Txt(rv.repo.name, 15.sp, Tokens.text, FontWeight.Bold)
                    Txt("⎇ ${rv.repo.branch}", 11.5.sp, Tokens.muted, font = MonoFont)
                }
                // ahead/behind now live as pills in the Badges row below (shared with the table view),
                // so the header no longer carries a separate arrow indicator.
            }
            // Tag chips (all)
            if (rv.tagChips.isNotEmpty()) {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) { rv.tagChips.forEach { CardTagChip(it) } }
            }
            // Change bar + status
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                ChangeBar(rv.segments)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt(rv.statusLabel, 12.sp, rv.accent, FontWeight.SemiBold)
                    rv.repo.dirtyFor?.let { Txt("· $it", 11.5.sp, Tokens.muted2) }
                }
            }
            // Badges (+ a "note" chip when the repo has a working note)
            val hasNote = state.noteOf(rv.id).isNotBlank()
            if (rv.badges.isNotEmpty() || hasNote) Badges(rv.badges, hasNote = hasNote)
            // Working note (live from the registry, so edits show without waiting for a rescan)
            val note = state.noteOf(rv.id)
            if (state.showNotes && note.isNotBlank()) {
                Box(
                    Modifier.clip(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                        .background(Tokens.panelFa)
                        .drawLeftBar(Tokens.borderDc, 2f)
                        .padding(start = 10.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
                ) { Txt(note, 12.sp, Tokens.muted, italic = true, maxLines = 3) }
            }
            // Footer
            Row(
                Modifier.fillMaxWidth().drawTopBorder(Tokens.rowBorder).padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Txt("${rv.repo.last} · ${rv.repo.author}", 11.5.sp, Tokens.muted2)
                Spacer(Modifier.weight(1f))
                rv.primary?.let { PrimaryPill(it, small = true) }
                IconButton27(">_", 10, size = 26, tip = TERMINAL_TIP) { state.openTerminal(rv.id) }
                SnoozeToggle(rv, size = 26)
            }
        }
    }
}

@Composable
private fun ChangeBar(segments: List<Segment>) {
    Row(
        Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(20.dp)).background(Tokens.changeTrack),
    ) {
        segments.forEach { seg ->
            Box(Modifier.weight(seg.fraction).height(6.dp).background(seg.color))
        }
    }
}

// ---------- shared small parts ----------

@Composable
fun SmallTagChip(chip: TagChip) {
    Pill(
        chip.label, chip.color, chip.bg, chip.border,
        fontSize = 10.sp, weight = FontWeight.SemiBold, radius = 10,
        padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
fun CardTagChip(chip: TagChip) {
    Pill(
        chip.label, chip.color, chip.bg, chip.border,
        fontSize = 10.5.sp, weight = FontWeight.SemiBold, radius = 12,
        padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
fun PrimaryPill(primary: Primary, small: Boolean) {
    Pill(
        primary.label, primary.color, primary.bg, primary.border,
        fontSize = 11.5.sp, weight = FontWeight.Bold, radius = 7,
        padding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = if (small) 10.dp else 11.dp, vertical = if (small) 4.dp else 5.dp,
        ),
    )
}

private const val TERMINAL_TIP =
    "Open a terminal in this repository's folder. Nothing is changed — it just launches your " +
        "terminal with the working directory set."

/** Small square icon button. Pass [tip] to explain the action on hover — these are glyph-only, so
 *  without it there's nothing telling the user what a click will do. */
@Composable
fun IconButton27(glyph: String, fontSize: Int, size: Int = 27, tip: String? = null, onClick: () -> Unit = {}) {
    val button: @Composable () -> Unit = {
        Box(
            Modifier.size(size.dp).clip(RoundedCornerShape(7.dp))
                .border(1.dp, Tokens.borderDc, RoundedCornerShape(7.dp))
                .pointerHoverIcon(PointerIcon.Hand).onTap(onClick),
            contentAlignment = Alignment.Center,
        ) { Txt(glyph, fontSize.sp, Tokens.secondary, font = MonoFont) }
    }
    if (tip != null) HoverTip(tip) { button() } else button()
}

/**
 * Snooze *status* for a repo — deliberately not a button: it shows whether alerts are silenced,
 * and the tooltip points at where snoozing is actually controlled (the detail panel), so the
 * moon glyph doesn't read as a mystery action.
 */
@Composable
fun SnoozeToggle(rv: RepoView, size: Int = 27) {
    val text = if (rv.snoozed) "☾ ${rv.repo.snoozedFor ?: "on"}" else "☾"
    val border = if (rv.snoozed) Tokens.snoozeChipBorder else Tokens.borderDc
    val color = if (rv.snoozed) Tokens.snoozeChipText else Tokens.secondary
    val bg = if (rv.snoozed) Tokens.snoozeChipBg else Tokens.surface
    val shape = RoundedCornerShape(7.dp)
    val tip = if (rv.snoozed) {
        "Alerts are silenced for this repo${rv.repo.snoozedFor?.let { " ($it)" } ?: ""}. " +
            "Open the repo and choose “Resume now” to re-enable them."
    } else {
        "Alerts are on for this repo. Open it and use “☾ Snooze” to silence them for a while."
    }
    HoverTip(tip) {
        Box(
            Modifier.height(size.dp).widthIn(min = size.dp).clip(shape)
                .background(bg, shape).border(1.dp, border, shape)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) { Txt(text, 11.5.sp, color, FontWeight.SemiBold) }
    }
}
