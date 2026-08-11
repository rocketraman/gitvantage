// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gitvantage.app.AppState
import com.gitvantage.app.GitHub
import com.gitvantage.app.MonoFont
import com.gitvantage.app.Popup
import com.gitvantage.app.Theme
import com.gitvantage.app.ThemeMode
import com.gitvantage.app.Tokens
import com.gitvantage.app.UiFont
import com.gitvantage.git.model.Commit
import com.gitvantage.model.Meta
import com.gitvantage.model.Reminder

/** Dispatches [AppState.popup] to the right modal. */
@Composable
fun PopupHost(state: AppState, popup: Popup) {
    when (popup) {
        is Popup.Tag -> TagPopup(state, popup.ids)
        is Popup.Untag -> UntagPopup(state, popup.ids)
        is Popup.Snooze -> SnoozePopup(state, popup.ids)
        is Popup.Remind -> RemindPopup(state, popup.ids, popup.text, popup.due)
        is Popup.Commit -> CommitPopup(state, popup.id)
        is Popup.Confirm -> ConfirmPopup(state, popup)
        is Popup.Appearance -> AppearancePopup(state)
    }
}

private fun noun(n: Int) = if (n == 1) "repo" else "repos"

// ---------- individual modals ----------

@Composable
private fun TagPopup(state: AppState, ids: Set<String>) {
    Modal({ state.popup = null }, width = 500) {
        ModalHeader("Tag ${ids.size} ${noun(ids.size)}", "Adds a tag to each selected repo. Use namespace:value, e.g. owner:me.")
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Same inline-ghost autocomplete as the detail-pane "+ Tag" (suggests across all repos).
            TagAutocompleteField(
                accent = state.accent,
                suggest = { state.tagSuggestions(it) },
                onCommit = { if (it.isNotBlank()) state.addTagToAll(ids, it); state.popup = null },
                onCancel = { state.popup = null },
                fieldWidth = 240.dp,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UntagPopup(state: AppState, ids: Set<String>) {
    val available = remember(ids) { state.tagsAcross(ids) }
    val selected = remember(ids) { mutableStateListOf<String>() }
    fun commit() {
        if (selected.isNotEmpty()) state.removeTagFromAll(ids, selected.toList())
        state.popup = null
    }
    Modal({ state.popup = null }, width = 440) {
        ModalHeader("Untag ${ids.size} ${noun(ids.size)}", "Pick one or more tags to remove from the selected repos.")
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (available.isEmpty()) {
                Txt("No tags on the selected ${noun(ids.size)}.", 12.5.sp, Tokens.muted)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    available.forEach { tag ->
                        val on = tag in selected
                        val shape = RoundedCornerShape(20.dp)
                        Row(
                            Modifier.clip(shape)
                                .background(if (on) Tokens.tintRed else Tokens.surface, shape)
                                .border(1.dp, if (on) Tokens.warnBorder else Tokens.borderE2, shape)
                                .onTap { if (on) selected.remove(tag) else selected.add(tag) }
                                .padding(horizontal = 11.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Txt(if (on) "✓" else "+", 12.sp, if (on) Tokens.redText else Tokens.muted2, FontWeight.Bold)
                            Txt(tag, 12.sp, if (on) Tokens.redText else Tokens.secondary, if (on) FontWeight.Bold else FontWeight.Medium)
                        }
                    }
                }
            }
            ModalButtons({ state.popup = null }, "Untag", selected.isNotEmpty(), true, ::commit)
        }
    }
}

@Composable
private fun SnoozePopup(state: AppState, ids: Set<String>) {
    Modal({ state.popup = null }) {
        ModalHeader("Snooze ${ids.size} ${noun(ids.size)}", "Silence attention flags (dirty/unpushed/stale) for a while.")
        Column(Modifier.padding(vertical = 6.dp)) {
            Meta.SNOOZE_PRESETS.forEach { (label, ms) ->
                MenuRow(label) {
                    val until = ms?.let { System.currentTimeMillis() + it } ?: Meta.SNOOZE_FOREVER
                    state.setSnoozeUntil(ids, until)
                    state.popup = null
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp).height(1.dp).background(Tokens.borderEd))
            MenuRow("Resume now (clear snooze)", accent = true) {
                state.setSnoozeUntil(ids, null)
                state.popup = null
            }
        }
    }
}

/**
 * The appearance picker: Match system / Light / Dark.
 *
 * A menu of the three modes rather than a light↔dark switch, because "follow the desktop" is a
 * distinct answer from either fixed choice and a two-state toggle has nowhere to put it.
 *
 * "Match system" is annotated with what the *desktop* currently asks for, so choosing it isn't a
 * leap of faith. That has to come from [Theme.systemDark] and not [Theme.isDark]: the latter is
 * the theme in force, so under a Light or Dark override it just reports the user's own choice
 * back at them — "Match system · dark" purely because dark is what's on screen. When the desktop
 * never answered (a session with no portal and no gsettings), the row says so rather than
 * quietly presenting light as the desktop's choice.
 */
@Composable
private fun AppearancePopup(state: AppState) {
    // The poller only runs under SYSTEM, so outside it the last reading can be arbitrarily old.
    // Re-read on open — this is the one moment the value is actually being shown to someone.
    LaunchedEffect(Unit) { Theme.refreshSystemPreferenceAsync() }
    Modal({ state.popup = null }) {
        ModalHeader("Appearance", "How GitVantage looks. Applies immediately and is remembered.")
        Column(Modifier.padding(vertical = 6.dp)) {
            ThemeMode.entries.forEach { m ->
                val on = m == Theme.mode
                val detail = when {
                    m != ThemeMode.SYSTEM -> null
                    !Theme.systemKnown -> "this desktop didn't say — using light"
                    Theme.systemDark -> "dark"
                    else -> "light"
                }
                Row(
                    Modifier.fillMaxWidth().onTap {
                        Theme.switchTo(m)
                        state.popup = null
                    }.padding(horizontal = 18.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Txt(m.glyph, 13.sp, if (on) Tokens.accent else Tokens.muted)
                    Txt(m.label, 13.sp, if (on) Tokens.accent else Tokens.text,
                        if (on) FontWeight.SemiBold else FontWeight.Normal)
                    detail?.let { Txt("· $it", 11.5.sp, Tokens.muted2) }
                    Spacer(Modifier.weight(1f))
                    if (on) Txt("✓", 13.sp, Tokens.accent, FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemindPopup(state: AppState, ids: Set<String>, initialText: String, initialDue: Long?) {
    var text by remember { mutableStateOf(initialText) }
    var dueIdx by remember { mutableStateOf(1) }   // default "Tomorrow"
    var custom by remember { mutableStateOf("") }  // a kotlin.time.Duration like 2m, 2h, 3d
    val hadReminder = initialText.isNotBlank()
    // A parseable custom period overrides the preset chip.
    val customMs: Long? = custom.trim().takeIf { it.isNotBlank() }
        ?.let { runCatching { kotlin.time.Duration.parse(it).inWholeMilliseconds }.getOrNull() }
    val customBad = custom.isNotBlank() && customMs == null
    fun commit() {
        val ms = customMs ?: Meta.REMINDER_PRESETS[dueIdx].second
        state.setReminder(ids, text, ms?.let { System.currentTimeMillis() + it })
        state.popup = null
    }
    Modal({ state.popup = null }, width = 440) {
        ModalHeader("Reminder for ${ids.size} ${noun(ids.size)}", "A short note plus an optional due date, shown as a badge.")
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextInput(text, { text = it }, "e.g. review PR, rebase onto main, follow up", state.accent, ::commit, { state.popup = null })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Meta.REMINDER_PRESETS.forEachIndexed { i, (label, _) ->
                    PresetChip(label, i == dueIdx && custom.isBlank(), state.accent) { dueIdx = i; custom = "" }
                }
            }
            // Custom period — takes precedence over the presets when set.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt("or in", 12.sp, Tokens.muted)
                Box(Modifier.width(120.dp)) {
                    TextInput(custom, { custom = it }, "2m · 2h · 3d", state.accent, ::commit, { state.popup = null })
                }
                if (customBad) Txt("unrecognized — try 2m, 2h, 3d", 11.sp, Tokens.amber)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (hadReminder) {
                    Txt("Clear reminder", 12.sp, Tokens.redText, FontWeight.SemiBold,
                        modifier = Modifier.onTap { state.clearReminder(ids); state.popup = null })
                }
                Spacer(Modifier.weight(1f))
                ModalButtons({ state.popup = null }, "Save", text.isNotBlank(), false, ::commit)
            }
        }
    }
}

@Composable
private fun CommitPopup(state: AppState, id: String) {
    val repo = state.repos.find { it.id == id }
    if (repo == null) { state.popup = null; return }
    var title by remember(id) { mutableStateOf("") }
    var body by remember(id) { mutableStateOf("") }
    var stageAll by remember(id) { mutableStateOf(repo.staged == 0) }   // nothing staged → stage all by default
    val titleLen = title.trim().length
    val titleLong = titleLen > 50
    val longBodyLines = body.lineSequence().count { it.length > 72 }
    fun commit() {
        if (title.isBlank()) return
        state.commit(id, title, body, stageAll)
        state.popup = null
    }
    val changes = buildList {
        if (repo.staged > 0) add("${repo.staged} staged")
        if (repo.unstaged > 0) add("${repo.unstaged} modified")
        if (repo.untracked > 0) add("${repo.untracked} untracked")
    }.joinToString(" · ").ifEmpty { "working tree clean" }

    Modal({ state.popup = null }, width = 640) {   // wide enough for the 72-col body guide
        ModalHeader("Commit — ${repo.name}", "⎇ ${repo.branch}   ·   $changes")
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Summary line
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextInput(title, { title = it }, "Summary (imperative, ≤50 chars)", state.accent, ::commit, { state.popup = null }, font = MonoFont, guideCol = 50)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (titleLong) Txt("Summary is long — GitHub truncates past 50 chars", 11.sp, Tokens.amber)
                    Spacer(Modifier.weight(1f))
                    Txt("$titleLen", 11.sp, if (titleLong) Tokens.amber else Tokens.muted2, font = MonoFont)
                }
            }
            // Body
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MultilineInput(body, { body = it }, "Extended description (optional) — wrap body lines at 72 chars", state.accent, guideCol = 72)
                if (longBodyLines > 0) {
                    Txt("$longBodyLines body ${if (longBodyLines == 1) "line" else "lines"} exceed 72 chars", 11.sp, Tokens.amber)
                }
            }
            // Stage-all toggle
            Row(
                Modifier.onTap { stageAll = !stageAll },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SelectBox(checked = stageAll)
                Txt("Stage all changes (git add -A) before committing", 12.sp, Tokens.text2)
            }
            ModalButtons({ state.popup = null }, "Commit", title.isNotBlank(), false, ::commit)
        }
    }
}

@Composable
private fun ConfirmPopup(state: AppState, p: Popup.Confirm) {
    Modal({ state.popup = null }) {
        ModalHeader(p.title, p.message)
        Box(Modifier.padding(18.dp)) {
            ModalButtons({ state.popup = null }, p.confirmLabel, true, p.danger) {
                state.popup = null
                p.onConfirm()
            }
        }
    }
}

// ---------- toast ----------

/** Transient op-result pill, bottom-center. The empty area is click-through. */
@Composable
fun Toast(state: AppState, msg: String) {
    Box(Modifier.fillMaxSize().padding(bottom = 24.dp), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).background(Tokens.tooltipBg)
                .onTap { state.dismissToast() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Txt(msg, 12.5.sp, Tokens.tooltipText, FontWeight.Medium)
            Txt("✕", 11.sp, Tokens.tooltipText.copy(alpha = 0.6f))
        }
    }
}

// ---------- shared building blocks ----------

@Composable
private fun Modal(onDismiss: () -> Unit, width: Int = 380, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Tokens.scrimSoft).onTap(onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(14.dp)
        Column(
            Modifier.width(width.dp).clip(shape).background(Tokens.surface, shape)
                .border(1.dp, Tokens.borderDc, shape).onTap { },
            content = content,
        )
    }
}

@Composable
private fun ModalHeader(title: String, subtitle: String) {
    Column(
        Modifier.fillMaxWidth().background(Tokens.panelFb).drawBottomBorder(Tokens.borderEd)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Txt(title, 15.sp, Tokens.text, FontWeight.Bold)
        Txt(subtitle, 11.5.sp, Tokens.muted, maxLines = 3)
    }
}

@Composable
private fun TextInput(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    accent: Color,
    onDone: () -> Unit,
    onEscape: () -> Unit,
    font: FontFamily = UiFont,
    guideCol: Int? = null,
) {
    val style = TextStyle(fontSize = 12.5.sp, color = Tokens.text, fontFamily = font)
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Tokens.surface)
            .border(1.dp, Tokens.borderD8, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .columnGuide(guideCol, style),
    ) {
        if (value.isEmpty()) Txt(placeholder, 12.5.sp, Tokens.muted2)
        val edit = rememberTextEdit(value)
        BasicTextField(
            value = edit.value,
            onValueChange = { edit.value = it; onValue(it.text) },
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(accent),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth()
                .selectAllOnDoubleClick { edit.selectAll() }
                .onPreviewKeyEvent {
                    if (it.key == Key.Escape) { onEscape(); true } else false
                },
        )
    }
}

/** Draw a thin, near-transparent vertical guide at monospace column [col] (content coords). */
@Composable
private fun Modifier.columnGuide(col: Int?, style: TextStyle): Modifier {
    if (col == null) return this
    val measurer = rememberTextMeasurer()
    return this.drawBehind {
        val x = measurer.measure("0".repeat(col), style).size.width.toFloat()
        drawLine(Tokens.hairline, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
    }
}

@Composable
private fun MultilineInput(value: String, onValue: (String) -> Unit, placeholder: String, accent: Color, guideCol: Int? = null) {
    val style = TextStyle(fontSize = 12.5.sp, color = Tokens.text, fontFamily = MonoFont)
    Box(
        Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(8.dp)).background(Tokens.surface)
            .border(1.dp, Tokens.borderD8, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .columnGuide(guideCol, style),
    ) {
        if (value.isEmpty()) Txt(placeholder, 12.5.sp, Tokens.muted2, maxLines = 2)
        // No selectAllOnDoubleClick here, unlike every single-line field: a commit body is prose
        // several lines long, and double-clicking a word to retype it is the normal thing to do in
        // it. Selecting the whole message on that gesture would put the next keystroke one
        // character away from discarding everything typed so far.
        val edit = rememberTextEdit(value)
        BasicTextField(
            value = edit.value,
            onValueChange = { edit.value = it; onValue(it.text) },
            textStyle = style,
            cursorBrush = SolidColor(accent),
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun ModalButtons(
    cancel: () -> Unit,
    confirmLabel: String,
    confirmEnabled: Boolean,
    danger: Boolean,
    onConfirm: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.clip(RoundedCornerShape(8.dp)).background(Tokens.surface)
                .border(1.dp, Tokens.borderD8, RoundedCornerShape(8.dp))
                .onTap(cancel).padding(horizontal = 14.dp, vertical = 7.dp),
        ) { Txt("Cancel", 12.5.sp, Tokens.text, FontWeight.SemiBold) }
        val bg = when {
            !confirmEnabled -> Tokens.segTrack
            danger -> Tokens.dangerFill
            else -> Tokens.accentFill
        }
        Box(
            Modifier.clip(RoundedCornerShape(8.dp)).background(bg)
                .let { if (confirmEnabled) it.onTap(onConfirm) else it }
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) { Txt(confirmLabel, 12.5.sp, if (confirmEnabled) Tokens.onAccent else Tokens.muted2, FontWeight.Bold) }
    }
}

@Composable
private fun MenuRow(label: String, accent: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().onTap(onClick).padding(horizontal = 18.dp, vertical = 9.dp)) {
        Txt(label, 13.sp, if (accent) Tokens.accent else Tokens.text, if (accent) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun PresetChip(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.clip(shape)
            .background(if (selected) Tokens.tintBlue else Tokens.surface, shape)
            .border(1.dp, if (selected) Tokens.accentBorder else Tokens.borderE2, shape)
            .onTap(onClick).padding(horizontal = 11.dp, vertical = 5.dp),
    ) {
        Txt(label, 12.sp, if (selected) accent else Tokens.secondary, if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}
