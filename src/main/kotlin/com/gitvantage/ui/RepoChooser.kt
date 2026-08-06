// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gitvantage.app.AppState
import com.gitvantage.app.MonoFont
import com.gitvantage.app.Tokens
import com.gitvantage.app.UiFont
import com.gitvantage.model.RepoCandidate

/**
 * "Add repo" step 2: a modal overlay listing the git repos discovered beneath the
 * parent folder the user picked (FileKit gives us one directory; we fan out here).
 * The user ticks which repos to track and confirms. Rendered on top of the whole app
 * (see [GitVantageApp]); only shown while [AppState.chooserOpen] is true.
 */
@Composable
fun RepoChooserOverlay(state: AppState) {
    // Scrim — click outside the card to dismiss.
    Box(
        Modifier.fillMaxSize().background(Tokens.scrimSoft).onTap { state.cancelChooser() },
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(14.dp)
        // The card itself: swallow taps so clicks inside don't dismiss.
        Column(
            Modifier.width(560.dp).heightIn(max = 620.dp)
                .clip(shape).background(Tokens.surface, shape)
                .border(1.dp, Tokens.borderDc, shape)
                .onTap { },
        ) {
            Header(state)
            when {
                state.chooserScanning -> CenteredNote("Scanning for git repositories…")
                state.chooserCandidates.isEmpty() -> CenteredNote(
                    "No git repositories found directly under this folder.",
                )
                else -> CandidateList(state)
            }
            Footer(state)
        }
    }
}

@Composable
private fun Header(state: AppState) {
    val found = state.chooserCandidates.size
    Column(
        Modifier.fillMaxWidth().background(Tokens.panelFb)
            .drawBottomBorder(Tokens.borderEd)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Txt("Add repositories", 15.sp, Tokens.text, FontWeight.Bold)
        Txt(state.chooserParent, 12.sp, Tokens.muted2, font = MonoFont)
        if (!state.chooserScanning) {
            Txt(
                if (found == 0) "Nothing to add" else "$found git ${if (found == 1) "repo" else "repos"} found",
                11.5.sp, Tokens.muted,
            )
        }
    }
}

@Composable
private fun CenteredNote(text: String) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 140.dp).padding(40.dp),
        contentAlignment = Alignment.Center,
    ) { Txt(text, 13.sp, Tokens.muted2) }
}

// ColumnScope extension so the list Box below can use `weight` in the card's Column —
// that keeps the [Footer] pinned and visible no matter how many repos were discovered
// (the old fixed 420dp list could push the footer past the card's 620dp cap and clip it).
@Composable
private fun ColumnScope.CandidateList(state: AppState) {
    val visible = state.chooserVisible()
    val filtering = state.chooserFilter.isNotEmpty()
    // Drive the search box with a TextFieldValue (not a raw String): the String overload of
    // BasicTextField loses its cursor/selection when the composable recomposes heavily — and this
    // one recomposes on every keystroke as the candidate list re-filters — which resets the caret to
    // offset 0 so Backspace has nothing before it to delete (typing still appends, hence the
    // "backspace doesn't work" symptom). A TextFieldValue carries its own selection across those
    // recompositions. [state.chooserFilter] stays the filter source of truth, mirrored on each edit.
    var tfv by remember { mutableStateOf(TextFieldValue(state.chooserFilter)) }
    // Auto-focus on open so it's ready to type immediately.
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { searchFocus.requestFocus() }
    // Search field (filters by name or path)
    Row(
        Modifier.fillMaxWidth().drawBottomBorder(Tokens.rowBorder)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Txt("⌕", 13.sp, Tokens.muted2)
        Box(Modifier.weight(1f)) {
            if (!filtering) Txt("Filter by name or path…", 12.5.sp, Tokens.muted2)
            BasicTextField(
                value = tfv,
                onValueChange = { tfv = it; state.chooserFilter = it.text },
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.5.sp, color = Tokens.text, fontFamily = UiFont),
                cursorBrush = SolidColor(state.accent),
                modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
            )
        }
        if (filtering) Txt("✕", 12.sp, Tokens.muted2, modifier = Modifier.onTap {
            tfv = TextFieldValue(""); state.chooserFilter = ""
        })
    }
    // Selected count + select-all / none (fixed height)
    Row(
        Modifier.fillMaxWidth().drawBottomBorder(Tokens.rowBorder)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Txt(
            "${state.chooserSelected.size} selected" + if (filtering) " · ${visible.size} shown" else "",
            12.sp, Tokens.muted,
        )
        Spacer(Modifier.weight(1f))
        Txt(if (filtering) "Select shown" else "Select all", 12.sp, state.accent, FontWeight.SemiBold,
            modifier = Modifier.onTap { state.chooserSelectAll() })
        Txt("None", 12.sp, state.accent, FontWeight.SemiBold,
            modifier = Modifier.onTap { state.chooserSelectNone() })
    }
    // Scrollable list — takes the space remaining between the fixed toolbar and footer
    // (weight with fill = false so short lists stay compact), scrolling with a scrollbar.
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(30.dp), contentAlignment = Alignment.Center) {
                Txt("No repos match “${state.chooserFilter}”", 12.5.sp, Tokens.muted2)
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth(), state = listState) {
                items(visible, key = { it.path }) { c ->
                    CandidateRow(
                        candidate = c,
                        checked = c.path in state.chooserSelected,
                        onToggle = { state.toggleChooserSelection(c.path) },
                    )
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun CandidateRow(candidate: RepoCandidate, checked: Boolean, onToggle: () -> Unit) {
    val enabled = !candidate.alreadyTracked
    Row(
        Modifier.fillMaxWidth().drawBottomBorder(Tokens.rowBorder)
            .let { if (enabled) it.onTap(onToggle) else it }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SelectBox(checked = checked, enabled = enabled)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(candidate.name, 13.5.sp, if (enabled) Tokens.text else Tokens.muted2, FontWeight.Bold)
            Txt(candidate.path, 11.sp, Tokens.muted2, font = MonoFont)
        }
        if (candidate.alreadyTracked) {
            Pill(
                "Already tracked", Tokens.muted, Tokens.segTrack,
                fontSize = 10.sp, weight = FontWeight.SemiBold, radius = 10,
                padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 7.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun Footer(state: AppState) {
    val count = state.chooserSelected.size
    Row(
        Modifier.fillMaxWidth().background(Tokens.panelFa)
            .drawTopBorder(Tokens.borderEd)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.weight(1f))
        // Cancel
        Box(
            Modifier.clip(RoundedCornerShape(8.dp)).background(Tokens.surface)
                .border(1.dp, Tokens.borderD8, RoundedCornerShape(8.dp))
                .onTap { state.cancelChooser() }
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) { Txt("Cancel", 12.5.sp, Tokens.text, FontWeight.SemiBold) }
        // Confirm — disabled (greyed, inert) when nothing is checked
        val on = count > 0
        Box(
            Modifier.clip(RoundedCornerShape(8.dp))
                .background(if (on) Tokens.accentFill else Tokens.segTrack)
                .let { if (on) it.onTap { state.confirmChooser() } else it }
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Txt(
                if (count == 0) "Add repositories" else "Add $count ${if (count == 1) "repository" else "repositories"}",
                12.5.sp, if (on) Tokens.onAccent else Tokens.muted2, FontWeight.Bold,
            )
        }
    }
}
