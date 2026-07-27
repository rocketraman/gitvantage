// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DetailPanel(state: AppState, rv: RepoView) {
    val repo = rv.repo
    val staged = repo.files.filter { it.section == "staged" }
    val modified = repo.files.filter { it.section == "unstaged" }
    val untracked = repo.files.filter { it.section == "untracked" }
    val selClean = repo.files.isEmpty() && repo.stashes.isEmpty() && repo.warning == null

    Row(Modifier.width(state.detailPaneWidth.dp).fillMaxHeight()) {
        // Drag handle on the left edge — resize the pane and remember the width. Wider than
        // it looks so it's easy to grab; a faint grip line marks it.
        Box(
            Modifier.width(12.dp).fillMaxHeight().drawLeftBar(Tokens.borderE6, 1f)
                .pointerHoverIcon(PointerIcon(java.awt.Cursor(java.awt.Cursor.W_RESIZE_CURSOR)))
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = { state.persistDetailPaneWidth() },
                    ) { _, dragAmount -> state.resizeDetailPane(state.detailPaneWidth - dragAmount.toDp().value) }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(2.dp).fillMaxHeight(0.12f).clip(RoundedCornerShape(1.dp)).background(Tokens.borderDc))
        }
        Column(
            Modifier.weight(1f).fillMaxHeight()
                .background(Color.White)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusDot(rv.accent, 11)
            Txt(repo.name, 20.sp, Tokens.text, FontWeight.Bold, modifier = Modifier.weight(1f))
            // Manual refresh — re-scan with fetch, reload branches + submodules. A safety net for
            // any external change the filesystem watcher didn't catch (e.g. remote advances).
            Box(
                Modifier.size(26.dp).clip(RoundedCornerShape(50)).background(Tokens.segTrack)
                    .pointerHoverIcon(PointerIcon.Hand).onTap { state.refreshRepo(repo.id) },
                contentAlignment = Alignment.Center,
            ) { Txt(if (state.refreshingId == repo.id) "⟳" else "↻", 14.sp, state.accent) }
            Box(
                Modifier.size(26.dp).clip(RoundedCornerShape(50)).background(Tokens.segTrack)
                    .onTap { state.selectedId = null },
                contentAlignment = Alignment.Center,
            ) { Txt("×", 14.sp, Tokens.secondary) }
        }

        // Branch line
        Row(
            Modifier.padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Txt("⎇ ${repo.branch} → ${rv.upstream}", 12.sp, Tokens.secondary, font = MonoFont)
            if (rv.ahead > 0) Txt("↑${rv.ahead} ahead", 12.sp, state.accent, FontWeight.SemiBold, font = MonoFont)
            if (rv.behind > 0) Txt("↓${rv.behind} behind", 12.sp, Tokens.behind, FontWeight.SemiBold, font = MonoFont)
        }

        // (Behind-upstream Diff/Log now live in the BehindBanner below, next to the
        // "N commits behind" message — where the eye lands looking for them.)

        // If this repo is itself a submodule of a parent that's also tracked, link to it.
        repo.superproject?.let { sup ->
            state.trackedRepoAt(sup)?.let { parentId ->
                Row(
                    Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).background(Tokens.tintBlue)
                        .pointerHoverIcon(PointerIcon.Hand).onTap { state.selectedId = parentId }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Txt("⤴ Submodule of", 11.5.sp, state.accent, FontWeight.SemiBold)
                    Txt(java.io.File(parentId).name, 11.5.sp, state.accent, FontWeight.Bold, font = MonoFont)
                }
            }
        }

        // Tags
        TagEditor(state, rv)

        // Primary actions (FlowRow so they wrap as the panel narrows)
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Commit opens a commit dialog (branch + message). Push/Fetch/Fast-forward all
            // need a remote — but Push works even without a tracking upstream (it pushes with
            // `-u origin HEAD`), so it's gated on hasRemote, not on upstream. Fetch just needs
            // the remote (offline/no-upstream is fine).
            ActionButton("Commit…", Color.White, state.accent, null, disabled = !repo.isGitRepo) {
                state.popup = Popup.Commit(repo.id)
            }
            ActionButton("Push", Tokens.text2, Color.White, Tokens.borderD8, disabled = !repo.hasRemote) {
                state.popup = Popup.Confirm(
                    "Push ${repo.name}?",
                    if (repo.upstream != null) "Runs git push to ${repo.upstream}."
                    else "Publishes “${repo.branch}” to origin and sets it as the upstream.",
                    "Push", danger = false,
                ) { state.push(listOf(repo.id)) }
            }
            ActionButton("Fetch", Tokens.text2, Color.White, Tokens.borderD8, disabled = !repo.hasRemote) {
                state.fetchRepo(repo.id)
            }
            // Fast-forward is only possible when strictly behind upstream (no local commits).
            val canFf = repo.upstream != null && repo.behind > 0 && repo.ahead == 0
            ActionButton("Fast Forward", Tokens.text2, Color.White, Tokens.borderD8, disabled = !canFf) {
                state.popup = Popup.Confirm(
                    "Fast-forward ${repo.name}?",
                    "Advances “${repo.branch}” to ${repo.upstream} (${repo.behind} commit${if (repo.behind == 1) "" else "s"}). No local commits are lost.",
                    "Fast Forward", danger = false,
                ) { state.fastForward(repo.id) }
            }
            val hasChanges = repo.staged + repo.unstaged + repo.untracked > 0
            ActionButton("Diff", Tokens.text2, Color.White, Tokens.borderD8, disabled = !hasChanges) {
                state.openDiff(repo.id)
            }
            ActionButton("Log", Tokens.text2, Color.White, Tokens.borderD8, disabled = !repo.isGitRepo) {
                state.openRepoLog(repo.id)
            }
            ActionButton("☾ Snooze ▾", Tokens.snoozeBtnText, Tokens.snoozeBtnBg, Tokens.snoozeBtnBorder) {
                state.popup = Popup.Snooze(setOf(repo.id))
            }
        }

        // Open in
        OpenInGroup(state, repo)

        // Banners. Behind-upstream takes its own banner (with Diff/Log) so it shows whether the tree
        // is clean or dirty; the clean banner covers the "clean / ahead" case.
        if (rv.snoozed) SnoozeBanner(state, rv)
        if (rv.behind > 0 && repo.upstream != null) BehindBanner(state, rv)
        else if (selClean) CleanBanner(state, rv)
        repo.warning?.let { WarnBanner(it) }

        // Changed files
        val openDiff = { state.openDiff(repo.id) }
        if (staged.isNotEmpty()) FileSection("Staged", Tokens.stagedHdr, Tokens.stagedDot, staged, state.accent, true, openDiff)
        if (modified.isNotEmpty()) FileSection("Modified", Tokens.modifiedHdr, Tokens.modifiedDot, modified, state.accent, true, openDiff)
        if (untracked.isNotEmpty()) FileSection("Untracked", Tokens.untrackedHdr, Tokens.untrackedDot, untracked, state.accent, false, openDiff)

        // Stashes
        if (repo.stashes.isNotEmpty()) StashSection(state, repo.id, repo.stashes, state.accent)

        // Submodules (if any) — pointer status, target, fetch + pointer update
        if (repo.hasSubmodules) SubmodulesSection(state, rv)

        // Branches (local), with status vs mainline + delete
        if (repo.isGitRepo) BranchesSection(state, rv)

        // Per-repo "stale after N days" threshold (override the global default)
        if (repo.isGitRepo) StaleThresholdRow(state, rv)

        // What will notify (attention / aging / reminders), and how snooze affects it
        NotificationsSection(state, rv)

        // Working notes
        if (state.showNotes) NotesSection(state, rv)

        // Curation: stop tracking this repo (non-destructive)
        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp).drawTopBorder(hex("#ecebe8")).padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Txt("✕ Remove Repo", 12.sp, Tokens.redText, FontWeight.SemiBold,
                modifier = Modifier.onTap {
                    state.popup = Popup.Confirm(
                        "Remove ${repo.name}?",
                        "Stops tracking it. Doesn't touch the repo on disk.",
                        "Remove", danger = true,
                    ) { state.removeRepo(repo.id) }
                })
            Spacer(Modifier.weight(1f))
            Txt("stops tracking · doesn't touch the repo", 11.sp, Tokens.muted2)
        }
        }   // content Column
    }       // outer Row (drag handle + content)
}

@Composable
private fun TagEditor(state: AppState, rv: RepoView) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rv.tagChips.forEach { chip ->
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier.clip(shape).background(chip.bg, shape).border(1.dp, chip.border, shape)
                    .padding(start = 9.dp, end = 5.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Txt("${chip.ns}:", 11.5.sp, chip.color.copy(alpha = 0.7f), FontWeight.SemiBold)
                Txt(chip.value, 11.5.sp, chip.color, FontWeight.SemiBold)
                Txt("×", 13.sp, chip.color.copy(alpha = 0.7f),
                    modifier = Modifier.onTap { state.removeTag(rv.id, chip.key) })
            }
        }
        if (!state.addingTag) {
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier.clip(shape).border(1.dp, hex("#cbc9c5"), shape)
                    .onTap { state.addingTag = true }
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) { Txt("+ Tag", 11.5.sp, Tokens.secondary, FontWeight.SemiBold) }
        } else {
            TagAutocompleteField(
                accent = state.accent,
                suggest = { state.tagSuggestions(it, rv.id) },
                onCommit = { state.addTag(rv.id, it); state.addingTag = false },
                onCancel = { state.addingTag = false },
                resetKey = rv.id,
            )
        }
    }
}

/**
 * Inline-ghost tag autocomplete field, shared by the detail-pane "+ Tag" and the bulk "Tag" dialog.
 * Type any part of an existing tag ([suggest] provides matches); the completion renders greyed around
 * the typed text (prefix, suffix, or mid-string). Tab cycles matches (Shift-Tab reverses) and accepts
 * the last one; → also accepts; Enter commits ([onCommit] with the resolved value); Esc cancels.
 */
@Composable
fun TagAutocompleteField(
    accent: Color,
    suggest: (String) -> List<String>,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit,
    resetKey: Any = Unit,
    fieldWidth: androidx.compose.ui.unit.Dp = 196.dp,
    placeholder: String = "namespace:value",
) {
    var tfv by remember(resetKey) { mutableStateOf(TextFieldValue("")) }
    var cycle by remember(resetKey) { mutableStateOf(0) }
    val typed = tfv.text
    fun commit(v: String) { onCommit(v); tfv = TextFieldValue(""); cycle = 0 }
    // Every existing tag the typed text can complete to (prefix matches first, then substring),
    // e.g. "kot" or "kotlin" both surface "lang:kotlin". Tab cycles when there are several
    // (Shift-Tab reverses) and accepts when only one is left; Enter or → also accept.
    val candidates = suggest(typed)
    val n = candidates.size
    val idx = if (n > 0) cycle.mod(n) else 0
    val active = candidates.getOrNull(idx)
    // Accept the active completion into the field, cursor at the end.
    fun accept() { active?.let { tfv = TextFieldValue(it, selection = TextRange(it.length)); cycle = 0 } }
    val atEnd = tfv.selection.collapsed && tfv.selection.end == typed.length
    // Inline completion via a visual transformation: render the active candidate with the typed
    // substring in black and the completed characters around it in grey — works whether the typed
    // text is a prefix, a suffix (e.g. "kotlin" → "lang:kotlin"), or in the middle. The field's real
    // value stays the typed text; the OffsetMapping keeps the cursor correct.
    val completion = remember(typed, active) {
        val a = active
        val start = if (a != null && typed.isNotEmpty()) a.lowercase().indexOf(typed.lowercase()) else -1
        if (a == null || start < 0) VisualTransformation.None
        else VisualTransformation {
            val end = start + typed.length
            val styled = buildAnnotatedString {
                withStyle(SpanStyle(color = Tokens.muted2)) { append(a.substring(0, start)) }
                withStyle(SpanStyle(color = Tokens.text)) { append(typed) }
                withStyle(SpanStyle(color = Tokens.muted2)) { append(a.substring(end)) }
            }
            TransformedText(styled, object : OffsetMapping {
                override fun originalToTransformed(offset: Int) = start + offset.coerceIn(0, typed.length)
                override fun transformedToOriginal(offset: Int) = when {
                    offset <= start -> 0
                    offset >= end -> typed.length
                    else -> offset - start
                }
            })
        }
    }
    val focus = remember(resetKey) { FocusRequester() }
    LaunchedEffect(resetKey) { runCatching { focus.requestFocus() } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            // Fixed width (not fillMaxWidth-driven) so the Add / Cancel controls stay on-row.
            Modifier.width(fieldWidth).clip(RoundedCornerShape(8.dp)).background(Color.White)
                .border(1.dp, Tokens.borderD8, RoundedCornerShape(8.dp))
                .padding(horizontal = 9.dp, vertical = 4.dp),
        ) {
            if (typed.isEmpty()) Txt(placeholder, 11.5.sp, Tokens.muted2, font = MonoFont)
            BasicTextField(
                value = tfv, onValueChange = { nv -> if (nv.text != tfv.text) cycle = 0; tfv = nv },
                singleLine = true,
                textStyle = TextStyle(fontSize = 11.5.sp, fontFamily = MonoFont, color = Tokens.text),
                cursorBrush = SolidColor(accent),
                visualTransformation = completion,
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.Escape -> { onCancel(); true }
                        // Tab cycles through several matches (Shift-Tab reverses); with one left, accepts.
                        Key.Tab -> when {
                            n == 1 -> { accept(); true }
                            n > 1 -> { cycle += if (ev.isShiftPressed) -1 else 1; true }
                            else -> false
                        }
                        // Right arrow at the end of the text accepts the active completion.
                        Key.DirectionRight -> if (active != null && atEnd) { accept(); true } else false
                        // Enter commits — the active completion if any, else the typed text.
                        Key.Enter, Key.NumPadEnter -> { commit(active ?: typed); true }
                        else -> false
                    }
                },
            )
        }
        Box(
            Modifier.clip(RoundedCornerShape(8.dp)).background(accent)
                .onTap { commit(active ?: typed) }.padding(horizontal = 10.dp, vertical = 4.dp),
        ) { Txt("Add", 11.5.sp, Color.White, FontWeight.Bold) }
        Txt("Cancel", 11.5.sp, Tokens.muted, modifier = Modifier.onTap { onCancel() })
        // Hint: cycle position when there are several matches, else the accept affordance.
        val hint = when {
            active == null -> null
            n > 1 -> "⇥ ${idx + 1}/$n · ⏎ accept"
            else -> "⇥ accept"
        }
        hint?.let { Txt(it, 10.5.sp, Tokens.muted2, maxLines = 1) }
    }
}

@Composable
private fun ActionButton(
    label: String,
    color: Color,
    bg: Color,
    border: Color?,
    disabled: Boolean = false,
    onClick: () -> Unit = {},
) {
    val shape = RoundedCornerShape(8.dp)
    if (disabled) {
        Box(
            Modifier.clip(shape).background(Tokens.segTrack, shape)
                .border(1.dp, Tokens.borderE2, shape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) { Txt(label, 12.5.sp, Tokens.muted2, FontWeight.Bold) }
        return
    }
    var m = Modifier.clip(shape).background(bg, shape)
    if (border != null) m = m.border(1.dp, border, shape)
    Box(m.onTap(onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Txt(label, 12.5.sp, color, FontWeight.Bold)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun OpenInGroup(state: AppState, repo: Repo) {
    val path = repo.id
    val shape = RoundedCornerShape(9.dp)
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(shape)
            .background(hex("#f4f3f1")).border(1.dp, Tokens.borderE2, shape).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(Modifier.padding(start = 6.dp, end = 8.dp), contentAlignment = Alignment.Center) {
            Txt("OPEN IN", 10.5.sp, Tokens.muted2, FontWeight.Bold, letterSpacing = 0.5.sp)
        }
        OpenInButton(">_ Terminal", MonoFont) { state.openTerminal(path) }
        OpenInButton("GitButler", UiFont) { state.openGitButler(path) }
        OpenInButton("Git Gui", UiFont) { state.openGitGui(path) }
        OpenInButton("IDE", UiFont) { state.openIde(path) }
        OpenInButton("Folder", UiFont) { state.openFolder(path) }
        // GitHub web links, for repos with a github.com remote.
        if (repo.isGitHub && repo.webBase != null) {
            OpenInButton("Issues", UiFont) { state.openUrl("${repo.webBase}/issues") }
            OpenInButton("Pull Requests", UiFont) { state.openUrl("${repo.webBase}/pulls") }
            if (repo.hasWorkflows) OpenInButton("Actions", UiFont) { state.openUrl("${repo.webBase}/actions") }
        }
    }
}

@Composable
private fun OpenInButton(label: String, font: androidx.compose.ui.text.font.FontFamily, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.clip(shape).background(Color.White, shape).border(1.dp, Tokens.borderE6, shape)
            .onTap(onClick).padding(horizontal = 10.dp, vertical = 5.dp),
    ) { Txt(label, 12.sp, Tokens.text2, FontWeight.Bold, font = font) }
}

@Composable
private fun SnoozeBanner(state: AppState, rv: RepoView) {
    Banner(Tokens.snoozeBannerBg, Tokens.snoozeBannerBorder, top = 16) {
        Txt("☾", 13.sp, Tokens.purple)
        Row(Modifier.weight(1f)) {
            Txt("Alerts silenced for ", 12.5.sp, Tokens.secondary)
            Txt(rv.repo.snoozedFor ?: "until resumed", 12.5.sp, Tokens.snoozeBannerText, FontWeight.Bold)
        }
        Txt("Resume now", 12.sp, state.accent, FontWeight.SemiBold,
            modifier = Modifier.onTap { state.setSnoozeUntil(setOf(rv.id), null) })
    }
}

@Composable
private fun CleanBanner(state: AppState, rv: RepoView) {
    Column(
        Modifier.fillMaxWidth().padding(top = 18.dp).clip(RoundedCornerShape(10.dp))
            .background(Tokens.cleanBg).border(1.dp, Tokens.cleanBorder, RoundedCornerShape(10.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Txt("✓ Working tree clean", 14.sp, Tokens.cleanText, FontWeight.Bold)
        if (rv.ahead > 0) Txt("${rv.ahead} commit(s) ready to push to ${rv.upstream}.", 12.5.sp, Tokens.secondary, maxLines = 2)
        // "behind" is handled by BehindBanner so its Diff/Log actions have a consistent home.
    }
}

/**
 * Shown whenever the current branch is behind its upstream (clean or dirty). Carries the
 * "N commits behind" message plus the actions to inspect those incoming commits — a Diff of
 * everything they change (`HEAD..upstream`) and a Log listing them. This is the one place the
 * behind actions live; the branch line's "↓N behind" is the at-a-glance echo.
 */
@Composable
private fun BehindBanner(state: AppState, rv: RepoView) {
    val repo = rv.repo
    val upstream = repo.upstream ?: return
    val n = rv.behind
    val commits = "$n commit${if (n == 1) "" else "s"}"
    val how = if (rv.ahead == 0) "Fast Forward to catch up" else "diverged — rebase or merge to catch up"
    Column(
        Modifier.fillMaxWidth().padding(top = 18.dp).clip(RoundedCornerShape(10.dp))
            .background(Tokens.cleanBg).border(1.dp, Tokens.cleanBorder, RoundedCornerShape(10.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Txt("↓ $commits behind $upstream", 14.sp, Tokens.cleanText, FontWeight.Bold)
        Txt(how.replaceFirstChar { it.uppercase() } + ".", 12.5.sp, Tokens.secondary, maxLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BranchActionPill("⑃ Diff") {
                state.openRangeDiff(repo.id, "HEAD", upstream, "Incoming — $upstream ($commits)", "HEAD → $upstream")
            }
            BranchActionPill("☰ Log") {
                state.openRangeLog(repo.id, "HEAD..$upstream", "Incoming — $upstream ($commits)")
            }
        }
    }
}

@Composable
private fun WarnBanner(warning: String) {
    Box(
        Modifier.fillMaxWidth().padding(top = 18.dp).clip(RoundedCornerShape(10.dp))
            .background(Tokens.warnBg).border(1.dp, Tokens.warnBorder, RoundedCornerShape(10.dp))
            .padding(16.dp),
    ) { Txt("⚠ $warning", 14.sp, Tokens.redText, FontWeight.Bold) }
}

@Composable
private fun Banner(bg: Color, border: Color, top: Int, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = top.dp).clip(RoundedCornerShape(10.dp))
            .background(bg).border(1.dp, border, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun FileSection(title: String, titleColor: Color, dotColor: Color, files: List<ChangedFile>, accent: Color, showDiff: Boolean, onDiff: () -> Unit) {
    val byPath = files.associateBy { it.path }
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Row(
            Modifier.padding(bottom = 6.dp).let { if (showDiff) it.onTap(onDiff) else it },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(dotColor))
            Txt(title, 12.5.sp, titleColor, FontWeight.Bold)
            if (showDiff) {
                Spacer(Modifier.weight(1f))
                Txt("Diff ›", 11.5.sp, accent, FontWeight.Medium)
            }
        }
        // Flattened tree — deep package paths collapse so filenames stay readable.
        PathTree.flatten(files.map { it.path }).forEach { node ->
            if (node.fullPath == null) {
                PathTip(node.label, Modifier.padding(start = (8 + node.depth * 14).dp, top = 3.dp, bottom = 3.dp)) {
                    Txt("▸ ${node.label}", 11.5.sp, Tokens.muted2, font = MonoFont, maxLines = 1)
                }
            } else {
                val f = byPath[node.fullPath] ?: return@forEach
                Row(
                    Modifier.fillMaxWidth().padding(start = (8 + node.depth * 14).dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) {
                        Txt(f.tag, 12.sp, fileTagColor(f), FontWeight.SemiBold, font = MonoFont)
                    }
                    PathTip(node.label, Modifier.weight(1f)) {   // filename only; folders shown in tree
                        Txt(node.label, 12.sp, Tokens.text2, font = MonoFont, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun StashSection(state: AppState, id: String, stashes: List<Stash>, accent: Color) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt("⚑ Stashes", 12.5.sp, Tokens.purple, FontWeight.Bold, modifier = Modifier.padding(bottom = 2.dp))
        stashes.forEach { st ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Tokens.stashCardBg).border(1.dp, Tokens.stashCardBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Txt(st.label, 11.sp, Tokens.purple, FontWeight.SemiBold, font = MonoFont)
                Txt(st.msg, 12.5.sp, Tokens.text2, modifier = Modifier.weight(1f))
                Txt("Diff", 12.sp, accent, FontWeight.Medium, modifier = Modifier.onTap { state.openStashDiff(id, st.label) })
                Txt("Apply", 12.sp, accent, FontWeight.Medium, modifier = Modifier.onTap {
                    state.popup = Popup.Confirm(
                        "Apply ${st.label}?",
                        "Restores this stash's changes into the working tree (the stash is kept).",
                        "Apply", danger = false,
                    ) { state.stashApply(id, st.label) }
                })
                Txt("Drop", 12.sp, Tokens.redText, FontWeight.Medium, modifier = Modifier.onTap {
                    state.popup = Popup.Confirm("Drop ${st.label}?", "Permanently deletes this stash.", "Drop", danger = true) {
                        state.stashDrop(id, st.label)
                    }
                })
            }
        }
    }
}

@Composable
private fun SubmodulesSection(state: AppState, rv: RepoView) {
    LaunchedEffect(rv.id) { state.loadSubmodules(rv.id) }
    if (state.submodulesRepo != rv.id || state.submodules.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 20.dp).drawTopBorder(hex("#ecebe8")).padding(top = 14.dp)) {
        Row(
            Modifier.padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Txt("Submodules", 12.5.sp, Tokens.text, FontWeight.Bold)
            Txt("— ${state.submodules.size}", 11.sp, Tokens.muted2)
            Spacer(Modifier.weight(1f))
            if (state.submodules.any { it.initialized }) {
                Txt(if (state.submodulesBusy) "Fetching…" else "Fetch all", 12.sp, state.accent, FontWeight.SemiBold,
                    modifier = if (state.submodulesBusy) Modifier else Modifier.onTap { state.fetchAllSubmodules(rv.id) })
            }
        }
        state.submodules.forEach { s -> SubmoduleRow(state, rv.id, s) }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SubmoduleRow(state: AppState, id: String, s: SubmoduleOps.Submodule) {
    val subFullPath = java.io.File(id, s.path).absolutePath
    val tracked = state.isTracked(subFullPath)
    val busy = state.submodulesBusy
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        // Line 1: path · recorded pointer · status badge
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(s.path, 12.5.sp, Tokens.text2, FontWeight.SemiBold, font = MonoFont, maxLines = 1, modifier = Modifier.weight(1f))
            if (s.initialized && s.recorded.isNotEmpty()) Txt("@${s.recorded.take(7)}", 10.5.sp, Tokens.muted2, font = MonoFont)
            when {
                !s.initialized -> BranchBadge("not initialized", Tokens.muted, Tokens.segTrack)
                s.statusChar == 'U' -> BranchBadge("conflict", Tokens.redText, Tokens.tintRed)
                s.behind > 0 -> BranchBadge("behind ${s.behind}", Tokens.snoozeBtnText, Tokens.snoozeBtnBg)
                s.statusChar == '+' -> BranchBadge("moved", state.accent, Tokens.tintBlue)
                else -> BranchBadge("up to date", Tokens.cleanText, Tokens.cleanBg)
            }
        }
        // Line 2: → target url (tooltip)
        PathTip(s.url, Modifier.padding(start = 2.dp, top = 2.dp)) {
            Txt("→ ${s.url}", 11.sp, Tokens.muted, font = MonoFont, maxLines = 1)
        }
        // Uncommitted changes inside the submodule — surfaced prominently.
        if (s.dirtyCount > 0) {
            Txt(
                "⚠ ${s.dirtyCount} uncommitted change${if (s.dirtyCount == 1) "" else "s"} inside — commit or stash them",
                11.sp, Tokens.amber, FontWeight.SemiBold, modifier = Modifier.padding(start = 2.dp, top = 3.dp),
            )
        }
        // Actions (wrap as the pane narrows)
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!s.initialized) {
                if (!busy) BranchActionPill("Init") { state.initSubmodule(id, s.path) }
            } else {
                if (!busy) BranchActionPill("Fetch") { state.fetchSubmodule(id, s.path) }
                if (s.behind > 0 && !busy) BranchActionPill("Update") { state.updateSubmodulePointer(id, s.path) }
                // Diff against whatever branch this submodule tracks (main, or a configured
                // submodule.<name>.branch like develop) — not necessarily mainline.
                if (s.behind > 0 && s.remoteRef != null) {
                    val shortRef = s.remoteRef.substringAfterLast('/')
                    BranchActionPill("Diff $shortRef") { state.openSubmoduleDiff(id, s) }
                    BranchActionPill("Log $shortRef") { state.openSubmoduleLog(id, s) }
                }
                if (!busy) BranchActionPill("Deinit") {
                    state.popup = Popup.Confirm(
                        "Deinitialize ${s.path}?",
                        "Removes the submodule's working tree (its files). You can re-init later; the pointer in the parent is untouched.",
                        "Deinit", danger = true,
                    ) { state.deinitSubmodule(id, s.path) }
                }
            }
            if (tracked) BranchActionPill("Goto Repo") {
                state.trackedRepoAt(subFullPath)?.let { state.selectedId = it }
            }
            else if (s.initialized && !busy) BranchActionPill("+ Add Repo") { state.trackSubmodule(id, s) }
        }
    }
}

@Composable
private fun BranchesSection(state: AppState, rv: RepoView) {
    LaunchedEffect(rv.id) { state.loadBranches(rv.id) }
    if (state.branchesRepo != rv.id) return
    // Switching branches would clobber uncommitted work, so it's only offered on a clean tree.
    val clean = with(rv.repo) { staged + unstaged + untracked == 0 }
    Column(Modifier.fillMaxWidth().padding(top = 20.dp).drawTopBorder(hex("#ecebe8")).padding(top = 14.dp)) {
        Row(
            Modifier.padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Txt("Branches", 12.5.sp, Tokens.text, FontWeight.Bold)
            if (state.branches.isNotEmpty()) Txt("— ${state.branches.size}", 11.sp, Tokens.muted2)
        }
        if (state.branchesLoading && state.branches.isEmpty()) {
            Txt("Loading…", 12.sp, Tokens.muted2, modifier = Modifier.padding(vertical = 4.dp))
        }
        state.branches.forEach { b -> BranchRow(state, rv.id, b, clean) }
        RemoteBranchesSection(state, rv.id, clean)
    }
}

@Composable
private fun BranchRow(state: AppState, id: String, b: BranchOps.Branch, clean: Boolean) {
    val canSwitch = !b.isCurrent && !state.switchingBranch
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        // Line 1: branch name · mainline-relationship badge · ahead-of-mainline · delete
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(
                b.name, 12.5.sp,
                if (b.isCurrent) Tokens.text else Tokens.text2,
                if (b.isCurrent) FontWeight.Bold else FontWeight.Normal,
                font = MonoFont, maxLines = 1, modifier = Modifier.weight(1f),
            )
            when {
                b.isCurrent -> BranchBadge("current", Tokens.cleanText, Tokens.cleanBg)
                b.isMainline -> BranchBadge("mainline", Tokens.secondary, Tokens.segTrack)
                // Merged wins over stale: a branch already folded into mainline is "done", not neglected.
                b.merged -> BranchBadge("merged", Tokens.purple, Tokens.tintPurple)
                b.stale -> BranchBadge("stale", Tokens.amber, Tokens.tintAmber)
                b.behind > 0 -> BranchBadge("behind ${b.behind}", Tokens.snoozeBtnText, Tokens.snoozeBtnBg)
                else -> BranchBadge("up to date", Tokens.muted, Tokens.segTrack)
            }
            if (b.ahead > 0 && !b.isMainline) Txt("↑${b.ahead}", 11.sp, state.accent, FontWeight.SemiBold, font = MonoFont)
            if (!b.isCurrent && !b.isMainline) {
                Txt("✕", 13.sp, Tokens.redText, modifier = Modifier.onTap {
                    // Mirror `git branch -d/-D`: a merged branch deletes with no prompt (nothing is
                    // lost). We use -D because "merged" is measured against mainline — `git branch -d`
                    // would refuse when you're on a different branch, even though the commits are safely
                    // in mainline. An unmerged branch still needs an explicit force-delete confirmation.
                    if (b.merged) {
                        state.deleteBranch(id, b.name, force = true)
                    } else {
                        state.popup = Popup.Confirm(
                            "Delete branch “${b.name}”?",
                            "It has ${b.ahead} unmerged commit${if (b.ahead == 1) "" else "s"} — force-deleting discards them.",
                            "Force delete", danger = true,
                        ) { state.deleteBranch(id, b.name, force = true) }
                    }
                })
            }
        }
        // Line 2: tracking status (left) · Diff / Switch actions (right)
        Row(
            Modifier.padding(start = 2.dp, top = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (b.upstream == null) {
                Txt("⚠ no upstream", 11.sp, Tokens.muted2)
            } else {
                Txt("⇄ ${b.upstream}", 11.sp, Tokens.muted, font = MonoFont)
                when {
                    b.upstreamGone -> Txt("· upstream gone", 11.sp, Tokens.redText, FontWeight.SemiBold)
                    b.upstreamAhead > 0 && b.upstreamBehind > 0 ->
                        Txt("· ↑${b.upstreamAhead} ↓${b.upstreamBehind} diverged", 11.sp, Tokens.amber, FontWeight.SemiBold, font = MonoFont)
                    b.upstreamAhead > 0 -> Txt("· ↑${b.upstreamAhead} ahead", 11.sp, state.accent, FontWeight.SemiBold, font = MonoFont)
                    b.upstreamBehind > 0 -> Txt("· ↓${b.upstreamBehind} behind", 11.sp, Tokens.behind, FontWeight.SemiBold, font = MonoFont)
                    else -> Txt("· ✓ in sync", 11.sp, Tokens.cleanText, FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.weight(1f))
            if (!b.isMainline) {
                BranchActionPill("Diff") { state.openBranchDiff(id, b.name, b.name) }
                BranchActionPill("Log") { state.openBranchLog(id, b.name, b.name) }
            }
            if (canSwitch) BranchActionPill("Switch") {
                if (clean) state.switchBranch(id, b.name)
                else state.popup = Popup.Confirm(
                    "Switch to ${b.name}?",
                    "You have uncommitted changes — git will carry them into “${b.name}”, or refuse if they'd conflict.",
                    "Switch anyway", danger = false,
                ) { state.switchBranch(id, b.name) }
            }
        }
    }
}

@Composable
private fun RemoteBranchesSection(state: AppState, id: String, clean: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(
            Modifier.onTap { state.toggleRemoteBranches(id) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Txt(if (state.showRemoteBranches) "▾" else "▸", 11.sp, Tokens.muted2)
            Txt("Remote branches", 12.sp, Tokens.secondary, FontWeight.SemiBold)
            if (state.remoteBranches.isNotEmpty()) Txt("— ${state.remoteBranches.size}", 11.sp, Tokens.muted2)
        }
        if (state.showRemoteBranches) {
            if (state.remoteBranches.isEmpty()) {
                Txt("No remote branches", 11.sp, Tokens.muted2, modifier = Modifier.padding(vertical = 4.dp))
            }
            state.remoteBranches.forEach { rb -> RemoteBranchRow(state, id, rb, clean) }
        }
    }
}

@Composable
private fun RemoteBranchRow(state: AppState, id: String, rb: BranchOps.RemoteBranch, clean: Boolean) {
    val canSwitch = !state.switchingBranch
    val isMainline = rb.shortName == "main" || rb.shortName == "master"
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        // Line 1: remote ref · merged · tracked
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(rb.name, 12.sp, Tokens.text2, font = MonoFont, maxLines = 1, modifier = Modifier.weight(1f))
            if (rb.merged) BranchBadge("merged", Tokens.purple, Tokens.tintPurple)
            if (rb.hasLocal) BranchBadge("tracked", Tokens.muted, Tokens.segTrack)
        }
        // Line 2: author · time · Diff / Switch / Checkout
        Row(
            Modifier.padding(start = 2.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Txt("${rb.author} · ${rb.lastRelative}", 10.5.sp, Tokens.muted2, maxLines = 1)
            Spacer(Modifier.weight(1f))
            if (!isMainline) {
                BranchActionPill("Diff") { state.openBranchDiff(id, rb.name, rb.name) }
                BranchActionPill("Log") { state.openBranchLog(id, rb.name, rb.name) }
            }
            if (canSwitch) BranchActionPill(if (rb.hasLocal) "Switch" else "Checkout") {
                if (clean) state.checkoutRemoteBranch(id, rb)
                else state.popup = Popup.Confirm(
                    if (rb.hasLocal) "Switch to ${rb.shortName}?" else "Check out ${rb.shortName}?",
                    "You have uncommitted changes — git will carry them over, or refuse if they'd conflict.",
                    if (rb.hasLocal) "Switch anyway" else "Checkout anyway", danger = false,
                ) { state.checkoutRemoteBranch(id, rb) }
            }
        }
    }
}

@Composable
private fun BranchBadge(label: String, color: Color, bg: Color) {
    Pill(label, color, bg, fontSize = 10.5.sp, weight = FontWeight.SemiBold, radius = 10,
        padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 7.dp, vertical = 2.dp))
}

/** A small, explicit "Switch"/"Checkout" action button on a branch row. */
@Composable
private fun BranchActionPill(label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(Tokens.tintBlue)
            .border(1.dp, hex("#c3dcf8"), RoundedCornerShape(6.dp))
            .pointerHoverIcon(PointerIcon.Hand).onTap(onClick)
            .padding(horizontal = 9.dp, vertical = 2.dp),
    ) { Txt(label, 10.5.sp, Tokens.accent, FontWeight.SemiBold) }
}

/** Per-repo "stale after N days" control: presets set an override, "Default" clears it back to the
 *  global threshold. Click-only (no text entry). The effective value shows in the header. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun StaleThresholdRow(state: AppState, rv: RepoView) {
    val override = state.staleThresholdDays(rv.id)
    Column(Modifier.fillMaxWidth().padding(top = 20.dp).drawTopBorder(hex("#ecebe8")).padding(top = 14.dp)) {
        Row(
            Modifier.padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val never = rv.repo.staleDays == Meta.STALE_NEVER
            Txt("Flags stale after", 12.5.sp, Tokens.text, FontWeight.Bold)
            Txt(
                if (never) "never" else "${rv.repo.staleDays} days",
                12.5.sp, state.accent, FontWeight.Bold, font = MonoFont,
            )
            if (override == null) Txt("· default", 11.sp, Tokens.muted2)
        }
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(7, 14, 30, 60, 90, 180).forEach { d ->
                PresetPill("${d}d", override == d, state.accent) { state.setStaleThresholdDays(rv.id, d) }
            }
            // For repos that are meant to sit untouched (archived mirrors, vendored deps), where a
            // stale flag is noise rather than a signal.
            PresetPill("Never", override == Meta.STALE_NEVER, state.accent) {
                state.setStaleThresholdDays(rv.id, Meta.STALE_NEVER)
            }
            PresetPill("Default", override == null, state.accent) { state.setStaleThresholdDays(rv.id, null) }
        }
        // How loud staleness should be for this repo. Default is informational (blue) because
        // stable code legitimately sits untouched; "Important" escalates it to yellow for repos
        // where going quiet actually means something is wrong. Hidden when the repo never goes
        // stale — there'd be nothing for the choice to apply to.
        if (rv.repo.staleDays != Meta.STALE_NEVER) {
            val important = state.staleImportant(rv.id)
            Row(
                Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Txt("Treat staleness as", 12.5.sp, Tokens.text, FontWeight.Bold)
                InfoTip(
                    "Informational shows stale in blue — a heads-up, alongside repos that are simply " +
                        "behind. Important shows it in yellow, next to uncommitted and unpushed work, " +
                        "and sorts the repo higher under the Attention ordering.",
                )
            }
            Row(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PresetPill("Informational", !important, state.accent) { state.setStaleImportant(rv.id, false) }
                PresetPill("Important", important, state.accent) { state.setStaleImportant(rv.id, true) }
            }
        }
    }
}

@Composable
private fun PresetPill(label: String, on: Boolean, accent: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.clip(shape).background(if (on) Tokens.tintBlue else Color.White, shape)
            .border(1.dp, if (on) hex("#c3dcf8") else Tokens.borderE2, shape)
            .pointerHoverIcon(PointerIcon.Hand).onTap(onClick)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) { Txt(label, 11.5.sp, if (on) accent else Tokens.secondary, if (on) FontWeight.Bold else FontWeight.Medium) }
}

/** A plain-language outlook of the desktop notifications this repo will actually produce, each with
 *  its timing: the manual reminder, the aging/stale threshold crossings, and the opt-in upstream
 *  alert. Signals that never fire a notification (e.g. an already-stale badge) are shown as their
 *  own visuals elsewhere — they don't appear here, so every line here is a real pending alert. */
@Composable
private fun NotificationsSection(state: AppState, rv: RepoView) {
    val repo = rv.repo
    val now = System.currentTimeMillis()
    val paused = if (rv.snoozed) " (paused)" else ""

    // Assemble rows first so we can skip the whole section when nothing will notify.
    data class Row_(
        val icon: String, val text: String, val color: Color,
        val action: Pair<String, () -> Unit>? = null, val tip: String? = null,
    )
    // Plain-language explanations of the two easily-confused states.
    val agingTip = "“Aging” = you have uncommitted changes that have been sitting in the working " +
        "tree past the threshold. It's about work in progress that hasn't been committed yet — " +
        "committing or stashing clears it."
    val staleTip = "“Stale” = no new commit has landed on this repo in over ${repo.staleDays} days. " +
        "It's about the repository going untouched over time — independent of whether you have " +
        "uncommitted changes. " +
        if (repo.staleImportant) {
            "This repo treats staleness as important, so it shows in yellow alongside uncommitted " +
                "and unpushed work. Change that above."
        } else {
            "It's a blue heads-up rather than a yellow call to action: stable code can sit " +
                "untouched for a long time. Mark it Important above if going quiet matters here."
        }
    val rows = buildList {
        if (rv.snoozed) {
            add(Row_("🔕", "Alerts paused${repo.snoozedFor?.let { " for $it" } ?: ""} — resume to re-enable", Tokens.purple))
        }
        // Reminder — fires at its due time, then re-notifies hourly until marked Done.
        repo.reminder?.let { rem ->
            val next = state.nextReminderAt(repo.id)
            val extra = if (rem.overdue && next != null) " · re-notifies in ${Meta.compactDuration(next - now)}" else ""
            add(Row_("◷", "Reminder “${rem.text}” — ${rem.due}$extra", if (rem.overdue) Tokens.remOverdue else Tokens.remTeal))
        }
        // Aging — a one-shot alert when uncommitted work crosses the threshold.
        if (rv.aging) {
            add(Row_("⚠", "Aging — uncommitted for ${repo.dirtyFor} (alerted)", Tokens.amber, tip = agingTip))
        } else if (repo.dirtySince != null) {
            val toAge = Meta.AGING_MS - (now - repo.dirtySince)
            val text = if (toAge > 0) "Flags as aging in ${Meta.humanDuration(toAge)}$paused"
            else "Would flag as aging now$paused"
            add(Row_("◷", text, Tokens.muted2, tip = agingTip))
        }
        // Stale — a one-shot alert when the repo crosses the no-commit threshold. Only shown while
        // it's still pending; once stale, the "stale" badge carries it (no further notification).
        if (!repo.stale && repo.staleDays != Meta.STALE_NEVER && repo.lastCommitEpoch != null) {
            val toStale = (repo.lastCommitEpoch + repo.staleDays * 86_400L) * 1000 - now
            if (toStale > 0) {
                // The projection is formatted by Human-Readable (it marks rounding with "about"),
                // but the elapsed half reuses git's own relative date (repo.last, i.e. %cr) — the
                // exact string already shown in the repo row and header. git and the library bucket
                // differently (git rounds 3d16h to "4 days"; the library floors it to "3 days"), so
                // formatting this half ourselves would put two different numbers for the same commit
                // on screen at once — the very inconsistency this line exists to remove.
                add(Row_("◷", "Flags as stale in ${Meta.humanDuration(toStale)}, the last commit was about ${repo.last}$paused", Tokens.muted2, tip = staleTip))
            }
        }
        // Upstream advance — opt-in per repo (default off), with an inline toggle.
        if (repo.hasRemote && repo.upstream != null) {
            if (state.notifyUpstreamEnabled(repo.id)) {
                val behind = if (repo.behind > 0) " · behind ${repo.behind} now" else ""
                add(Row_("🔔", "Alerts when ${repo.upstream} gets new commits$behind$paused", Tokens.remTeal,
                    "Turn off" to { state.setNotifyUpstream(repo.id, false) }))
            } else {
                add(Row_("🔕", "Upstream-commit alerts off for this repo", Tokens.muted2,
                    "Enable" to { state.setNotifyUpstream(repo.id, true) }))
            }
        }
    }
    if (rows.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 20.dp).drawTopBorder(hex("#ecebe8")).padding(top = 14.dp)) {
        Txt("Notifications outlook", 12.5.sp, Tokens.text, FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
        rows.forEach { r ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Txt(r.icon, 12.sp, r.color)
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Txt(r.text, 11.5.sp, r.color, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                    r.tip?.let { InfoTip(it) }
                }
                r.action?.let { (label, act) ->
                    Box(
                        Modifier.clip(RoundedCornerShape(6.dp)).background(Tokens.tintBlue)
                            .border(1.dp, hex("#c3dcf8"), RoundedCornerShape(6.dp))
                            .pointerHoverIcon(PointerIcon.Hand).onTap(act)
                            .padding(horizontal = 9.dp, vertical = 2.dp),
                    ) { Txt(label, 10.5.sp, Tokens.accent, FontWeight.SemiBold) }
                }
            }
        }
    }
}

@Composable
private fun NotesSection(state: AppState, rv: RepoView) {
    Column(Modifier.fillMaxWidth().padding(top = 22.dp).drawTopBorder(hex("#ecebe8")).padding(top = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Txt("Working notes", 12.5.sp, Tokens.text, FontWeight.Bold)
            Txt("— what's in progress / TODO", 11.sp, Tokens.muted2)
            Spacer(Modifier.weight(1f))
            if (state.noteOf(rv.id).isNotEmpty()) {
                Txt("Clear", 11.5.sp, Tokens.redText, FontWeight.SemiBold,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).onTap { state.setNote(rv.id, "") })
            }
        }
        val shape = RoundedCornerShape(8.dp)
        Box(
            Modifier.fillMaxWidth().heightIn(min = 62.dp).clip(shape).background(hex("#fcfcfb"))
                .border(1.dp, Tokens.borderD8, shape).padding(10.dp),
        ) {
            BasicTextField(
                value = state.noteOf(rv.id), onValueChange = { state.setNote(rv.id, it) },
                textStyle = TextStyle(fontSize = 12.5.sp, color = Tokens.text2, fontFamily = UiFont),
                cursorBrush = SolidColor(state.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Reminder line
        Row(
            Modifier.padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val rem = rv.repo.reminder
            if (rem != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Txt("◷ Reminder:", 12.sp, Tokens.muted)
                    Txt(rem.text, 12.sp, Tokens.text2, FontWeight.SemiBold)
                    Txt("· ${rem.due}", 12.sp, if (rem.overdue) Tokens.remOverdue else Tokens.remTeal, FontWeight.Bold)
                    if (rem.overdue) {
                        val next = state.nextReminderAt(rv.id)
                        val delta = if (next != null) next - System.currentTimeMillis() else null
                        val label = when {
                            delta == null -> null
                            delta <= 0 -> "· next reminder due now"
                            else -> "· next reminder in ${Meta.compactDuration(delta)}"
                        }
                        if (label != null) Txt(label, 12.sp, Tokens.muted, FontWeight.Medium)
                    }
                }
                Txt("Edit", 12.sp, state.accent, FontWeight.SemiBold,
                    modifier = Modifier.onTap { state.popup = Popup.Remind(setOf(rv.id), rem.text, null) })
                Txt("Clear", 12.sp, Tokens.redText, FontWeight.SemiBold,
                    modifier = Modifier.onTap { state.clearReminder(setOf(rv.id)) })
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Txt("◷ Reminder:", 12.sp, Tokens.muted)
                    Txt("none set", 12.sp, Tokens.text2, FontWeight.SemiBold)
                }
                Txt("+ Add reminder", 12.sp, state.accent, FontWeight.SemiBold,
                    modifier = Modifier.onTap { state.popup = Popup.Remind(setOf(rv.id), "", null) })
            }
        }
    }
}
