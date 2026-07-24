// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Cursor

/**
 * A docked console panel along the bottom of the app that lists the side-effecting git
 * commands the app has run (see [GitLog]), newest at the bottom. It stays open while you
 * work; drag its top edge to resize. Themed to match the app (light), with git's colors
 * (or a semantic fallback) applied to the output.
 */

// Light-theme console colors, drawn from the app tokens.
private val ConErr = Tokens.redText
private val ConOk = Tokens.cleanText
private val ConPrompt = Tokens.accent
private val ConText = Tokens.text2
private val ConMuted = Tokens.muted2

// ANSI foreground palette tuned for the light console background.
private val ANSI_FG = listOf(
    hex("#3d3d3d"), hex("#c0181f"), hex("#1a8049"), hex("#8a6d00"),
    hex("#1a5fb4"), hex("#9c27b0"), hex("#0e7490"), hex("#5e5c64"),
)

@Composable
fun GitConsolePanel(state: AppState) {
    val listState = rememberLazyListState()
    LaunchedEffect(GitLog.entries.size) {
        if (GitLog.entries.isNotEmpty()) runCatching { listState.scrollToItem(GitLog.entries.size - 1) }
    }
    Column(Modifier.fillMaxWidth().height(state.consoleHeight.dp).background(Color.White).drawTopBorder(Tokens.borderEd)) {
        // Drag handle (top edge) — drag up to grow, down to shrink.
        Box(
            Modifier.fillMaxWidth().height(11.dp).background(Tokens.panelFb)
                .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount -> state.resizeConsole(-dragAmount) }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(34.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Tokens.borderD8))
        }
        // Header
        Row(
            Modifier.fillMaxWidth().background(Tokens.panelFb).drawBottomBorder(Tokens.borderEd)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Txt(">_ Git console", 12.5.sp, Tokens.text, FontWeight.Bold, font = MonoFont)
            Txt("${GitLog.entries.size} command${if (GitLog.entries.size == 1) "" else "s"}", 11.sp, ConMuted, font = MonoFont)
            Spacer(Modifier.weight(1f))
            Txt("Clear", 12.sp, Tokens.accent, FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).onTap { state.clearConsole() }
                    .padding(horizontal = 8.dp, vertical = 3.dp))
            Txt("✕", 13.sp, Tokens.muted, FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).onTap { state.closeConsole() }
                    .padding(horizontal = 8.dp, vertical = 3.dp))
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (GitLog.entries.isEmpty()) {
                Txt("No git commands run yet. Push / fetch / switch / commit … will appear here.",
                    12.sp, ConMuted, font = MonoFont, modifier = Modifier.padding(14.dp))
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp)) {
                    items(GitLog.entries, key = { it.seq }) { e -> ConsoleEntry(e) }
                }
                VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun ConsoleEntry(e: GitLog.Entry) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt("❯", 12.5.sp, ConPrompt, FontWeight.Bold, font = MonoFont)
            Txt(e.command, 12.5.sp, Tokens.text, FontWeight.SemiBold, font = MonoFont, maxLines = 1, modifier = Modifier.weight(1f))
            Txt(e.repo, 11.sp, ConMuted, font = MonoFont)
            Txt(if (e.exitCode == 0) "✓ 0" else "✗ ${e.exitCode}", 11.sp,
                if (e.exitCode == 0) ConOk else ConErr, FontWeight.Bold, font = MonoFont)
            Txt("${e.durationMs}ms", 11.sp, ConMuted, font = MonoFont)
        }
        if (e.output.isNotBlank()) {
            Box(Modifier.fillMaxWidth().padding(start = 16.dp, top = 3.dp).horizontalScroll(rememberScrollState())) {
                androidx.compose.material.Text(
                    colorizeOutput(e.output),
                    fontSize = 11.5.sp,
                    fontFamily = MonoFont,
                    color = ConText,
                    softWrap = false,
                )
            }
        }
    }
}

private const val ESC = "\\u001B"

/** Colorize git output line-by-line: honor real ANSI colors when git emitted them
 *  (log/diff/status), else apply a semantic tint so push/fetch/switch output still
 *  reads well — errors red, ref updates green, remotes blue. */
fun colorizeOutput(text: String): AnnotatedString = buildAnnotatedString {
    val lines = text.split('\n')
    lines.forEachIndexed { i, line ->
        if (line.contains(ESC)) append(ansiToAnnotated(line))
        else withStyle(SpanStyle(color = semanticColor(line))) { append(line) }
        if (i < lines.lastIndex) append('\n')
    }
}

private val REF_UPDATE = Regex("[0-9a-f]{7,}\\.\\.[0-9a-f]{7,}")

private fun semanticColor(line: String): Color {
    val t = line.trimStart()
    val low = t.lowercase()
    return when {
        t.startsWith("error:") || t.startsWith("fatal:") || t.startsWith("!") ||
            "[rejected]" in t || "non-fast-forward" in low -> ConErr
        t.startsWith("warning:") || t.startsWith("hint:") -> ANSI_FG[3]                   // yellow
        t.startsWith("Switched to") || t.startsWith("Already") ||
            REF_UPDATE.containsMatchIn(t) || "[new branch]" in t || "[new tag]" in t -> ConOk
        t.startsWith("To ") || t.startsWith("From ") -> ConPrompt                          // blue
        else -> ConText
    }
}

private val ANSI_RE = Regex("\\u001B\\[([0-9;]*)m")

/** Parse a string containing ANSI SGR escapes into a colored [AnnotatedString].
 *  Handles reset(0), bold(1/22), default-fg(39), and the 30–37 / 90–97 color ranges;
 *  background and unsupported codes are ignored. */
fun ansiToAnnotated(text: String): AnnotatedString = buildAnnotatedString {
    var fg: Color? = null
    var bold = false
    var idx = 0

    fun emit(chunk: String) {
        if (chunk.isEmpty()) return
        withStyle(SpanStyle(color = fg ?: ConText, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)) {
            append(chunk)
        }
    }

    for (m in ANSI_RE.findAll(text)) {
        if (m.range.first > idx) emit(text.substring(idx, m.range.first))
        val codes = m.groupValues[1].split(';').filter { it.isNotEmpty() }.ifEmpty { listOf("0") }
        for (code in codes) {
            when (val n = code.toIntOrNull()) {
                0 -> { fg = null; bold = false }
                1 -> bold = true
                22 -> bold = false
                39 -> fg = null
                null -> {}
                in 30..37 -> fg = ANSI_FG[n - 30]
                in 90..97 -> fg = ANSI_FG[n - 90]
                else -> {}
            }
        }
        idx = m.range.last + 1
    }
    if (idx < text.length) emit(text.substring(idx))
}
