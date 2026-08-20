// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup as WindowPopup
import androidx.compose.ui.window.PopupProperties
import com.gitvantage.app.AppState
import com.gitvantage.app.GitHub
import com.gitvantage.app.MonoFont
import com.gitvantage.app.Popup
import com.gitvantage.app.RepoView
import com.gitvantage.app.Tokens
import com.gitvantage.app.UiFont
import com.gitvantage.git.model.Branch
import com.gitvantage.git.model.RemoteBranch
import com.gitvantage.git.model.Submodule
import com.gitvantage.model.ChangedFile
import com.gitvantage.model.Meta
import com.gitvantage.model.Repo
import com.gitvantage.model.Stash

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
                .background(Tokens.surface)
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

        // Branch line. Just the branch and a way to copy it: the ahead/behind numbers used to sit
        // here too and said the same thing as the badges on the row and the banner below, three
        // times on one screen.
        Row(
            Modifier.padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Txt("⎇ ${repo.branch} → ${rv.upstream}", 12.sp, Tokens.secondary, font = MonoFont,
                modifier = Modifier.weight(1f, fill = false))
            // Only when there's a real branch name to copy — a detached HEAD or a non-repo has none.
            if (repo.hasNamedBranch) CopyPill(repo.branch, "Copy branch")
        }

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

        // Actions. Split by consequence: a bordered button changes something, a flat accent link
        // only shows you something. That's the same rule the list rows' hover actions follow, and
        // it's what lets Diff and Log lose their borders without losing their meaning — they were
        // the two read-only actions wearing the same weight as Push.
        ActionRow(state, rv)

        // Banners. Behind-upstream takes its own banner (with Diff/Log) so it shows whether the tree
        // is clean or dirty; the clean banner covers the "clean / ahead" case.
        if (rv.snoozed) SnoozeBanner(state, rv)
        if (rv.behind > 0 && repo.upstream != null) BehindBanner(state, rv)
        else if (selClean) CleanBanner(rv)
        repo.warning?.let { WarnBanner(it) }

        // ---- work zones, in scan order: what's in this folder, what's in the others, what refs exist

        // Changed files — one list, not three sections. The three headings were a taxonomy of git's
        // index, and the question the pane is asked is "what have I touched"; the state each file is
        // in stays on the row, where it belongs to the file rather than to a heading.
        if (repo.files.isNotEmpty()) ChangedFilesSection(state, rv)

        // Stashes
        if (repo.stashes.isNotEmpty()) StashSection(state, repo.id, repo.stashes)

        // Submodules (if any) — pointer status, target, fetch + pointer update
        if (repo.hasSubmodules) SubmodulesSection(state, rv)

        // The branches this repository has checked out in other folders, each openable in place.
        WorktreesPaneSection(state, rv)

        // Branches (local), with status vs mainline + delete
        if (repo.isGitRepo) BranchesSection(state, rv)

        // Open GitHub issues / pull requests. Only for remotes we can actually query: a non-GitHub
        // forge (Azure DevOps, GitLab, …) or a GitHub URL that isn't a plain owner/repo gets no
        // section at all, rather than a permanently empty one — an empty "Issues & pull requests"
        // heading on a GitLab repo reads as "no open issues", which is a different claim entirely.
        if (state.issuesSupported(repo)) IssuesSection(state, rv)

        // What will notify, and when. Its thresholds and opt-ins live in Settings; this is the
        // forecast they produce.
        NotificationsOutlook(state, rv)

        // Note + reminder, two lines. The full editors are one click away; what the pane owes at a
        // glance is what the note *says*, and a 60px empty textarea said nothing on most repos.
        if (state.showNotes) NoteLines(state, rv)

        // Everything that configures rather than reports, behind one disclosure — see [SettingsZone].
        SettingsZone(state, rv)
        }   // content Column
    }       // outer Row (drag handle + content)
}

/**
 * Zone 4: the pane's action row.
 *
 * One wrapping row with a divider in it, rather than two rows. The old pane had four bordered
 * buttons, then a bordered Diff and Log beside them, then a snooze pill, then a whole second bar of
 * "OPEN IN" buttons — nine or ten controls of near-identical weight, in which nothing said which
 * ones would change the repository. Now the left of the divider mutates and the right of it doesn't,
 * and "Open in" is one menu because launching an editor is the least consequential thing here.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ActionRow(state: AppState, rv: RepoView) {
    val repo = rv.repo
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Commit opens a commit dialog (branch + message). Push/Fetch/Fast-forward all need a
        // remote — but Push works even without a tracking upstream (it pushes with `-u origin
        // HEAD`), so it's gated on hasRemote, not on upstream. Fetch just needs the remote
        // (offline/no-upstream is fine).
        ActionButton("Commit…", Tokens.onAccent, Tokens.accentFill, null, disabled = !repo.isGitRepo) {
            state.popup = Popup.Commit(repo.id)
        }
        ActionButton("Push", Tokens.text2, Tokens.surface, Tokens.borderD8, disabled = !repo.hasRemote) {
            state.popup = Popup.Confirm(
                "Push ${repo.name}?",
                if (repo.upstream != null) "Runs git push to ${repo.upstream}."
                else "Publishes “${repo.branch}” to origin and sets it as the upstream.",
                "Push", danger = false,
            ) { state.push(listOf(repo.id)) }
        }
        ActionButton("Fetch", Tokens.text2, Tokens.surface, Tokens.borderD8, disabled = !repo.hasRemote) {
            state.fetchRepo(repo.id)
        }
        // Fast-forward is only possible when strictly behind upstream (no local commits).
        val canFf = repo.upstream != null && repo.behind > 0 && repo.ahead == 0
        ActionButton("Fast Forward", Tokens.text2, Tokens.surface, Tokens.borderD8, disabled = !canFf) {
            state.popup = Popup.Confirm(
                "Fast-forward ${repo.name}?",
                "Advances “${repo.branch}” to ${repo.upstream} (${repo.behind} commit${if (repo.behind == 1) "" else "s"}). No local commits are lost.",
                "Fast Forward", danger = false,
            ) { state.fastForward(repo.id) }
        }
        // The read-only half travels as one unit, so it wraps to the next line together rather than
        // leaving a divider stranded at the end of the buttons. Centred against the buttons, which
        // are twice as tall as a bare text link.
        Row(
            Modifier.height(33.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.width(1.dp).height(22.dp).background(Tokens.borderDc))
            val hasChanges = repo.staged + repo.unstaged + repo.untracked > 0
            if (hasChanges) FlatAction("Diff") { state.openDiff(repo.id) }
            if (repo.isGitRepo) FlatAction("Log") { state.openRepoLog(repo.id) }
            // Out here rather than inside the menu, unlike the other launchers. A terminal in the
            // repo is the one thing reached often enough to be worth a click of its own — it's how
            // you do whatever this pane doesn't cover — and burying it behind a menu made the pane's
            // most-used escape hatch its least visible control.
            FlatAction("Terminal") { state.openTerminal(repo.id) }
            OpenInMenu(state, repo)
        }
        // Snooze is its own group: it neither changes the repository nor shows you anything about it
        // — it changes what the repo is allowed to tell *you*. That's a third kind of action, so it
        // gets a third compartment rather than being filed under one of the other two.
        Row(
            Modifier.height(33.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.width(1.dp).height(22.dp).background(Tokens.borderDc))
            // Carries how long it's silenced for, the way the list rows' chip does — so the button
            // reports the state as well as offering to change it. No "Resume now" beside it: the
            // snooze banner a few pixels below already has one, and two of them one line apart read
            // as two different actions.
            ActionButton(
                if (rv.snoozed) "☾ ${rv.repo.snoozedFor ?: "on"} ▾" else "☾ Snooze ▾",
                Tokens.snoozeBtnText, Tokens.snoozeBtnBg, Tokens.snoozeBtnBorder,
            ) { state.popup = Popup.Snooze(setOf(repo.id)) }
        }
    }
}

/**
 * "Open in ▾" — the old OPEN IN bar as a menu.
 *
 * That bar carried up to eight bordered buttons across two lines, permanently, for a set of actions
 * whose entire effect is to start another program. It was the widest thing in the pane and the least
 * consequential. Collapsed, it costs one click and gives the row back to the actions that change
 * something.
 */
@Composable
private fun OpenInMenu(state: AppState, repo: Repo) {
    var open by remember(repo.id) { mutableStateOf(false) }
    val below = with(LocalDensity.current) { 26.dp.roundToPx() }
    fun pick(act: () -> Unit): () -> Unit = { open = false; act() }
    Box {
        FlatAction("Open in ▾") { open = !open }
        if (open) {
            WindowPopup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, below),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                val shape = RoundedCornerShape(10.dp)
                Column(
                    Modifier.width(210.dp).shadow(10.dp, shape).clip(shape)
                        .background(Tokens.surface, shape).border(1.dp, Tokens.borderDc, shape)
                        .dismissOnEscape { open = false }
                        .padding(vertical = 5.dp),
                ) {
                    // No Terminal here — it graduated to the action row above.
                    OpenInItem("GitButler", UiFont, pick { state.openGitButler(repo.id) })
                    OpenInItem("Git Gui", UiFont, pick { state.openGitGui(repo.id) })
                    OpenInItem("IDE", UiFont, pick { state.openIde(repo.id) })
                    OpenInItem("Folder", UiFont, pick { state.openFolder(repo.id) })
                    if (repo.isGitHub && repo.webBase != null) {
                        Box(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                                .height(1.dp).background(Tokens.borderEd),
                        )
                        OpenInItem("Issues", UiFont, pick { state.openUrl("${repo.webBase}/issues") })
                        OpenInItem("Pull Requests", UiFont, pick { state.openUrl("${repo.webBase}/pulls") })
                        if (repo.hasWorkflows) {
                            OpenInItem("Actions", UiFont, pick { state.openUrl("${repo.webBase}/actions") })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenInItem(label: String, font: androidx.compose.ui.text.font.FontFamily, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        Modifier.fillMaxWidth()
            .background(if (hovered) Tokens.rowHoverBg else Color.Transparent)
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand).onTap(onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) { Txt(label, 12.sp, Tokens.text2, FontWeight.Medium, font = font) }
}

/**
 * Zone 5: what's changed in this checkout.
 *
 * One section, flat paths — a tree earns its keep in the diff viewer's sidebar, where a change can
 * touch fifty files across a deep hierarchy, but here the list is short by construction and which
 * package a file is in isn't the question.
 *
 * Staged files keep a group of their own, though, and that is not a taxonomy of git's index for its
 * own sake: "what will the next commit contain" is a different question from "what have I touched",
 * and it's the one you're asking right before pressing Commit. Told apart only by a word at the far
 * right of each row, the answer meant reading down the margin and counting. Told apart by a heading,
 * it's the shape of the list.
 *
 * The subheadings appear only when there is something staged. On a repo with nothing staged — the
 * common case — a lone "Not staged" heading over every row would be labelling the absence of a
 * distinction, so the section is just its files.
 */
@Composable
private fun ChangedFilesSection(state: AppState, rv: RepoView) {
    val repo = rv.repo
    val staged = repo.files.filter { it.section == "staged" }
    val rest = repo.files - staged.toSet()
    Column(Modifier.fillMaxWidth().padding(top = 20.dp).drawTopBorder(Tokens.sectionBorder).padding(top = 14.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Txt("Changed files", 12.5.sp, Tokens.text, FontWeight.Bold)
            // The repo's own count, not the list's: the list is capped and the count never is.
            Txt("— ${repo.changedCount}", 11.sp, Tokens.muted2)
            Spacer(Modifier.weight(1f))
            FlatAction("Open diff →", size = 11.5.sp) { state.openDiff(repo.id) }
        }
        if (staged.isEmpty()) {
            rest.forEach { ChangedFileRow(it, repo.dirtyFor) }
        } else {
            FileGroupLabel("Staged", staged.size, Tokens.stagedHdr, Tokens.stagedDot, top = 0)
            staged.forEach { ChangedFileRow(it, repo.dirtyFor) }
            if (rest.isNotEmpty()) {
                FileGroupLabel("Not staged", rest.size, Tokens.modifiedHdr, Tokens.modifiedDot, top = 10)
                rest.forEach { ChangedFileRow(it, repo.dirtyFor) }
            }
        }
        // Said plainly, because these rows are composed eagerly — one per entry, no lazy list — and
        // the cap is what keeps a repo with an un-ignored build directory from composing forty
        // thousand of them. Silence here would read as "that is all of them", which it is not.
        if (repo.filesTruncated) {
            Txt(
                "… and ${repo.changedCount - repo.files.size} more — open the diff to see them all",
                11.sp, Tokens.muted2, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** A subheading inside [ChangedFilesSection] — the square dot and coloured label the old per-state
 *  sections used, at a size that reads as part of the section rather than as another one. */
@Composable
private fun FileGroupLabel(label: String, count: Int, color: Color, dot: Color, top: Int) {
    Row(
        Modifier.padding(top = top.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp)).background(dot))
        Txt(label, 11.5.sp, color, FontWeight.Bold)
        Txt("$count", 10.5.sp, Tokens.muted2)
    }
}

@Composable
private fun ChangedFileRow(f: ChangedFile, dirtyFor: String?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(
            when (f.section) {
                "staged" -> Tokens.stagedDot
                "untracked" -> Tokens.untrackedDot
                else -> Tokens.modifiedDot
            },
            7,
        )
        PathTip(f.path, Modifier.weight(1f)) {
            Txt(f.path, 11.5.sp, Tokens.text2, font = MonoFont, maxLines = 1)
        }
        // How long it's been sitting, on the files that have been: an untracked file has no "since"
        // to report. "modified" rather than git's own "unstaged", which was an implementation detail
        // that only ever showed while it was a heading.
        Txt(
            if (f.section == "untracked") "untracked"
            else listOfNotNull(if (f.section == "staged") "staged" else "modified", dirtyFor).joinToString(" · "),
            10.5.sp, Tokens.muted2, maxLines = 1,
        )
    }
}

/**
 * The one setting the notifications outlook used to carry inline: whether this repo says anything
 * when its upstream advances.
 *
 * Off by default, and deliberately so — on a dashboard of thirty repos, "someone pushed" is the most
 * frequent event there is and the least likely to need you. Enabling re-baselines the behind count,
 * so the next *new* commit alerts rather than the backlog that already exists.
 */
@Composable
private fun UpstreamAlertRow(state: AppState, rv: RepoView) {
    val repo = rv.repo
    if (!repo.hasRemote || repo.upstream == null) return
    val on = state.notifyUpstreamEnabled(repo.id)
    Column(Modifier.fillMaxWidth().padding(top = 20.dp).drawTopBorder(Tokens.sectionBorder).padding(top = 14.dp)) {
        Row(
            Modifier.padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Txt("Alert when upstream advances", 12.5.sp, Tokens.text, FontWeight.Bold)
            InfoTip(
                "Fires a desktop notification when new commits land on ${repo.upstream}. Off by " +
                    "default: on a dashboard of many repos this is the most frequent thing that " +
                    "happens and rarely the thing that needs you. Turning it on starts from now, so " +
                    "the commits you're already behind by don't alert.",
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PresetPill("On", on, state.accent) { state.setNotifyUpstream(repo.id, true) }
            PresetPill("Off", !on, state.accent) { state.setNotifyUpstream(repo.id, false) }
        }
    }
}

/**
 * Zone 8: the note and the reminder, one line each.
 *
 * The note used to own a permanently-mounted 60px text area near the bottom of the pane — the
 * largest single element in it, empty on most repos, and below the things you actually came for. As
 * a line it states what the note says and hands editing to a click, which is the same bargain the
 * reminder already made.
 */
@Composable
private fun NoteLines(state: AppState, rv: RepoView) {
    val note = state.noteOf(rv.id)
    val rem = rv.repo.reminder
    Column(
        Modifier.fillMaxWidth().padding(top = 20.dp).drawTopBorder(Tokens.sectionBorder).padding(top = 14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt("✎", 12.sp, Tokens.muted2)
            Txt(
                note.ifBlank { "No note" }, 12.sp,
                if (note.isBlank()) Tokens.muted2 else Tokens.text2,
                italic = note.isNotBlank(), modifier = Modifier.weight(1f),
            )
            FlatAction(if (note.isBlank()) "Add" else "Edit", size = 12.sp) {
                state.popup = Popup.Note(rv.id)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt("◷", 12.sp, if (rem?.overdue == true) Tokens.remOverdue else Tokens.remTeal)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (rem == null) {
                    Txt("No reminder", 12.sp, Tokens.muted2)
                } else {
                    Txt("Reminder: ${rem.text}", 12.sp, Tokens.text2, modifier = Modifier.weight(1f, fill = false))
                    Txt("· ${rem.due}", 12.sp, if (rem.overdue) Tokens.remOverdue else Tokens.remTeal, FontWeight.Bold)
                }
            }
            FlatAction(if (rem == null) "Add" else "Edit", size = 12.sp) {
                state.popup = Popup.Remind(setOf(rv.id), rem?.text ?: "", null)
            }
            if (rem != null) {
                FlatAction("Clear", danger = true, size = 12.sp) { state.clearReminder(setOf(rv.id)) }
            }
        }
    }
}

/**
 * Zone 9: every setting the pane owns, behind one disclosure.
 *
 * Six sections used to sit open at the bottom of every repo's pane, and then Remove Repo. Together
 * they were most of the pane's height, on a screen whose job is to answer "what's going on in this
 * repo".
 *
 * What belongs here is only what *configures*: the stale threshold and its severity, whether issues
 * are polled and how loudly, the upstream-alert opt-in, the hidden-branch patterns, and Remove Repo.
 * Read once when a repo is added, then left alone. What does *not* belong here is anything that
 * reports — the issue list and the notifications outlook both went back to the work zones above,
 * because a prediction about the next few hours behind a collapsed chevron is a prediction nobody
 * reads. Snooze went to the action row for the same reason: it's a thing you do, not a thing you set.
 *
 * Collapsed it still has to say two things, which is what the header line is for: the summary names
 * what's inside, so the chevron isn't a mystery box, and the "N customized" chip says whether any of
 * it deviates from the defaults — the one fact you'd otherwise have to expand to learn.
 */
@Composable
private fun SettingsZone(state: AppState, rv: RepoView) {
    val open = state.settingsExpanded
    val customized = state.customizedSettings(rv.id)
    Column(Modifier.fillMaxWidth().padding(top = 20.dp).drawTopBorder(Tokens.sectionBorder).padding(top = 14.dp)) {
        Row(
            Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand)
                .onTap { state.toggleSettingsExpanded() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Txt(if (open) "▾" else "▸", 11.sp, Tokens.muted2)
            Txt("Settings", 12.5.sp, Tokens.secondary, FontWeight.Bold)
            Txt(
                "staleness · issues · alerts · hidden branches · remove repo", 11.sp, Tokens.muted2,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.weight(1f))
            if (customized > 0) {
                val p = Tokens.worktreeChip
                Pill(
                    "$customized customized", p.c, p.t, p.b,
                    fontSize = 10.5.sp, weight = FontWeight.SemiBold, radius = 20,
                    padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 9.dp, vertical = 2.dp),
                )
            }
        }
        if (!open) return@Column
        val repo = rv.repo

        // Per-repo "stale after N days" threshold (override the global default)
        if (repo.isGitRepo) StaleThresholdRow(state, rv)

        // Whether open issues are polled here, how loudly they count, and which of them count
        if (state.issuesSupported(repo)) IssuesSettings(state, rv)

        // Whether new upstream commits raise a desktop notification
        UpstreamAlertRow(state, rv)

        // Per-repo patterns for the branches the lists keep out of the way
        if (repo.isGitRepo) HiddenBranchesRow(state, rv)

        // Curation: stop tracking this repo (non-destructive)
        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp).drawTopBorder(Tokens.sectionBorder).padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The row already says "doesn't touch the repo" beside the button, but that caption is
            // terse and sits at the far right where a narrow pane can push it out of view. The
            // tooltip is where there is room to name the things people are actually afraid of
            // losing — history, uncommitted work — and to say that it is reversible.
            HoverTip(REMOVE_KEEPS_FILES) {
                Txt("✕ Remove Repo", 12.sp, Tokens.redText, FontWeight.SemiBold,
                    modifier = Modifier.onTap {
                        state.popup = Popup.Confirm(
                            "Remove ${repo.name}?",
                            "Stops tracking it. Doesn't touch the repo on disk.",
                            "Remove", danger = true,
                        ) { state.removeRepo(repo.id) }
                    })
            }
            Spacer(Modifier.weight(1f))
            Txt("stops tracking · doesn't touch the repo", 11.sp, Tokens.muted2)
        }
    }
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
                Modifier.clip(shape).border(1.dp, Tokens.borderCb, shape)
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
 *
 * A [suggest] that always returns nothing reduces this to a plain type-and-commit field — no ghost,
 * no cycling, no hint — which is how the branch-hide pattern editor uses it.
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
    val edit = remember(resetKey) { TextEditState("") }
    var cycle by remember(resetKey) { mutableStateOf(0) }
    val typed = edit.text
    fun commit(v: String) { onCommit(v); edit.setText(""); cycle = 0 }
    // Every existing tag the typed text can complete to (prefix matches first, then substring),
    // e.g. "kot" or "kotlin" both surface "lang:kotlin". Tab cycles when there are several
    // (Shift-Tab reverses) and accepts when only one is left; Enter or → also accept.
    val candidates = suggest(typed)
    val n = candidates.size
    val idx = if (n > 0) cycle.mod(n) else 0
    val active = candidates.getOrNull(idx)
    // Accept the active completion into the field, cursor at the end.
    fun accept() { active?.let { edit.setText(it); cycle = 0 } }
    val atEnd = edit.value.selection.collapsed && edit.value.selection.end == typed.length
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
            Modifier.width(fieldWidth).clip(RoundedCornerShape(8.dp)).background(Tokens.surface)
                .border(1.dp, Tokens.borderD8, RoundedCornerShape(8.dp))
                .padding(horizontal = 9.dp, vertical = 4.dp),
        ) {
            if (typed.isEmpty()) Txt(placeholder, 11.5.sp, Tokens.muted2, font = MonoFont)
            BasicTextField(
                value = edit.value,
                onValueChange = { nv -> if (nv.text != edit.text) cycle = 0; edit.value = nv },
                singleLine = true,
                textStyle = TextStyle(fontSize = 11.5.sp, fontFamily = MonoFont, color = Tokens.text),
                cursorBrush = SolidColor(accent),
                visualTransformation = completion,
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    // Selects the typed text only — the greyed completion around it isn't in the
                    // field's value, so there is nothing there to select.
                    .selectAllOnDoubleClick { edit.selectAll() }
                    .onPreviewKeyEvent { ev ->
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
            Modifier.clip(RoundedCornerShape(8.dp)).background(Tokens.accentFill)
                .onTap { commit(active ?: typed) }.padding(horizontal = 10.dp, vertical = 4.dp),
        ) { Txt("Add", 11.5.sp, Tokens.onAccent, FontWeight.Bold) }
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
private fun CleanBanner(rv: RepoView) {
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
            BannerActionPill("⑃ Diff") {
                state.openRangeDiff(repo.id, "HEAD", upstream, "Incoming — $upstream ($commits)", "HEAD → $upstream")
            }
            BannerActionPill("☰ Log") {
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

/**
 * Work set aside on this repo. Stashes are easy to forget and expensive to forget, so the section
 * earns its place high in the panel — but each stash used to be a bordered, purple-tinted card,
 * which made two of them louder than everything below including the branch you're actually on.
 * Prominence belongs to the *section*, which the ⚑ and the purple heading still carry; the rows
 * inside it are list rows like every other list in this panel.
 */
@Composable
private fun StashSection(state: AppState, id: String, stashes: List<Stash>) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp).drawTopBorder(Tokens.sectionBorder).padding(top = 14.dp)) {
        Row(
            Modifier.padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Txt("⚑ Stashes", 12.5.sp, Tokens.purple, FontWeight.Bold)
            Txt("— ${stashes.size}", 11.sp, Tokens.muted2)
        }
        stashes.forEach { st ->
            HoverRow { hovered ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Txt(st.label, 11.sp, Tokens.purple, FontWeight.SemiBold, font = MonoFont)
                        // Gives way to the actions when they arrive, rather than pushing them off —
                        // the same bargain the branch rows make with a long upstream ref.
                        Txt(st.msg, 12.sp, Tokens.text2, modifier = Modifier.weight(1f, fill = false))
                    }
                    RowActions(hovered) {
                        RowAction("Diff") { state.openStashDiff(id, st.label) }
                        RowAction("Apply") {
                            state.popup = Popup.Confirm(
                                "Apply ${st.label}?",
                                "Restores this stash's changes into the working tree (the stash is kept).",
                                "Apply", danger = false,
                            ) { state.stashApply(id, st.label) }
                        }
                        RowAction("Drop", danger = true) {
                            state.popup = Popup.Confirm(
                                "Drop ${st.label}?", "Permanently deletes this stash.", "Drop", danger = true,
                            ) { state.stashDrop(id, st.label) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubmodulesSection(state: AppState, rv: RepoView) {
    LaunchedEffect(rv.id) { state.loadSubmodules(rv.id) }
    if (state.submodulesRepo != rv.id || state.submodules.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 20.dp).drawTopBorder(Tokens.sectionBorder).padding(top = 14.dp)) {
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
private fun SubmoduleRow(state: AppState, id: String, s: Submodule) {
    val subFullPath = java.io.File(id, s.path).absolutePath
    val tracked = state.isTracked(subFullPath)
    val busy = state.submodulesBusy
    HoverRow(vertical = 6) { hovered ->
        // Line 1: path · recorded pointer · status badge
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(s.path, 12.5.sp, Tokens.text2, FontWeight.SemiBold, font = MonoFont, maxLines = 1, modifier = Modifier.weight(1f))
            if (s.initialized && s.recorded.isNotEmpty()) Txt("@${s.recorded.take(7)}", 10.5.sp, Tokens.muted2, font = MonoFont)
            when {
                !s.initialized -> QuietBadge("not initialized")
                s.statusChar == 'U' -> BranchBadge("conflict", Tokens.redText, Tokens.tintRed)
                s.behind > 0 -> BranchBadge("behind ${s.behind}", Tokens.snoozeBtnText, Tokens.snoozeBtnBg)
                s.statusChar == '+' -> BranchBadge("moved", state.accent, Tokens.tintBlue)
                else -> QuietBadge("up to date")
            }
        }
        // Line 2: → target url (tooltip)
        PathTip(s.url, Modifier.padding(top = 2.dp)) {
            Txt("→ ${s.url}", 11.sp, Tokens.muted, font = MonoFont, maxLines = 1)
        }
        // Uncommitted changes inside the submodule — surfaced prominently.
        if (s.dirtyCount > 0) {
            Txt(
                "⚠ ${s.dirtyCount} uncommitted change${if (s.dirtyCount == 1) "" else "s"} inside — commit or stash them",
                11.sp, Tokens.amber, FontWeight.SemiBold, modifier = Modifier.padding(top = 3.dp),
            )
        }
        // Actions (wrap as the pane narrows)
        RowActions(hovered, Modifier.fillMaxWidth().padding(top = 4.dp)) {
            if (!s.initialized) {
                if (!busy) RowAction("Init") { state.initSubmodule(id, s.path) }
            } else {
                if (!busy) RowAction("Fetch") { state.fetchSubmodule(id, s.path) }
                if (s.behind > 0 && !busy) RowAction("Update") { state.updateSubmodulePointer(id, s.path) }
                // Diff against whatever branch this submodule tracks (main, or a configured
                // submodule.<name>.branch like develop) — not necessarily mainline.
                if (s.behind > 0 && s.remoteRef != null) {
                    val shortRef = s.remoteRef.substringAfterLast('/')
                    RowAction("Diff $shortRef") { state.openSubmoduleDiff(id, s) }
                    RowAction("Log $shortRef") { state.openSubmoduleLog(id, s) }
                }
                if (!busy) RowAction("Deinit", danger = true) {
                    state.popup = Popup.Confirm(
                        "Deinitialize ${s.path}?",
                        "Removes the submodule's working tree (its files). You can re-init later; the pointer in the parent is untouched.",
                        "Deinit", danger = true,
                    ) { state.deinitSubmodule(id, s.path) }
                }
            }
            if (tracked) RowAction("Goto Repo") {
                state.trackedRepoAt(subFullPath)?.let { state.selectedId = it }
            }
            else if (s.initialized && !busy) RowAction("Add Repo") { state.trackSubmodule(id, s) }
        }
    }
}

@Composable
private fun BranchesSection(state: AppState, rv: RepoView) {
    LaunchedEffect(rv.id) { state.loadBranches(rv.id) }
    if (state.branchesRepo != rv.id) return
    // Switching branches would clobber uncommitted work, so it's only offered on a clean tree.
    val clean = with(rv.repo) { staged + unstaged + untracked == 0 }
    // One compiled pattern list for both lists below — see Meta.compileHidePatterns for why the
    // same patterns can serve local and remote names. Recompiled only when the patterns change.
    val patterns = state.hideBranchPatterns(rv.id)
    val hide = remember(patterns) { Meta.compileHidePatterns(patterns) }
    val hidden = state.branches.filter { Meta.isBranchHidden(it.name, hide) }
    val shown = if (state.showHiddenBranches) state.branches else state.branches - hidden.toSet()
    Column(Modifier.fillMaxWidth().padding(top = 20.dp).drawTopBorder(Tokens.sectionBorder).padding(top = 14.dp)) {
        Row(
            Modifier.padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Txt("Branches", 12.5.sp, Tokens.text, FontWeight.Bold)
            if (shown.isNotEmpty()) Txt("— ${shown.size}", 11.sp, Tokens.muted2)
            HiddenBranchToggle(state, hidden.size)
        }
        if (state.branchesLoading && state.branches.isEmpty()) {
            Txt("Loading…", 12.sp, Tokens.muted2, modifier = Modifier.padding(vertical = 4.dp))
        }
        shown.forEach { b ->
            BranchRow(state, rv.id, b, clean, rv.repo.hasRemote, hidden = b in hidden)
        }
        RemoteBranchesSection(state, rv.id, clean, hide)
    }
}

/**
 * The "· N hidden" indicator and its Show/Hide switch, on a branch list's header row. Nothing at
 * all when the patterns matched nothing, so a repo without bot branches never learns the feature
 * exists — and a repo with them says so on the line where the count would otherwise look wrong.
 */
@Composable
private fun HiddenBranchToggle(state: AppState, hiddenCount: Int) {
    if (hiddenCount == 0) return
    Txt("· $hiddenCount hidden", 11.sp, Tokens.muted2)
    Txt(
        if (state.showHiddenBranches) "Hide" else "Show",
        11.sp, state.accent, FontWeight.SemiBold,
        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).onTap { state.toggleShowHiddenBranches() },
    )
}

@Composable
private fun BranchRow(
    state: AppState,
    id: String,
    b: Branch,
    clean: Boolean,
    hasRemote: Boolean,
    hidden: Boolean = false,
) {
    // git holds a branch in exactly one working tree at a time: switching to one that's checked
    // out elsewhere fails, and so does deleting it. Both actions come off the row rather than
    // being offered and then refused — the badge on line 1 says where the branch went instead.
    val canSwitch = !b.isCurrent && !b.inOtherWorktree && !state.switchingBranch
    val canDelete = !b.isCurrent && !b.isMainline && !b.inOtherWorktree
    // Whether line 2's tracking status has something the row can actually do about it. "⚠ no
    // upstream" was the sharpest case — a warning with no way out of it, on a branch the
    // repo-level Push can't reach unless you happen to be standing on it — but a branch sitting
    // ahead of its upstream is the same dead end one line down, so both get the one action.
    //
    // The states left out are the ones a plain push can't fix: diverged needs a force, and a
    // hover lane a stray click can find is the last place to offer one. "upstream gone" means
    // someone deleted the remote branch, and re-creating it silently would undo that.
    val pushLabel = when {
        !hasRemote || b.upstreamGone -> null
        b.upstream == null -> "Publish"
        b.upstreamAhead > 0 && b.upstreamBehind == 0 -> "Push"
        else -> null
    }
    val pushing = state.pushingBranch == b.name
    HoverRow { hovered ->
        // Line 1: branch name · mainline-relationship badge · ahead-of-mainline
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(
                b.name, 12.5.sp,
                if (b.isCurrent) Tokens.text else Tokens.text2,
                if (b.isCurrent) FontWeight.Bold else FontWeight.Normal,
                font = MonoFont, maxLines = 1, modifier = Modifier.weight(1f),
            )
            // Only ever on screen while "Show hidden" is on, so it reads as "this is one of the
            // ones you asked to see" rather than as a state the row is in.
            if (hidden) QuietBadge("hidden")
            when {
                b.isCurrent -> BranchBadge("current", Tokens.cleanText, Tokens.cleanBg)
                // "mainline" and "up to date" are what most rows in a healthy repo say, so they're
                // stated rather than announced — a tinted pill on every row is a pattern the eye
                // learns to skip, taking the ones that do mean something with it.
                b.isMainline -> QuietBadge("mainline")
                // Merged wins over stale: a branch already folded into mainline is "done", not neglected.
                b.merged -> BranchBadge("merged", Tokens.purple, Tokens.tintPurple)
                b.stale -> BranchBadge("stale", Tokens.amber, Tokens.tintAmber)
                b.behind > 0 -> BranchBadge("behind ${b.behind}", Tokens.snoozeBtnText, Tokens.snoozeBtnBg)
                else -> QuietBadge("up to date")
            }
            // Separate from the relationship badge above, not folded into it: "merged" and "checked
            // out in another tree" are independent facts, and this is the one that explains why the
            // row's actions are missing.
            if (b.inOtherWorktree) {
                HoverTip(
                    "Checked out in the “${b.worktreeName}” worktree (${b.worktreePath}). " +
                        "A branch can only live in one working tree, so it can't be switched to or " +
                        "deleted from here — open that worktree instead.",
                ) { BranchBadge("in ⑂ ${b.worktreeName}", state.accent, Tokens.tintBlue) }
            }
            if (b.ahead > 0 && !b.isMainline) Txt("↑${b.ahead}", 11.sp, state.accent, FontWeight.SemiBold, font = MonoFont)
        }
        // Line 2: tracking status (left) · the row's actions (right, on hover)
        Row(
            Modifier.fillMaxWidth().padding(top = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            // Wider than the gap between the actions themselves, so the lane reads as its own
            // group arriving on the row rather than as more words on the end of the status line.
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Weighted so a long upstream ref gives way to the actions instead of pushing them off
            // the row — the sync verdict beside it is the short part, and the part worth keeping.
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (b.upstream == null) {
                    Txt("⚠ no upstream", 11.sp, Tokens.muted2)
                } else {
                    Txt("⇄ ${b.upstream}", 11.sp, Tokens.muted, font = MonoFont,
                        modifier = Modifier.weight(1f, fill = false))
                    when {
                        b.upstreamGone -> Txt("· upstream gone", 11.sp, Tokens.redText, FontWeight.SemiBold)
                        b.upstreamAhead > 0 && b.upstreamBehind > 0 ->
                            Txt("· ↑${b.upstreamAhead} ↓${b.upstreamBehind} diverged", 11.sp, Tokens.amber, FontWeight.SemiBold, font = MonoFont)
                        b.upstreamAhead > 0 -> Txt("· ↑${b.upstreamAhead} ahead", 11.sp, state.accent, FontWeight.SemiBold, font = MonoFont)
                        b.upstreamBehind > 0 -> Txt("· ↓${b.upstreamBehind} behind", 11.sp, Tokens.behind, FontWeight.SemiBold, font = MonoFont)
                        else -> Txt("· ✓ in sync", 11.sp, Tokens.cleanText, FontWeight.SemiBold)
                    }
                }
            }
            RowActions(hovered) {
                CopyAction(b.name)
                if (!b.isMainline) {
                    RowAction("Diff") { state.openBranchDiff(id, b.name, b.name) }
                    RowAction("Log") { state.openBranchLog(id, b.name, b.name) }
                }
                // Between the read-only actions and the ones that move you: this changes the
                // remote, not where you're standing or what's on disk.
                if (pushLabel != null) {
                    HoverTip(
                        if (b.upstream == null) {
                            "Pushes “${b.name}” to origin and sets it as the upstream. The branch " +
                                "doesn't have to be checked out — your working tree isn't touched."
                        } else {
                            "Sends ${b.upstreamAhead} commit${if (b.upstreamAhead == 1) "" else "s"} " +
                                "to ${b.upstream}. Your working tree isn't touched."
                        },
                    ) {
                        RowAction(if (pushing) "Pushing…" else pushLabel) {
                            if (!pushing) state.pushBranch(id, b)
                        }
                    }
                }
                if (canSwitch) RowAction("Switch") {
                    if (clean) state.switchBranch(id, b.name)
                    else state.popup = Popup.Confirm(
                        "Switch to ${b.name}?",
                        "You have uncommitted changes — git will carry them into “${b.name}”, or refuse if they'd conflict.",
                        "Switch anyway", danger = false,
                    ) { state.switchBranch(id, b.name) }
                }
                // Where "Switch" would have been: the branch is already checked out somewhere, so the
                // useful move is to go there. Only when that worktree is tracked — offering to open an
                // untracked one is the "+ Add Repo" flow, and it belongs on the worktree list, not here.
                if (b.inOtherWorktree) {
                    b.worktreePath?.let { p ->
                        state.trackedRepoAt(p)?.let { t -> RowAction("Goto Repo") { state.selectedId = t } }
                    }
                }
                // Joined the other actions rather than keeping its own ✕ up on line 1: a delete
                // glyph standing permanently beside every branch name is both the loudest thing in
                // the list and the one thing you least want a stray click to find.
                if (canDelete) RowAction("Delete", danger = true) {
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
                }
            }
        }
    }
}

@Composable
private fun RemoteBranchesSection(state: AppState, id: String, clean: Boolean, hide: List<Regex>) {
    val hidden = state.remoteBranches.filter { Meta.isBranchHidden(it.name, hide) }
    val shown = if (state.showHiddenBranches) state.remoteBranches else state.remoteBranches - hidden.toSet()
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(
            Modifier.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // The disclosure triangle and label expand the section; the Show/Hide beside them is
            // its own target, so peeking at bot branches doesn't collapse the list you're in.
            Row(
                Modifier.onTap { state.toggleRemoteBranches(id) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Txt(if (state.showRemoteBranches) "▾" else "▸", 11.sp, Tokens.muted2)
                Txt("Remote branches", 12.sp, Tokens.secondary, FontWeight.SemiBold)
                if (shown.isNotEmpty()) Txt("— ${shown.size}", 11.sp, Tokens.muted2)
            }
            // Only once the list is open: the count is meaningless next to a collapsed section,
            // and before the first expand the remote list hasn't been loaded to count at all.
            if (state.showRemoteBranches) HiddenBranchToggle(state, hidden.size)
        }
        if (state.showRemoteBranches) {
            if (state.remoteBranches.isEmpty()) {
                Txt("No remote branches", 11.sp, Tokens.muted2, modifier = Modifier.padding(vertical = 4.dp))
            }
            shown.forEach { rb -> RemoteBranchRow(state, id, rb, clean, hidden = rb in hidden) }
        }
    }
}

@Composable
private fun RemoteBranchRow(
    state: AppState,
    id: String,
    rb: RemoteBranch,
    clean: Boolean,
    hidden: Boolean = false,
) {
    // Checking out a remote branch lands on its local counterpart, so it hits the same wall when
    // that local branch is held by another working tree. The local list is already loaded above.
    val heldBy = state.branches.firstOrNull { it.name == rb.shortName }?.takeIf { it.inOtherWorktree }
    val canSwitch = !state.switchingBranch && heldBy == null
    val isMainline = rb.shortName == "main" || rb.shortName == "master"
    val deleting = state.deletingRemoteBranch == rb.name
    HoverRow(vertical = 4) { hovered ->
        // Line 1: remote ref · merged · tracked
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(rb.name, 12.sp, Tokens.text2, font = MonoFont, maxLines = 1, modifier = Modifier.weight(1f))
            if (hidden) QuietBadge("hidden")
            if (rb.merged) BranchBadge("merged", Tokens.purple, Tokens.tintPurple)
            // Having a local counterpart is the ordinary case in a repo you work in, so it's said
            // quietly; the notable half of the pair is the branch you *haven't* got, and that one
            // announces itself through the "Checkout" action rather than a badge.
            if (rb.hasLocal) QuietBadge("tracked")
            heldBy?.let { h ->
                HoverTip(
                    "Its local branch “${rb.shortName}” is checked out in the “${h.worktreeName}” " +
                        "worktree (${h.worktreePath}), so it can't be checked out here.",
                ) { BranchBadge("in ⑂ ${h.worktreeName}", state.accent, Tokens.tintBlue) }
            }
        }
        // Line 2: author · time · the row's actions (on hover)
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Txt("${rb.author} · ${rb.lastRelative}", 10.5.sp, Tokens.muted2, maxLines = 1,
                modifier = Modifier.weight(1f))
            RowActions(hovered) {
                // The full ref, as the row above shows it — copying something other than what's on
                // screen would be a surprise. Checkout is the button for wanting the short name.
                CopyAction(rb.name)
                if (!isMainline) {
                    RowAction("Diff") { state.openBranchDiff(id, rb.name, rb.name) }
                    RowAction("Log") { state.openBranchLog(id, rb.name, rb.name) }
                }
                if (canSwitch) RowAction(if (rb.hasLocal) "Switch" else "Checkout") {
                    if (clean) state.checkoutRemoteBranch(id, rb)
                    else state.popup = Popup.Confirm(
                        if (rb.hasLocal) "Switch to ${rb.shortName}?" else "Check out ${rb.shortName}?",
                        "You have uncommitted changes — git will carry them over, or refuse if they'd conflict.",
                        if (rb.hasLocal) "Switch anyway" else "Checkout anyway", danger = false,
                    ) { state.checkoutRemoteBranch(id, rb) }
                }
                // Absent on the branches a workflow shares — main, develop, next, pu and the rest of
                // [Meta.INTEGRATION_BRANCHES]. Deleting one of those on the remote isn't a mistake
                // you make for yourself, and no confirmation wording makes an accidental click on a
                // hover lane worth the blast radius, so the action simply isn't there to hit.
                if (!rb.integration) {
                    HoverTip(
                        if (rb.merged) {
                            "Deletes “${rb.shortName}” from ${rb.remote}. Its commits are already in " +
                                "mainline, so nothing is lost" +
                                if (rb.hasLocal) " — your local branch stays." else "."
                        } else {
                            "Deletes “${rb.shortName}” from ${rb.remote}. It hasn't been merged into " +
                                "mainline, so its commits survive only where they're still checked out."
                        },
                    ) {
                        RowAction(if (deleting) "Deleting…" else "Delete", danger = true) {
                            if (deleting) return@RowAction
                            // Same rule as the local list one section up: merged means the commits
                            // are safe in mainline, so the delete just goes. Unmerged is the case
                            // that can lose work — and unlike a local branch this one is other
                            // people's too, so the confirmation says where the commits went.
                            if (rb.merged) {
                                state.deleteRemoteBranch(id, rb)
                            } else {
                                state.popup = Popup.Confirm(
                                    "Delete “${rb.name}” from ${rb.remote}?",
                                    "It isn't merged into mainline, and deleting it on the remote " +
                                        "removes it for everyone — git has no undo for this. Its " +
                                        "commits are only recoverable from a clone that still has " +
                                        (if (rb.hasLocal) "them, including your local “${rb.shortName}”." else "them."),
                                    "Delete on ${rb.remote}", danger = true,
                                ) { state.deleteRemoteBranch(id, rb) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A bordered action button inside a banner. Kept in pill form where the list rows dropped theirs:
 * a banner appears once, for one repo, and its buttons are the reason it's on screen — nothing
 * about it repeats, so nothing about it needs quieting down.
 */
@Composable
private fun BannerActionPill(label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(Tokens.tintBlue)
            .border(1.dp, Tokens.accentBorder, RoundedCornerShape(6.dp))
            .pointerHoverIcon(PointerIcon.Hand).onTap(onClick)
            .padding(horizontal = 9.dp, vertical = 2.dp),
    ) { Txt(label, 10.5.sp, Tokens.accent, FontWeight.SemiBold) }
}

/**
 * Open issues and pull requests from GitHub — the list itself.
 *
 * A work zone, not a setting. What's waiting on you is the same kind of fact as what's uncommitted
 * or what's sitting in another worktree, and it was the one such fact that had been filed away
 * behind a disclosure alongside the knobs that govern it. The knobs stayed there; see
 * [IssuesSettings].
 *
 * Only mounted for a remote that can actually be queried (see the call site). Within that, it
 * still renders when tracking is off or `gh` isn't usable — that's exactly when the user needs
 * telling *why* there are no counts, and each of those states is fixable. The distinction that
 * matters: "we can't read this" is worth saying, "this forge isn't supported" is not.
 *
 * The list is capped: this is a triage dashboard, and the Issues/Pull Requests links in "Open in"
 * are one click away for the full picture.
 */
@Composable
private fun IssuesSection(state: AppState, rv: RepoView) {
    val tracked = state.issuesTracked(rv.repo)
    val gh = rv.gh
    Column(Modifier.fillMaxWidth().padding(top = 20.dp).drawTopBorder(Tokens.sectionBorder).padding(top = 14.dp)) {
        Row(
            Modifier.padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Txt("Issues & pull requests", 12.5.sp, Tokens.text, FontWeight.Bold)
            if (gh != null && gh.error == null && gh.open > 0) {
                Txt("— ${gh.open}${if (gh.countIsFloor) "+" else ""} open", 11.sp, Tokens.muted2)
            }
            Spacer(Modifier.weight(1f))
            if (tracked && !state.githubFetching && state.githubFetchedEpoch > 0L) {
                Txt(
                    "checked ${Meta.compactDuration(System.currentTimeMillis() - state.githubFetchedEpoch)} ago",
                    10.5.sp, Tokens.muted2,
                )
            }
            if (tracked) {
                Txt(
                    if (state.githubFetching) "Checking…" else "Check now",
                    12.sp, state.accent, FontWeight.SemiBold,
                    modifier = if (state.githubFetching) Modifier else Modifier.onTap { state.refreshGitHub() },
                )
            }
        }

        // Why there's nothing to show. Each of these is actionable, so say what to do — a silent
        // empty section would read as "no open issues", which is a different and wrong fact.
        val blocker: String? = when {
            !tracked && state.issuesTrackedOverride(rv.id) == false ->
                "Not tracked for this repo. Turn it on under Settings to count open issues here."
            !tracked -> "Issue tracking is off by default. Turn it on for this repo under Settings."
            state.githubStatus is GitHub.Status.Missing ->
                "Needs the GitHub CLI. Install `gh` and run `gh auth login` — GitVantage reads " +
                    "issues through it, so it never has to hold a token of your own."
            state.githubStatus is GitHub.Status.NoAuth ->
                "The GitHub CLI isn't logged in. Run `gh auth login` in a terminal, then Check now."
            state.githubStatus is GitHub.Status.Failed ->
                "GitHub CLI error: ${(state.githubStatus as GitHub.Status.Failed).message}"
            gh?.error != null -> "Couldn't read this repo: ${gh.error}"
            gh == null -> if (state.githubFetching) "Checking…" else "Not checked yet."
            else -> null
        }
        if (blocker != null) {
            Txt(blocker, 11.5.sp, Tokens.muted, maxLines = 4)
        } else if (gh != null && gh.open == 0) {
            Txt(
                if (state.githubMineOnly) "Nothing open that involves you." else "No open issues or pull requests.",
                11.5.sp, Tokens.muted,
            )
        } else if (gh != null) {
            // Awaiting-you first: it's the reason this section exists. Within each half the fetch
            // already ordered by most-recently-updated.
            val ordered = gh.items.sortedByDescending { it.awaitingYou }
            val shown = ordered.take(ISSUE_ROWS)
            // Same gate the branch lists use: checking out a PR moves the working tree.
            val clean = with(rv.repo) { staged + unstaged + untracked == 0 }
            shown.forEach { IssueRow(state, rv.id, it, clean) }
            // Counted against the header's total, not the fetched list — on a busy repo those
            // differ by hundreds, and "+94 more" under a "980 open" header reads as a bug. When
            // the total is itself a floor, drop the number rather than print one that's wrong.
            val remaining = gh.open - shown.size
            if (remaining > 0 || gh.countIsFloor) {
                Txt(
                    if (gh.countIsFloor) "More on GitHub →" else "+ $remaining more on GitHub →",
                    11.5.sp, state.accent, FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp).onTap { state.openUrl("${rv.repo.webBase}/issues") },
                )
            }
            if (gh.truncated) {
                Txt(
                    if (gh.countIsFloor) {
                        "Too many open to check them all — these counts cover the most recently " +
                            "updated only, so both may be higher."
                    } else {
                        "Too many open to check them all — the open counts are exact, but " +
                            "“awaiting you” covers the most recently updated only, so it may be higher."
                    },
                    10.5.sp, Tokens.muted2, maxLines = 3,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * How this repo's issue tracking behaves — whether to poll it at all, how loudly the results count,
 * and which issues count.
 *
 * The settings half of the split. These are read once when a repo is added and then left alone, which
 * is what a disclosure is for; the list they govern is checked daily, which is what a work zone is
 * for. Keeping the two together meant one of them was always in the wrong place.
 */
@Composable
private fun IssuesSettings(state: AppState, rv: RepoView) {
    val tracked = state.issuesTracked(rv.repo)
    Column(Modifier.fillMaxWidth().padding(top = 20.dp).drawTopBorder(Tokens.sectionBorder).padding(top = 14.dp)) {
        Row(
            Modifier.padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Txt("Track issues & pull requests", 12.5.sp, Tokens.text, FontWeight.Bold)
            if (state.issuesTrackedOverride(rv.id) == null) Txt("· default", 11.sp, Tokens.muted2)
        }
        // Tracking on/off for this repo, mirroring the stale threshold's preset/Default shape.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val override = state.issuesTrackedOverride(rv.id)
            PresetPill("Track", override == true, state.accent) { state.setIssuesTracked(rv.id, true) }
            PresetPill("Never", override == false, state.accent) { state.setIssuesTracked(rv.id, false) }
            PresetPill("Default", override == null, state.accent) { state.setIssuesTracked(rv.id, null) }
            Txt(
                if (state.githubEnabled) "· default is on" else "· default is off",
                11.sp, Tokens.muted2,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }

        // What the repo is to you. Above the severity choice because it decides what is *fetched*
        // rather than how loudly it's drawn — everything below it applies to whatever this leaves.
        if (tracked) {
            val role = state.repoRole(rv.id)
            Row(
                Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Txt("This repo is", 12.5.sp, Tokens.text, FontWeight.Bold)
                InfoTip(
                    "On a repo you maintain, every open issue and PR is fetched, and anyone's " +
                        "unanswered comment counts as awaiting you — including a new issue nobody " +
                        "has replied to yet. On one you only contribute to, just the issues and " +
                        "PRs you opened are fetched at all, which is also far cheaper on a large " +
                        "tracker. Reacting to the latest comment retires it either way. " +
                        "Auto reads your push access: it's yours if you can push to it.",
                )
            }
            Row(
                Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PresetPill("Mine", role == Meta.ROLE_MINE, state.accent) {
                    state.setRepoRole(rv.id, Meta.ROLE_MINE)
                }
                PresetPill("Contributing", role == Meta.ROLE_CONTRIBUTING, state.accent) {
                    state.setRepoRole(rv.id, Meta.ROLE_CONTRIBUTING)
                }
                PresetPill("Auto", role == null, state.accent) { state.setRepoRole(rv.id, null) }
                if (role == null) {
                    // Nothing fetched yet reads as neither — saying "contributing" there would be
                    // a guess about a repo the classify pass hasn't reached.
                    val label = when (state.repoRoleInferred(rv.repo)) {
                        true -> "· reading as mine"
                        false -> "· reading as contributing"
                        null -> "· not classified yet"
                    }
                    Txt(label, 11.sp, Tokens.muted2, modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
        }

        // How loud open issues are here. Same two-way choice as staleness, and hidden for the
        // same reason — with tracking off there is nothing for the severity to apply to.
        if (tracked) {
            val important = state.issuesImportant(rv.id)
            Row(
                Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Txt("Treat open issues as", 12.5.sp, Tokens.text, FontWeight.Bold)
                InfoTip(
                    "Informational shows open issues in blue and only turns yellow when one is " +
                        "actually waiting on you. Important escalates both a step — open issues " +
                        "show yellow, and anything awaiting you shows red — and sorts the repo " +
                        "higher under the Attention ordering.",
                )
            }
            Row(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PresetPill("Informational", !important, state.accent) { state.setIssuesImportant(rv.id, false) }
                PresetPill("Important", important, state.accent) { state.setIssuesImportant(rv.id, true) }
            }
        }

        if (tracked) IgnoredLabelsRow(state, rv)

        // The "only mine" filter is app-wide, but this is the only screen where its effect is
        // visible, so it's reachable from here rather than buried in a preferences dialog.
        Row(
            Modifier.padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Txt("Count", 12.5.sp, Tokens.text, FontWeight.Bold)
            PresetPill("All issues", !state.githubMineOnly, state.accent) { state.setMineOnly(false) }
            PresetPill("Only mine", state.githubMineOnly, state.accent) { state.setMineOnly(true) }
            InfoTip(
                "“Only mine” counts just the issues and PRs you're involved in — you opened it, " +
                    "you're assigned, your review is requested, or the last comment mentions you. " +
                    "This setting applies to every repo, not only this one.",
            )
        }
    }
}

/** Open issues/PRs listed per repo before collapsing into a "+ N more" link. */
private const val ISSUE_ROWS = 6

@Composable
private fun IssueRow(state: AppState, id: String, item: GitHub.Item, clean: Boolean) {
    // Awaiting-you rows get the amber treatment the badge uses, so the reason you opened the
    // panel is findable without reading every title.
    val bg = if (item.awaitingYou) Tokens.tintAmber else Tokens.panelF7
    val border = if (item.awaitingYou) Tokens.snoozeBtnBorder else Tokens.borderE6
    Row(
        Modifier.fillMaxWidth().padding(top = 5.dp).clip(RoundedCornerShape(8.dp))
            .background(bg).border(1.dp, border, RoundedCornerShape(8.dp))
            .pointerHoverIcon(PointerIcon.Hand).onTap { state.openUrl(item.url) }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Txt(if (item.isPr) "⑂" else "◎", 12.sp, if (item.awaitingYou) Tokens.amber else Tokens.muted)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(item.title, 12.sp, Tokens.text2, FontWeight.Medium, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("#${item.number}", 10.5.sp, Tokens.muted2, font = MonoFont)
                if (item.isDraft) Txt("draft", 10.5.sp, Tokens.muted2)
                if (item.author.isNotEmpty()) Txt("by ${item.author}", 10.5.sp, Tokens.muted2, maxLines = 1)
                item.reason?.let { Txt("· $it", 10.5.sp, Tokens.snoozeBtnText, FontWeight.SemiBold, maxLines = 1) }
                // PRs only: bring the branch here rather than go to it. Its own click target inside
                // the row, so it wins over the row's open-in-browser tap; hidden mid-switch the same
                // way the branch rows' Switch is.
                if (item.isPr && !state.switchingBranch) {
                    Spacer(Modifier.weight(1f))
                    HoverTip(
                        "Fetches this PR's branch and checks it out here (`gh pr checkout " +
                            "${item.number}`) — from a fork too, no remote setup needed.",
                    ) {
                        RowAction("Checkout") {
                            if (clean) state.checkoutPr(id, item.number)
                            else state.popup = Popup.Confirm(
                                "Check out PR #${item.number}?",
                                "You have uncommitted changes — git will carry them over, or refuse if they'd conflict.",
                                "Checkout anyway", danger = false,
                            ) { state.checkoutPr(id, item.number) }
                        }
                    }
                }
            }
        }
    }
}

/** Per-repo "stale after N days" control: presets set an override, "Default" clears it back to the
 *  global threshold. Click-only (no text entry). The effective value shows in the header. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun StaleThresholdRow(state: AppState, rv: RepoView) {
    val override = state.staleThresholdDays(rv.id)
    Column(Modifier.fillMaxWidth().padding(top = 20.dp).drawTopBorder(Tokens.sectionBorder).padding(top = 14.dp)) {
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

/**
 * Per-repo "hide these branches" patterns — the dotfile rule for branch lists. Each chip is one
 * regex; the lists keep matching branches out of the way and offer a Show/Hide switch beside the
 * count, so this is the *what*, and the section headers are the *whether*.
 *
 * Repos start on [Meta.DEFAULT_HIDE_BRANCH_PATTERNS] and say "· default" until something is
 * changed here, matching how the stale threshold above distinguishes an override from the default.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
/**
 * Labels this repo's issue list leaves out entirely.
 *
 * Chips rather than a comma-separated field, mirroring [HiddenBranchesRow] — but filtered rather
 * than hidden-behind-a-toggle, because the case this exists for is a category that is open by
 * design and never waiting on you. A "Show ignored" switch would just reintroduce the number the
 * filter was set up to remove.
 */
@Composable
private fun IgnoredLabelsRow(state: AppState, rv: RepoView) {
    var adding by remember(rv.id) { mutableStateOf(false) }
    val ignored = state.ignoreLabels(rv.id)
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(
            Modifier.padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Txt("Ignore labels", 12.5.sp, Tokens.text, FontWeight.Bold)
            InfoTip(
                "Issues and pull requests carrying any of these labels are left out of this " +
                    "repo's list and counts altogether — for the categories that stay open by " +
                    "design and aren't waiting on you, like requests where the next move is the " +
                    "submitter's.\n\n" +
                    "Matched against the label name, ignoring case. Suggestions come from the " +
                    "labels currently on this repo's open items.",
            )
        }
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ignored.forEach { name ->
                val shape = RoundedCornerShape(12.dp)
                Row(
                    Modifier.clip(shape).background(Tokens.surface, shape)
                        .border(1.dp, Tokens.borderE2, shape)
                        .padding(start = 9.dp, end = 5.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Txt(name, 11.5.sp, Tokens.secondary, FontWeight.SemiBold)
                    Txt("×", 13.sp, Tokens.secondary.copy(alpha = 0.7f), modifier = Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .onTap { state.setIgnoreLabels(rv.id, ignored - name) })
                }
            }
            if (!adding) {
                val shape = RoundedCornerShape(12.dp)
                Box(
                    Modifier.clip(shape).border(1.dp, Tokens.borderCb, shape)
                        .pointerHoverIcon(PointerIcon.Hand).onTap { adding = true }
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) { Txt("+ Label", 11.5.sp, Tokens.secondary, FontWeight.SemiBold) }
            } else {
                // Suggestions are the labels on items already fetched, minus the ones already
                // ignored — offering a label back that's currently filtering is just noise.
                val known = state.knownLabels(rv.repo) - ignored.toSet()
                TagAutocompleteField(
                    accent = state.accent,
                    suggest = { typed ->
                        known.filter { typed.isBlank() || it.contains(typed, ignoreCase = true) }
                    },
                    onCommit = { state.setIgnoreLabels(rv.id, ignored + it); adding = false },
                    onCancel = { adding = false },
                    resetKey = rv.id,
                    placeholder = "utility request",
                )
            }
        }
    }
}

@Composable
private fun HiddenBranchesRow(state: AppState, rv: RepoView) {
    var adding by remember(rv.id) { mutableStateOf(false) }
    val patterns = state.hideBranchPatterns(rv.id)
    val override = state.hideBranchPatternsOverride(rv.id)
    val invalid = remember(patterns) { Meta.invalidHidePatterns(patterns) }
    Column(Modifier.fillMaxWidth().padding(top = 20.dp).drawTopBorder(Tokens.sectionBorder).padding(top = 14.dp)) {
        Row(
            Modifier.padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Txt("Hidden branches", 12.5.sp, Tokens.text, FontWeight.Bold)
            if (override == null) Txt("· default", 11.sp, Tokens.muted2)
            InfoTip(
                "Branches matching these patterns are kept out of this repo's branch lists — bot " +
                    "branches, by default. They aren't gone: each list shows how many it's holding " +
                    "back and a Show switch to bring them in, the way a file manager treats dotfiles.\n\n" +
                    "Each pattern is a regular expression matched against the whole branch name as " +
                    "the list shows it — “origin/dependabot/.*” for remote branches, an unprefixed " +
                    "pattern like “wip/.*” for local ones.",
            )
        }
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            patterns.forEach { p ->
                val bad = p in invalid
                val shape = RoundedCornerShape(12.dp)
                val fg = if (bad) Tokens.redText else Tokens.secondary
                Row(
                    Modifier.clip(shape).background(if (bad) Tokens.tintRed else Tokens.surface, shape)
                        .border(1.dp, if (bad) Tokens.redText.copy(alpha = 0.35f) else Tokens.borderE2, shape)
                        .padding(start = 9.dp, end = 5.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Txt(p, 11.5.sp, fg, FontWeight.SemiBold, font = MonoFont)
                    // A pattern that doesn't compile hides nothing. Saying so on the chip is the
                    // only way to tell that apart from a pattern that simply matches no branch.
                    if (bad) HoverTip("Not a valid regular expression, so it hides nothing.") {
                        Txt("invalid", 10.5.sp, Tokens.redText, FontWeight.SemiBold)
                    }
                    Txt("×", 13.sp, fg.copy(alpha = 0.7f), modifier = Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .onTap { state.removeHideBranchPattern(rv.id, p) })
                }
            }
            if (!adding) {
                val shape = RoundedCornerShape(12.dp)
                Box(
                    Modifier.clip(shape).border(1.dp, Tokens.borderCb, shape)
                        .pointerHoverIcon(PointerIcon.Hand).onTap { adding = true }
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) { Txt("+ Pattern", 11.5.sp, Tokens.secondary, FontWeight.SemiBold) }
            } else {
                // No suggestions to offer — a regex isn't drawn from a known set — so the shared
                // field degrades to a plain type-and-Enter commit box, which is all this needs.
                TagAutocompleteField(
                    accent = state.accent,
                    suggest = { emptyList() },
                    onCommit = { state.addHideBranchPattern(rv.id, it); adding = false },
                    onCancel = { adding = false },
                    resetKey = rv.id,
                    placeholder = "origin/dependabot/.*",
                )
            }
        }
        if (override != null) {
            Txt(
                if (patterns.isEmpty()) "Nothing hidden in this repo · Reset to default"
                else "Reset to default",
                11.5.sp, state.accent, FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp).pointerHoverIcon(PointerIcon.Hand)
                    .onTap { state.resetHideBranchPatterns(rv.id) },
            )
        }
    }
}

/**
 * A plain-language outlook of the desktop notifications this repo will actually produce, each with
 * its timing: the manual reminder, the aging/stale threshold crossings, and the opt-in upstream
 * alert. Signals that never fire a notification (e.g. an already-stale badge) are shown as their own
 * visuals elsewhere — they don't appear here, so every line here is a real pending alert.
 *
 * A work zone, not a setting, and the distinction is the whole reason it moved out of the Settings
 * disclosure: every line is a *prediction about the next few hours* — "flags as aging in about 4
 * hours", "reminder overdue, re-notifies in 20 minutes". That is news, and news behind a collapsed
 * chevron is news nobody reads. What governs it — the thresholds, the upstream opt-in — stayed
 * behind the chevron, because those are answered once.
 */
@Composable
private fun NotificationsOutlook(state: AppState, rv: RepoView) {
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
        // Upstream advance — opt-in per repo (default off). Stated, not toggled: the switch lives in
        // Settings with the other choices, and this section's job is to say what will happen.
        if (repo.hasRemote && repo.upstream != null && state.notifyUpstreamEnabled(repo.id)) {
            val behind = if (repo.behind > 0) " · behind ${repo.behind} now" else ""
            add(Row_("🔔", "Alerts when ${repo.upstream} gets new commits$behind$paused", Tokens.remTeal))
        }
    }
    if (rows.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 20.dp).drawTopBorder(Tokens.sectionBorder).padding(top = 14.dp)) {
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
                            .border(1.dp, Tokens.accentBorder, RoundedCornerShape(6.dp))
                            .pointerHoverIcon(PointerIcon.Hand).onTap(act)
                            .padding(horizontal = 9.dp, vertical = 2.dp),
                    ) { Txt(label, 10.5.sp, Tokens.accent, FontWeight.SemiBold) }
                }
            }
        }
    }
}
