// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gitvantage.app.AppState
import com.gitvantage.app.GitHub
import com.gitvantage.app.MonoFont
import com.gitvantage.app.NsPalette
import com.gitvantage.app.Popup
import com.gitvantage.app.Theme
import com.gitvantage.app.ThemeMode
import com.gitvantage.app.Tokens
import com.gitvantage.app.UiFont
import com.gitvantage.app.ViewMode
import com.gitvantage.app.deriveView
import com.gitvantage.app.nsPalette
import com.gitvantage.app.statusPalette

/** Generic segmented pill control (Group by, View toggle). */
@Composable
fun Segmented(
    options: List<Pair<String, String>>,   // key to label
    active: String,
    onSelect: (String) -> Unit,
    tips: Map<String, String> = emptyMap(),   // optional per-option hover explanation
    // Toolbar size by default. Smaller where the control sits inside a list item rather than above
    // the list — a worktree card's Changes|Log switch is part of that card, not a page control.
    fontSize: androidx.compose.ui.unit.TextUnit = 12.5.sp,
) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Tokens.segTrack).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { (key, label) ->
            val on = key == active
            val shape = RoundedCornerShape(6.dp)
            var m = Modifier.clip(shape)
            if (on) m = m.shadow(1.dp, shape).background(Tokens.surface, shape)
            val option: @Composable () -> Unit = {
                Box(m.onTap { onSelect(key) }.padding(horizontal = 11.dp, vertical = 4.dp)) {
                    Txt(
                        label, fontSize,
                        if (on) Tokens.text else Tokens.secondary,
                        if (on) FontWeight.Bold else FontWeight.SemiBold,
                    )
                }
            }
            val tip = tips[key]
            if (tip != null) HoverTip(tip) { option() } else option()
        }
    }
}

private const val GROUP_BY_TIP =
    "Groups the list by tag namespace. Options here fill in automatically from the namespaces you " +
        "use in tags: tag a repo “namespace:value” (e.g. “kind:work”) and a “Kind” option appears."

/** Toolbar: search · Group-by · fetched · Fetch all · Add repo. */
@Composable
fun Toolbar(state: AppState) {
    Row(
        Modifier.fillMaxWidth().background(Tokens.panelFb)
            .drawBottomBorder(Tokens.borderEd)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Search field (fixed 300dp so the single Spacer right-aligns the trailing group)
        Row(
            Modifier.width(300.dp)
                .clip(RoundedCornerShape(8.dp)).background(Tokens.surface)
                .border(1.dp, Tokens.borderD8, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Txt("⌕", 13.sp, Tokens.muted2)
            Box(Modifier.weight(1f)) {
                if (state.searchText.isEmpty()) {
                    Txt("Filter repos, branches, tags…", 13.sp, Tokens.muted2)
                }
                // The field of the app that recomposes hardest — every character re-filters,
                // re-groups and re-lays out the whole repo list — so the caret has to be held
                // outside that recomposition. See [TextEditState] for what goes wrong otherwise.
                val edit = rememberTextEdit(state.searchText)
                BasicTextField(
                    value = edit.value,
                    onValueChange = { edit.value = it; state.searchText = it.text },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = Tokens.text, fontFamily = UiFont),
                    cursorBrush = SolidColor(Tokens.accent),
                    modifier = Modifier.fillMaxWidth().selectAllOnDoubleClick { edit.selectAll() },
                )
            }
        }
        // "Group by" is empty apart from "None" until tags with namespaces exist, so the tooltip has
        // to explain how the options get there — otherwise a fresh install looks broken.
        HoverTip(GROUP_BY_TIP) { Txt("Group by", 12.sp, Tokens.muted) }
        val namespaces = state.namespaces()
        val gbOptions = listOf("none" to "None") +
            namespaces.map { it to it.replaceFirstChar { c -> c.uppercase() } }
        val gbTips = mapOf("none" to GROUP_BY_TIP) +
            namespaces.associateWith { ns -> "Group the list by each repo's “$ns:” tag value." }
        Segmented(gbOptions, state.groupBy, { state.groupBy = it }, tips = gbTips)

        HoverTip("The order repositories are listed in.") { Txt("Sort", 12.sp, Tokens.muted) }
        Segmented(
            listOf("name" to "Name", "commit" to "Last commit", "attention" to "Attention"),
            state.sortBy, { state.setSort(it) },
            tips = mapOf(
                "name" to "Alphabetical by repository name.",
                "commit" to "Most recently committed first; repos with no commits last.",
                // Tiers here mirror the status-dot colours in deriveView() — keep the two in step.
                "attention" to "Most in need of attention first, in status-dot order: " +
                    "red (something's wrong — detached HEAD, no upstream — or an issue awaiting you " +
                    "on a repo you marked issues-important), " +
                    "yellow (work sitting here — uncommitted changes or unpushed commits — stale " +
                    "on a repo you marked stale-important, or a GitHub issue/PR awaiting your reply), " +
                    "blue (nothing to do locally — behind the upstream, gone quiet/stale, or just " +
                    "open issues nobody's waiting on you for), " +
                    "green (clean and in sync).",
            ),
        )

        Spacer(Modifier.weight(1f))

        Txt(state.fetchedLabel(), 12.sp, Tokens.muted2)
        // Appearance. Glyph *and* label, like the Console and Fetch-all buttons it sits between:
        // these buttons are a fill a single step off the toolbar's own, so it's the label that
        // gives them a body to find. Glyph-only, this one read as an empty outline — see the note
        // on ThemeMode for the half of that which was the sun turning into a colour emoji.
        val themeTip = "Appearance: ${Theme.mode.label.lowercase()}" +
            // systemDark, not isDark — the parenthetical reports what the *desktop* asked for.
            (if (Theme.mode == ThemeMode.SYSTEM) " (${if (Theme.systemDark) "dark" else "light"})" else "") +
            ". Click to switch between light, dark, and matching your desktop."
        HoverTip(themeTip) {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).background(Tokens.surface)
                    .border(1.dp, Tokens.borderD8, RoundedCornerShape(8.dp))
                    .onTap { state.popup = Popup.Appearance }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Txt(Theme.mode.glyph, 13.sp, Tokens.text)
                Txt(Theme.mode.short, 12.5.sp, Tokens.text, FontWeight.Bold)
            }
        }
        // Git console toggle — opens the log of git commands the app has run
        Box(
            Modifier.clip(RoundedCornerShape(8.dp))
                .background(if (state.consoleOpen) Tokens.tintBlue else Tokens.surface)
                .border(1.dp, if (state.consoleOpen) Tokens.accentBorder else Tokens.borderD8, RoundedCornerShape(8.dp))
                .onTap { state.toggleConsole() }
                .padding(horizontal = 11.dp, vertical = 6.dp),
        ) {
            Txt(">_ Console", 12.5.sp, if (state.consoleOpen) state.accent else Tokens.text, FontWeight.Bold, font = MonoFont)
        }
        // Fetch all — re-scan every repo with `git fetch`, off the UI thread
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).background(Tokens.surface)
                .border(1.dp, Tokens.borderD8, RoundedCornerShape(8.dp))
                .onTap { state.refreshAll(fetch = true) }
                .padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Txt("↻", 13.sp, Tokens.text)
            Txt("Fetch all", 12.5.sp, Tokens.text, FontWeight.Bold)
        }
        // Add repo — native folder picker → register + scan
        Box(
            Modifier.clip(RoundedCornerShape(8.dp)).background(Tokens.accentFill)
                .onTap { state.pickAndAddRepo() }
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Txt("+ Add repo", 12.5.sp, Tokens.onAccent, FontWeight.Bold)
        }
    }
}

/** Contextual bar shown when repos are checkbox-selected: bulk actions over the selection. */
@Composable
fun BulkActionBar(state: AppState) {
    val ids = state.bulkSelected
    val n = ids.size
    val noun = if (n == 1) "repo" else "repos"
    Row(
        Modifier.fillMaxWidth().background(Tokens.tintBlue)
            .drawBottomBorder(Tokens.borderEd)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Discoverable selection controls first (far left), then the action buttons.
        Txt("Select all shown", 12.sp, state.accent, FontWeight.SemiBold,
            modifier = Modifier.onTap { state.bulkSelectAllVisible() })
        Txt("Clear", 12.sp, Tokens.muted, FontWeight.SemiBold, modifier = Modifier.onTap { state.clearBulk() })
        Box(Modifier.width(1.dp).height(18.dp).background(Tokens.borderD8))
        Txt("$n selected", 12.5.sp, state.accent, FontWeight.Bold)
        Spacer(Modifier.width(4.dp))
        BulkBtn("Tag") { state.popup = Popup.Tag(ids) }
        BulkBtn("Untag") { state.popup = Popup.Untag(ids) }
        BulkBtn("Push") {
            state.popup = Popup.Confirm(
                "Push $n $noun?", "Runs git push in each selected repo that has an upstream.",
                "Push", danger = false,
            ) { state.push(ids) }
        }
        BulkBtn("Snooze") { state.popup = Popup.Snooze(ids) }
        BulkBtn("Remind") { state.popup = Popup.Remind(ids, "", null) }
        BulkBtn("Remove", danger = true) {
            state.popup = Popup.Confirm(
                "Remove $n $noun?", "Stops tracking them. Does not touch the repos on disk.",
                "Remove", danger = true,
            ) { state.removeRepos(ids) }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun BulkBtn(label: String, danger: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(7.dp)
    Box(
        Modifier.clip(shape).background(Tokens.surface, shape)
            .border(1.dp, if (danger) Tokens.warnBorder else Tokens.borderD8, shape)
            .onTap(onClick).padding(horizontal = 11.dp, vertical = 5.dp),
    ) {
        Txt(label, 12.sp, if (danger) Tokens.redText else Tokens.text, FontWeight.SemiBold)
    }
}

/** Status filter chips + "N of M shown" + view toggle. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun StatusBar(state: AppState) {
    val counts = state.counts()
    val shownCount = state.filtered().size
    val total = counts["all"] ?: 0
    // key, label, and the hover explanation of what the filter selects.
    val chips = listOf(
        Triple("all", "All", "Every tracked repository."),
        Triple("dirty", "Dirty", "Repos with uncommitted changes — staged, modified, or untracked files."),
        Triple("aging", "Aging", "Repos whose uncommitted changes have been sitting for over 24 hours."),
        Triple("unpushed", "Unpushed", "Repos with local commits that haven't been pushed to their upstream yet."),
        Triple("behind", "Behind", "Repos whose branch is behind its upstream — there are commits to pull in."),
        Triple("stale", "Stale", "Repos with no new commit for longer than the stale threshold (30 days by default, overridable per repo). A heads-up, not a problem — stable code often sits untouched."),
        Triple("problems", "Problems", "Repos in a state that needs fixing: detached HEAD, no upstream, or not a git repo."),
        Triple("ghopen", "Open Issues", "Repos with open issues or pull requests on GitHub. Informational — every healthy project has some."),
        Triple("ghawaiting", "Awaiting You", "Repos with an open issue or PR waiting on your response: your review is requested, you're assigned, the last comment mentions you, or someone replied on a thread you opened."),
        Triple("stashes", "Stashes", "Repos holding at least one stash."),
        Triple("worktrees", "Worktrees", "Repos with a branch checked out in another folder. Counts the repos, not the worktrees — and expands each one's worktree rows while the filter is on."),
        Triple("reminders", "Reminders", "Repos with a reminder set."),
        Triple("notes", "Notes", "Repos with a non-empty working note."),
        Triple("snoozed", "Snoozed", "Repos whose alerts are currently silenced."),
        Triple("recent", "Recently Added", "Repos first tracked in the last 24 hours."),
        Triple("recentmod", "Recently Modified", "Repos whose working tree was edited in the last 12 hours."),
    )
    Row(
        Modifier.fillMaxWidth().background(Tokens.surface)
            .drawBottomBorder(Tokens.borderEd)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Chips wrap to a second line rather than pushing the shown-count and view toggle off the
        // right edge — the filter set has outgrown a single row on narrower windows.
        androidx.compose.foundation.layout.FlowRow(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            chips.forEach { (key, label, tip) ->
                HoverTip(tip) {
                    StatusChip(label, counts[key] ?: 0, key == state.status, statusPalette(key)) {
                        state.status = key
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Txt("$shownCount of $total shown", 12.sp, Tokens.muted2)
        Segmented(
            listOf("table" to "▤ Table", "cards" to "▦ Cards"),
            if (state.view == ViewMode.TABLE) "table" else "cards",
            { state.view = if (it == "table") ViewMode.TABLE else ViewMode.CARDS },
        )
    }
}

@Composable
private fun StatusChip(label: String, count: Int, on: Boolean, p: NsPalette, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.clip(shape)
            .background(if (on) p.t else Tokens.surface, shape)
            .border(1.dp, if (on) p.b else Tokens.borderE2, shape)
            .onTap(onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Txt(label, 12.5.sp, if (on) p.c else Tokens.secondary, if (on) FontWeight.Bold else FontWeight.SemiBold)
        Box(
            Modifier.height(18.dp).widthIn(min = 18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (on) p.c else Tokens.segTrack)
                .padding(horizontal = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Txt("$count", 11.sp, if (on) Tokens.onBright else Tokens.muted, FontWeight.Bold)
        }
    }
}

/** Tag filter bar: TAGS · Clear Tag Filters · per-namespace value chips. */
@Composable
fun TagBar(state: AppState) {
    Row(
        Modifier.fillMaxWidth().background(Tokens.panelFa)
            .drawBottomBorder(Tokens.borderEd)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Txt("TAGS", 11.sp, Tokens.muted2, FontWeight.Bold, letterSpacing = 0.5.sp)
        // Always laid out — only the colour changes — so selecting a tag can't shift the chips
        // sideways under a cursor that's mid-way through picking several of them.
        val canClear = state.tagFilter.isNotEmpty() || state.tagExclude.isNotEmpty()
        Txt(
            "Clear Tag Filters", 12.sp, if (canClear) state.accent else Tokens.muted2, FontWeight.SemiBold,
            modifier = if (canClear) Modifier.onTap { state.clearTags() } else Modifier,
        )
        state.namespaceGroups().forEach { (ns, chips) ->
            val p = nsPalette(ns)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(ns.replaceFirstChar { it.uppercase() }, 11.sp, p.c, FontWeight.Bold)
                chips.forEach { chip ->
                    val on = chip.key in state.tagFilter
                    val excluded = chip.key in state.tagExclude
                    val shape = RoundedCornerShape(20.dp)
                    val bg = when { on -> p.t; excluded -> Tokens.tintRed; else -> Tokens.surface }
                    val bd = when { on -> p.b; excluded -> Tokens.warnBorder; else -> Tokens.borderE2 }
                    val fg = when { on -> p.c; excluded -> Tokens.redText; else -> Tokens.muted }
                    // Tooltip reflects the chip's current state: what a plain click will select, or
                    // — once negated — that it's excluding and how to undo that.
                    val tip = if (excluded) {
                        "Hiding repos with tag “${chip.key}”. Shift-click to stop excluding it."
                    } else {
                        "Repos with tag “${chip.key}”, shift-click to negate."
                    }
                    HoverTip(tip) {
                        Box(
                            Modifier.clip(shape).background(bg, shape).border(1.dp, bd, shape)
                                // Left click = include; right-click or Ctrl/⌘/Shift+click = exclude.
                                .onTagClick(chip.key) { exclude ->
                                    if (exclude) state.toggleTagExclude(chip.key) else state.toggleTagFilter(chip.key)
                                }
                                .padding(horizontal = 9.dp, vertical = 3.dp),
                        ) {
                            Txt(if (excluded) "≠ ${chip.label}" else chip.label, 11.5.sp, fg, if (on || excluded) FontWeight.Bold else FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}
