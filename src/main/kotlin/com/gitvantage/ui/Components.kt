// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.ui

import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.onClick
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gitvantage.app.Badge
import com.gitvantage.app.MonoFont
import com.gitvantage.app.Tokens
import com.gitvantage.app.UiFont
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/** A 1px bottom hairline, like CSS `border-bottom`. */
fun Modifier.drawBottomBorder(color: Color): Modifier = this.drawBehind {
    val h = 1.dp.toPx()
    drawRect(color = color, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h),
        size = androidx.compose.ui.geometry.Size(size.width, h))
}

/** A 1px top hairline, like CSS `border-top`. */
fun Modifier.drawTopBorder(color: Color): Modifier = this.drawBehind {
    val h = 1.dp.toPx()
    drawRect(color = color, size = androidx.compose.ui.geometry.Size(size.width, h))
}

/** A colored left bar of [width] px (row accent). */
fun Modifier.drawLeftBar(color: Color, width: Float): Modifier = this.drawBehind {
    val w = width.dp.toPx()
    drawRect(color = color, size = androidx.compose.ui.geometry.Size(w, size.height))
}

/** Flat click with no Material ripple, to match the Adwaita look. */
@Composable
fun Modifier.onTap(onClick: () -> Unit): Modifier =
    clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClick)

/**
 * Row click that reports the keyboard modifiers held at release — so a list row can do
 * plain-click (open), Ctrl/⌘-click (toggle bulk selection) and Shift-click (range select).
 * [clickable]/[onTap] don't expose modifiers, so we read them off the pointer event.
 * A child with its own click handler (e.g. the row checkbox) consumes first, so this
 * won't double-fire there; a scroll drag cancels the gesture, so list scrolling is intact.
 */
@Composable
fun Modifier.onModifierClick(key: Any, onClick: (ctrl: Boolean, shift: Boolean) -> Unit): Modifier {
    val handler = androidx.compose.runtime.rememberUpdatedState(onClick)
    return this.pointerInput(key) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val up = waitForUpOrCancellation()
            if (up != null && !up.isConsumed) {
                val m = currentEvent.keyboardModifiers
                handler.value(m.isCtrlPressed || m.isMetaPressed, m.isShiftPressed)
                up.consume()
            }
        }
    }
}

/**
 * Tag-chip click. Reports [exclude]=true for a **right-click** or a **Ctrl/⌘/Shift + left-click**
 * (make it a negative filter), false for a plain left-click (positive filter). A scroll drag
 * cancels the left-click gesture, so the horizontally-scrolling TAGS bar still scrolls.
 *
 * Right-click needs its own handler: `awaitFirstDown` below never fires for the secondary button,
 * so we detect it with foundation's `onClick(matcher = Secondary)` (the two never overlap).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Modifier.onTagClick(key: Any, onClick: (exclude: Boolean) -> Unit): Modifier {
    val handler = androidx.compose.runtime.rememberUpdatedState(onClick)
    return this
        .onClick(matcher = PointerMatcher.mouse(PointerButton.Secondary)) { handler.value(true) }
        .pointerInput(key) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                val up = waitForUpOrCancellation()
                if (up != null && !up.isConsumed) {
                    val m = currentEvent.keyboardModifiers
                    handler.value(m.isCtrlPressed || m.isMetaPressed || m.isShiftPressed)
                    up.consume()
                }
            }
        }
}

/**
 * The editing state of a text field: the text *and* where the caret and selection are.
 *
 * Every editable field in the app is driven by one of these rather than by `BasicTextField`'s
 * `String` overload. That overload keeps no selection of its own — it rebuilds one from the
 * incoming `String` on each pass and only carries the previous caret across if the composable
 * survives untouched. In a field whose every keystroke recomposes a large subtree — the toolbar
 * search re-filters, re-groups and re-lays out the whole repo list on each character — the caret
 * can come back at offset 0. Typing still appends (the platform inserts at the end of the buffer),
 * but Backspace has nothing before it to delete, which is exactly the "sometimes I can't erase
 * what's in the search box" symptom, and exactly why it is intermittent. Holding the selection
 * here, in state the recomposition cannot reach, removes the failure mode rather than making it
 * rarer.
 *
 * [value] is public and assignable: fields that drive the caret themselves (accepting an inline
 * completion, say) set it directly.
 */
@androidx.compose.runtime.Stable
class TextEditState(text: String) {
    var value by mutableStateOf(TextFieldValue(text, TextRange(text.length)))

    val text: String get() = value.text

    /** Replace the text, caret at the end — for programmatic changes, not for user edits. */
    fun setText(text: String) { value = TextFieldValue(text, TextRange(text.length)) }

    /** Select the whole field, as Ctrl-A does. */
    fun selectAll() { value = value.copy(selection = TextRange(0, value.text.length)) }
}

/**
 * A [TextEditState] mirroring a [text] whose source of truth lives elsewhere — app state, a
 * registry entry, a `remember` in the caller. Edits flow out through the field's `onValueChange`;
 * changes made to [text] by anything *else* (a "Clear" button, a popup reopening) flow back in
 * here, replacing the text and putting the caret at the end.
 *
 * [key] resets the state outright, for a field reused across subjects — pass the repo id and
 * selecting a different repo starts the field over rather than carrying the old caret into it.
 */
@Composable
fun rememberTextEdit(text: String, key: Any = Unit): TextEditState {
    val state = remember(key) { TextEditState(text) }
    // In composition rather than a LaunchedEffect: the field must never render a frame showing
    // text the source of truth no longer holds.
    if (text != state.value.text) state.setText(text)
    return state
}

/**
 * Double-click a text field to select everything in it, the way Ctrl-A does.
 *
 * Compose's own double-click selects a word, which is the platform convention but not what these
 * fields are for — a search box or a tag field holds one short value that you replace wholesale,
 * and stopping at a word boundary in `namespace:value` or a path fragment means typing over half
 * of it. So this widens the selection to the whole field.
 *
 * It fires on *release* rather than on the second press, for two reasons: by then the field's own
 * double-click handling has certainly run, so this is the last word on the selection without
 * depending on how the two pointer handlers happen to be ordered; and a double-click that turns
 * into a drag is the user selecting a range by hand, which [moved] leaves alone.
 *
 * Clicks are counted here because Compose reports no click count on desktop and the field's own
 * counter is internal.
 */
@Composable
fun Modifier.selectAllOnDoubleClick(onSelectAll: () -> Unit): Modifier {
    val handler = androidx.compose.runtime.rememberUpdatedState(onSelectAll)
    return this.pointerInput(Unit) {
        var lastDownAt = 0L
        var lastDownPos = Offset.Zero
        awaitEachGesture {
            // requireUnconsumed = false: the field consumes its own presses as it places the
            // caret, and this needs to see them anyway.
            val down = awaitFirstDown(requireUnconsumed = false)
            val slop = viewConfiguration.touchSlop
            val second = down.uptimeMillis - lastDownAt <= viewConfiguration.doubleTapTimeoutMillis &&
                (down.position - lastDownPos).getDistance() <= slop
            // A third click starts a fresh pair rather than firing again on every click after the
            // second, so click-click-click reads as one double-click plus one single.
            lastDownAt = if (second) 0L else down.uptimeMillis
            lastDownPos = down.position
            if (!second) return@awaitEachGesture
            var moved = false
            do {
                val event = awaitPointerEvent()
                if (event.changes.any { (it.position - down.position).getDistance() > slop }) moved = true
            } while (event.changes.any { it.pressed })
            if (!moved) handler.value()
        }
    }
}

/** Text wrapper with the tokens' defaults (family, ellipsis). */
@Composable
fun Txt(
    text: String,
    size: TextUnit,
    color: Color,
    weight: FontWeight = FontWeight.Normal,
    font: FontFamily = UiFont,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    italic: Boolean = false,
    letterSpacing: TextUnit = TextUnit.Unspecified,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = size,
        fontWeight = weight,
        fontFamily = font,
        fontStyle = if (italic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        letterSpacing = letterSpacing,
    )
}

/** A rounded pill: label with optional border/background. */
@Composable
fun Pill(
    label: String,
    color: Color,
    bg: Color,
    border: Color? = null,
    fontSize: TextUnit,
    weight: FontWeight,
    radius: Int,
    padding: PaddingValues,
    font: FontFamily = UiFont,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(radius.dp)
    var m = modifier.clip(shape).background(bg, shape)
    if (border != null) m = m.border(1.dp, border, shape)
    Box(m.padding(padding), contentAlignment = Alignment.Center) {
        Txt(label, fontSize, color, weight, font)
    }
}

/** A git-state badge (mono, tinted). */
@Composable
fun BadgeView(badge: Badge) {
    Pill(
        label = badge.txt,
        color = badge.color,
        bg = badge.bg,
        fontSize = 11.sp,
        weight = FontWeight.Medium,
        radius = 6,
        padding = PaddingValues(horizontal = 7.dp, vertical = 2.dp),
        font = MonoFont,
    )
}

/** A note-present indicator chip (shown alongside git-state badges). */
@Composable
fun NoteChip() {
    Pill(
        label = "✎ note",
        color = Tokens.noteChip.c,
        bg = Tokens.noteChip.t,
        border = Tokens.noteChip.b,
        fontSize = 11.sp,
        weight = FontWeight.Medium,
        radius = 6,
        padding = PaddingValues(horizontal = 7.dp, vertical = 2.dp),
        font = MonoFont,
    )
}

/** A row of badges that wraps; [hasNote] appends a "note" chip. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun Badges(badges: List<Badge>, modifier: Modifier = Modifier, hasNote: Boolean = false) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(5.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(5.dp),
    ) {
        badges.forEach { BadgeView(it) }
        if (hasNote) NoteChip()
    }
}

/** Wrap content in a hover tooltip that shows the full [path] (for truncated file names). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PathTip(path: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.foundation.TooltipArea(
        modifier = modifier,
        tooltip = {
            Box(
                Modifier.clip(RoundedCornerShape(6.dp)).background(Tokens.tooltipBg)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) { Txt(path, 11.5.sp, Tokens.tooltipText, font = MonoFont) }
        },
    ) { content() }
}

/**
 * What "Remove" means, for the two buttons that offer it.
 *
 * Shared rather than written twice because they make the same promise about the same irreversible-
 * sounding word, and two copies of a reassurance drift into two different reassurances. It names
 * history and uncommitted work specifically: "doesn't touch the repo" is accurate but abstract, and
 * the fear it has to answer is concrete.
 */
const val REMOVE_KEEPS_FILES: String =
    "Removes the repo from GitVantage only. Nothing on disk is touched — the folder, its history " +
        "and any uncommitted work stay exactly as they are, and you can add it back at any time."

/** Wrap arbitrary content in a hover tooltip explaining [text] (wraps to a readable width). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HoverTip(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.foundation.TooltipArea(
        modifier = modifier,
        tooltip = {
            Box(
                Modifier.widthIn(max = 340.dp).clip(RoundedCornerShape(8.dp)).background(Tokens.tooltipBg)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) { Txt(text, 11.5.sp, Tokens.tooltipText, maxLines = 10) }
        },
    ) { content() }
}

/** A small ⓘ info glyph with a hover tooltip that explains [text]. */
@Composable
fun InfoTip(text: String, modifier: Modifier = Modifier) {
    HoverTip(text, modifier) {
        Txt("ⓘ", 12.sp, Tokens.muted2, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand))
    }
}

/**
 * A pill that puts [value] on the clipboard and confirms it in place — the label swaps to
 * "✓ Copied" for a moment. The confirmation is local rather than a toast because the toast
 * reports git operations, and copying isn't one; it also keeps the answer next to the thing
 * that was copied, which matters when a list has many of these.
 *
 * Deliberately one look everywhere, in the blue of the action pills it sits among: copying is
 * the same act whether the thing is a branch, a remote ref or a file path, and a pill that
 * changed colour by neighbourhood read as a different control each time.
 */
@Composable
fun CopyPill(value: String, label: String = "Copy", modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(value) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1200.milliseconds); copied = false } }
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier.clip(shape)
            .background(if (copied) Tokens.tintGreen else Tokens.tintBlue, shape)
            .border(1.dp, if (copied) Tokens.okBorder else Tokens.accentBorder, shape)
            .pointerHoverIcon(PointerIcon.Hand)
            .onTap { clipboard.setText(AnnotatedString(value)); copied = true }
            .padding(horizontal = 9.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(if (copied) "✓ Copied" else "⧉ $label", 10.5.sp,
            if (copied) Tokens.addFg else Tokens.accent, FontWeight.SemiBold)
    }
}

/**
 * A list row in the detail panel whose actions are held back until the pointer is over it.
 *
 * The panel's lists — branches, remote branches, worktrees, submodules — carry the same three or
 * four actions on every single row. Drawn permanently they stack into a column of identical
 * buttons down the right margin that says nothing about any particular row, and the badges that
 * *do* differ get lost in it. Held behind hover, the eye reads names and states first, and the
 * actions arrive on the one row being asked about.
 *
 * The hover tint is what teaches this: rows visibly respond to the pointer, so the actions are
 * found by the same move that reaches for them. [content] gets the flag so each row decides which
 * of its parts are hover-only — line 1's badges are information and stay put.
 */
@Composable
fun HoverRow(
    vertical: Int = 5,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.(hovered: Boolean) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val shape = RoundedCornerShape(6.dp)
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (hovered) Tokens.rowHoverBg else Color.Transparent, shape)
            .hoverable(source)
            .padding(horizontal = 6.dp, vertical = vertical.dp),
    ) { content(hovered) }
}

/**
 * The lane a [HoverRow]'s actions live in. Always laid out, filled only when [visible] — the
 * height is reserved either way, because a row that grew under the pointer would shove the rows
 * below it out from under a cursor already on its way to one of them.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RowActions(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit,
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier.heightIn(min = 17.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(11.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp),
    ) { if (visible) content() }
}

/**
 * One action inside a [RowActions] lane: a plain text label in the accent, the same shape the
 * stash rows have always used. Bordered pills were doing the work of standing out from a row they
 * were permanently part of; once a row only shows its actions when asked, the border is just
 * weight. [danger] keeps anything destructive out of the blue reserved for navigation.
 */
@Composable
fun RowAction(label: String, danger: Boolean = false, onClick: () -> Unit) {
    Txt(
        label, 11.5.sp, if (danger) Tokens.redText else Tokens.accent, FontWeight.Medium,
        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).onTap(onClick),
    )
}

/**
 * [CopyPill]'s behaviour — clipboard, then "✓ Copied" in place for a moment — as a [RowAction],
 * for the lists where a bordered pill on every row was the problem. The pill form stays for the
 * places it appears alone, like the diff header.
 */
@Composable
fun CopyAction(value: String, label: String = "Copy") {
    val clipboard = LocalClipboardManager.current
    var copied by remember(value) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1200.milliseconds); copied = false } }
    Txt(
        if (copied) "✓ Copied" else label, 11.5.sp,
        if (copied) Tokens.addFg else Tokens.accent, FontWeight.Medium,
        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
            .onTap { clipboard.setText(AnnotatedString(value)); copied = true },
    )
}

/**
 * Click-to-copy on the identifier itself, for the places where the thing you would copy *is* the
 * thing already on screen — a commit hash, a branch chip. [content] draws it and is handed the
 * copied flag back, so each caller keeps its own colours.
 *
 * The confirmation is a colour change rather than [CopyPill]'s and [CopyAction]'s label swap
 * because these sit inline among other content: a label that grew to "✓ Copied" would push
 * everything to its right along, and in a log row that is far enough to reflow the subject onto a
 * second line and change the row's height under the pointer. Colour says the same thing at a fixed
 * width. It is the same green either way, which is what ties this to the pill form.
 *
 * Used for the identifiers a row carries several of, where a single [CopyAction] in the row's
 * hover lane would have no way to say which one it meant.
 */
@Composable
fun CopyTarget(
    value: String,
    tip: String,
    modifier: Modifier = Modifier,
    content: @Composable (copied: Boolean) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(value) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1200.milliseconds); copied = false } }
    HoverTip(
        tip,
        modifier.pointerHoverIcon(PointerIcon.Hand)
            .onTap { clipboard.setText(AnnotatedString(value)); copied = true },
    ) { content(copied) }
}

/**
 * A tinted state badge on a list row — "merged", "current", "agent", "in ⑂ feat/x".
 *
 * Rounder and quieter than [BadgeView]: those are git *counts* in mono, these are one-word verdicts
 * about a ref, and the two sit on the same rows.
 */
@Composable
fun BranchBadge(label: String, color: Color, bg: Color, modifier: Modifier = Modifier) {
    Pill(
        label, color, bg, fontSize = 10.5.sp, weight = FontWeight.SemiBold, radius = 10,
        padding = PaddingValues(horizontal = 7.dp, vertical = 2.dp), modifier = modifier,
    )
}

/**
 * The state a row is *expected* to be in — "up to date", "mainline", "linked", "tracked" — said in
 * plain muted text rather than a tinted pill.
 *
 * These labels are true of nearly every row in their list, so as pills they were a repeating
 * pattern rather than information, and the badges that do report something (merged, stale, behind,
 * missing, locked, agent) had to fight them for attention. Dropping them entirely would be worse:
 * "up to date" is an answer, and an empty space isn't. So they're kept, and de-emphasised.
 */
@Composable
fun QuietBadge(label: String) {
    Txt(label, 10.5.sp, Tokens.muted2, FontWeight.Medium)
}

/**
 * A click-to-apply choice in a set of them — the stale-threshold presets, the issue-severity pair,
 * a worktree's Inherit/On/Off.
 *
 * The idiom is that there is no Save: the pills *are* the setting, and clicking one applies it. That
 * is why they are pills and not radio buttons, and why the alerts popover can drop its footer
 * entirely — everything in it is already one of these.
 */
@Composable
fun PresetPill(
    label: String,
    on: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val alpha = if (dimmed) 0.45f else 1f
    Box(
        modifier.clip(shape)
            .background((if (on) Tokens.tintBlue else Tokens.surface).copy(alpha = alpha), shape)
            .border(1.dp, (if (on) Tokens.accentBorder else Tokens.borderE2).copy(alpha = alpha), shape)
            .pointerHoverIcon(PointerIcon.Hand).onTap(onClick)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Txt(
            label, 11.5.sp,
            (if (on) accent else Tokens.secondary).copy(alpha = alpha),
            if (on) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

/**
 * A read-only action in the detail pane: a flat accent link, no border.
 *
 * The other half of the pane's action split — a bordered button changes something, a flat accent
 * link only shows you something. Same rule the list rows' [RowAction] already followed, which is why
 * this is the same colour at a slightly larger size rather than a new look.
 */
@Composable
fun FlatAction(label: String, danger: Boolean = false, size: TextUnit = 12.5.sp, onClick: () -> Unit) {
    Txt(
        label, size, if (danger) Tokens.redText else Tokens.accent, FontWeight.SemiBold,
        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).onTap(onClick),
    )
}

/**
 * Esc closes this popover.
 *
 * A popover's own [androidx.compose.ui.window.PopupProperties] `focusable` flag lets the *window*
 * take focus, which is what makes a click outside dismiss it — but nothing inside it holds keyboard
 * focus, so the key event has nowhere to land and Esc does nothing. This takes focus on the way in
 * and handles the key itself, which is what the click-away half already promised: the two dismissals
 * are supposed to be interchangeable, and one of them silently not working is worse than neither.
 */
@Composable
fun Modifier.dismissOnEscape(onDismiss: () -> Unit): Modifier {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    return this.focusRequester(focus).focusable()
        .onPreviewKeyEvent { ev ->
            if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) { onDismiss(); true } else false
        }
}

/** A small filled circle of the given diameter. */
@Composable
fun StatusDot(color: Color, diameter: Int, modifier: Modifier = Modifier) {
    Box(modifier.size(diameter.dp).clip(RoundedCornerShape(50)).background(color))
}

/** An Adwaita-style checkbox glyph (filled accent when checked; greyed when disabled). */
@Composable
fun SelectBox(checked: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(5.dp)
    val bg = when {
        !enabled -> Tokens.segTrack
        checked -> Tokens.accentFill
        else -> Tokens.surface
    }
    val border = if (checked && enabled) Tokens.accent else Tokens.borderD8
    Box(
        modifier.size(18.dp).clip(shape).background(bg, shape).border(1.dp, border, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Txt("✓", 11.sp, if (enabled) Tokens.onAccent else Tokens.muted2, FontWeight.Bold)
    }
}
