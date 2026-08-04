// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Side-by-side diff overlay with Staged/Modified/Untracked sections, a flattened-tree file
 * list (click to jump), and character-level highlighting inside changed lines. Closes on
 * Escape (works even after clicking the diff — the root re-grabs focus on any press).
 */
@Composable
fun DiffView(state: AppState) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.fillMaxSize().background(Color(0x55000000)).onTap { }
            .focusRequester(focus).focusable()
            // Re-grab focus after every click (Final pass = after children) so Escape keeps
            // working even if a click inside the diff moved focus to the scrollable content.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Final)
                        if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Release) {
                            runCatching { focus.requestFocus() }
                        }
                    }
                }
            }
            .onPreviewKeyEvent {
                if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { state.closeDiff(); true } else false
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxSize().padding(24.dp)
                .clip(shape).background(Color.White, shape).border(1.dp, Tokens.borderDc, shape),
        ) {
            Row(
                Modifier.fillMaxWidth().background(Tokens.panelFb).drawBottomBorder(Tokens.borderEd)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Txt(state.diffTitle, 14.sp, Tokens.text, FontWeight.Bold)
                Txt("— ${state.diffRepoName}", 13.sp, Tokens.muted2)
                Spacer(Modifier.weight(1f))
                if (!state.diffLoading && state.diff.files.isNotEmpty()) {
                    Txt("${state.diff.files.size} ${if (state.diff.files.size == 1) "file" else "files"} changed", 12.sp, Tokens.muted2)
                }
                Txt("Esc", 11.sp, Tokens.muted2)
                Box(
                    Modifier.size(26.dp).clip(RoundedCornerShape(50)).background(Tokens.segTrack).onTap { state.closeDiff() },
                    contentAlignment = Alignment.Center,
                ) { Txt("×", 14.sp, Tokens.secondary) }
            }
            when {
                state.diffLoading -> CenteredMessage("Loading diff…")
                state.diff.items.isEmpty() -> CenteredMessage("No changes to show.")
                else -> Row(Modifier.fillMaxWidth().weight(1f)) {
                    Box(Modifier.width(300.dp).fillMaxHeight().background(Tokens.panelFa)) {
                        FileTreePane(state.diff.files) { idx -> scope.launch { listState.scrollToItem(idx) } }
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Tokens.borderEd))
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        LazyColumn(Modifier.fillMaxSize(), state = listState) {
                            items(state.diff.items.size) { i -> DiffItemRow(state.diff.items[i]) }
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
}

@Composable
private fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Txt(text, 13.sp, Tokens.muted2) }
}

/** Left pane: sections, each with a flattened tree of its files; leaves jump to the diff. */
@Composable
private fun FileTreePane(files: List<DiffOps.FileRef>, onJump: (Int) -> Unit) {
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            files.groupBy { it.section }.forEach { (section, refs) ->
                item(key = "sec-$section") { SectionHeader(section) }
                val byPath = refs.associateBy { it.path }
                // Key by position within the section: directory nodes only carry their collapsed
                // label (not their full path), so two dirs that collapse to the same segment under
                // different parents would otherwise collide and crash the LazyColumn.
                PathTree.flatten(refs.map { it.path }).forEachIndexed { idx, node ->
                    item(key = "$section#$idx") {
                        if (node.fullPath == null) TreeDir(node) else byPath[node.fullPath]?.let { TreeFile(node, it, onJump) }
                    }
                }
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 2.dp),
        )
    }
}

@Composable
private fun SectionHeader(label: String) {
    Box(Modifier.fillMaxWidth().background(Tokens.groupHeaderBg).drawBottomBorder(Tokens.groupHeaderBorder)
        .padding(horizontal = 12.dp, vertical = 6.dp)) {
        Txt(label.uppercase(), 10.5.sp, Tokens.muted, FontWeight.Bold, letterSpacing = 0.5.sp)
    }
}

@Composable
private fun TreeDir(node: PathTree.Node) {
    Row(Modifier.fillMaxWidth().padding(start = (12 + node.depth * 14).dp, end = 12.dp, top = 4.dp, bottom = 4.dp)) {
        PathTip(node.label, Modifier.weight(1f, fill = false)) {
            Txt("▸ ${node.label}", 11.5.sp, Tokens.muted2, font = MonoFont, maxLines = 1)
        }
    }
}

@Composable
private fun TreeFile(node: PathTree.Node, ref: DiffOps.FileRef, onJump: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().onTap { onJump(ref.index) }
            .padding(start = (12 + node.depth * 14).dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Tooltip shows just the filename — the folder path is already visible in the tree.
        PathTip(node.label, Modifier.weight(1f)) { Txt(node.label, 12.sp, Tokens.text2, font = MonoFont, maxLines = 1) }
        if (ref.added > 0) Txt("+${ref.added}", 10.5.sp, hex("#1a7f37"), FontWeight.SemiBold, font = MonoFont)
        if (ref.removed > 0) Txt("-${ref.removed}", 10.5.sp, hex("#b0181f"), FontWeight.SemiBold, font = MonoFont)
    }
}

@Composable
private fun DiffItemRow(item: DiffOps.Item) {
    when (item) {
        is DiffOps.Section -> Box(Modifier.fillMaxWidth().background(Tokens.tintBlue).padding(horizontal = 14.dp, vertical = 5.dp)) {
            Txt(item.label.uppercase(), 11.sp, Tokens.accent, FontWeight.Bold, letterSpacing = 0.5.sp)
        }
        is DiffOps.FileHead -> Row(
            Modifier.fillMaxWidth().background(Tokens.groupHeaderBg).drawBottomBorder(Tokens.groupHeaderBorder)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Txt(item.path, 12.5.sp, Tokens.text, FontWeight.Bold, font = MonoFont, modifier = Modifier.weight(1f))
            CopyPill(item.path, "Copy path")
            if (item.added > 0) Txt("+${item.added}", 11.sp, hex("#1a7f37"), FontWeight.SemiBold, font = MonoFont)
            if (item.removed > 0) Txt("-${item.removed}", 11.sp, hex("#b0181f"), FontWeight.SemiBold, font = MonoFont)
        }
        is DiffOps.Hunk -> Box(Modifier.fillMaxWidth().background(hex("#eaf2fc")).padding(horizontal = 14.dp, vertical = 2.dp)) {
            Txt(item.text, 11.5.sp, Tokens.accent, font = MonoFont, maxLines = 2)
        }
        is DiffOps.Row -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            SideCell(item.left, Modifier.weight(1f))
            Box(Modifier.width(1.dp).fillMaxHeight().background(Tokens.borderEd))
            SideCell(item.right, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SideCell(cell: DiffOps.Cell?, modifier: Modifier) {
    val (bg, color, hl) = when (cell?.side) {
        DiffOps.Side.ADD -> Triple(hex("#e6f9ee"), hex("#12622f"), hex("#a6ecbf"))   // saturated green highlight
        DiffOps.Side.DEL -> Triple(hex("#fce9ea"), hex("#9a1720"), hex("#f4aab0"))   // saturated red highlight
        DiffOps.Side.CONTEXT -> Triple(Color.White, Tokens.text2, Color.Transparent)
        null -> Triple(hex("#f6f6f4"), Tokens.muted2, Color.Transparent)
    }
    val text: AnnotatedString = if (cell != null && cell.hlStart >= 0 && cell.hlEnd > cell.hlStart && cell.hlEnd <= cell.text.length) {
        buildAnnotatedString {
            append(cell.text.substring(0, cell.hlStart))
            withStyle(SpanStyle(background = hl)) { append(cell.text.substring(cell.hlStart, cell.hlEnd)) }
            append(cell.text.substring(cell.hlEnd))
        }
    } else AnnotatedString(cell?.text ?: "")
    Row(modifier.fillMaxHeight().background(bg), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(40.dp).fillMaxHeight().padding(end = 6.dp, top = 1.dp), contentAlignment = Alignment.TopEnd) {
            Txt(cell?.no?.toString() ?: "", 10.5.sp, Tokens.muted2, font = MonoFont)
        }
        Text(
            text = text, color = color, fontSize = 12.sp, fontFamily = MonoFont, maxLines = 40,
            modifier = Modifier.weight(1f).padding(end = 8.dp, top = 1.dp, bottom = 1.dp),
        )
    }
}
