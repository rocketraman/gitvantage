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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
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
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import kotlin.math.cos
import kotlin.math.sin
import java.awt.datatransfer.StringSelection

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

/**
 * An absolute path with `$HOME` folded back to `~`, which is how the user thinks of it.
 *
 * A display form only — never what gets copied. `~` is a shell's expansion, not a path, so the
 * thing on the clipboard stays absolute even where the thing on screen is folded.
 */
fun shortPath(path: String): String {
    val home = System.getProperty("user.home").orEmpty()
    return if (home.isNotEmpty() && path.startsWith(home)) "~" + path.removePrefix(home) else path
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
 * Put [text] on the system clipboard as plain text.
 *
 * [Clipboard] only takes a [ClipEntry], and on desktop the one way to build that is around an AWT
 * `Transferable` — a constructor Compose still marks experimental, hence the opt-in.
 */
@OptIn(ExperimentalComposeUiApi::class)
suspend fun Clipboard.setText(text: String) = setClipEntry(ClipEntry(StringSelection(text)))

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
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(value) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1200.milliseconds); copied = false } }
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier.clip(shape)
            .background(if (copied) Tokens.tintGreen else Tokens.tintBlue, shape)
            .border(1.dp, if (copied) Tokens.okBorder else Tokens.accentBorder, shape)
            .pointerHoverIcon(PointerIcon.Hand)
            .onTap { scope.launch { clipboard.setText(value); copied = true } }
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
    // The reserved height is a [RowActionKind.GIT] chip's, not a bare link's — otherwise a lane with
    // a chip in it would grow by its border the moment it filled.
    androidx.compose.foundation.layout.FlowRow(
        modifier.heightIn(min = 19.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(11.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) { if (visible) content() }
}

/**
 * What an action does, which decides how it looks — everywhere: the detail pane's toolbar, the hover
 * lanes under it, the log's commit rows, the bulk bar.
 *
 * The colour goes where the consequence is. Blue — the colour the eye finds first — marks what
 * changes git state; showing you something, which costs nothing to click, is neutral grey. A lane of
 * identical blue words threw that away and left "Folder", "Alerts ▾" and "Remove" reading as one
 * list; the earlier toolbar had it backwards, a blue Log beside a grey Push.
 */
enum class RowActionKind {
    /** Shows something or opens something elsewhere; changes nothing. Grey outline. */
    VIEW,

    /** Changes git state — a branch, a checkout, a remote. Blue outline, like Push. */
    GIT,

    /** GitVantage's own settings for this row, not git's. Amber, like Snooze. */
    APP,

    /** Destroys something. Red outline, and last in its lane — at its far end where the lane has room. */
    DANGER,
}

/** Where a [SplitMenuButton] sits, which sets its size to match its neighbours. */
enum class SplitSize(val height: Dp, val radius: Dp, val padH: Dp, val font: TextUnit, val weight: FontWeight) {
    /** The detail pane's action row, beside Commit and Push. */
    TOOLBAR(33.dp, 8.dp, 12.dp, 12.5.sp, FontWeight.Bold),

    /** The bulk-selection bar. */
    BAR(27.dp, 7.dp, 11.dp, 12.sp, FontWeight.SemiBold),

    /** A [RowActions] hover lane. */
    LANE(19.dp, 5.dp, 7.dp, 11.5.sp, FontWeight.Medium),
}

/** A kind's text, fill and border. */
data class ActionColors(val text: Color, val fill: Color, val border: Color)

val RowActionKind.colors: ActionColors
    get() = when (this) {
        RowActionKind.VIEW -> ActionColors(Tokens.text2, Tokens.surface, Tokens.borderD8)
        RowActionKind.GIT -> ActionColors(Tokens.accent, Tokens.tintBlue, Tokens.accentBorder)
        RowActionKind.APP -> ActionColors(Tokens.snoozeBtnText, Tokens.snoozeBtnBg, Tokens.snoozeBtnBorder)
        RowActionKind.DANGER -> ActionColors(Tokens.redText, Tokens.tintRed, Tokens.warnBorder)
    }

/**
 * One action inside a [RowActions] lane: a small outlined chip in its [kind]'s colours — the
 * toolbar's buttons at a size a hover lane has room for. Callers order a lane by kind, in the enum's
 * order, and put the [RowActionKind.DANGER] ones in a [DangerActions] group.
 */
@Composable
fun RowAction(label: String, kind: RowActionKind = RowActionKind.VIEW, onClick: () -> Unit) {
    val click = Modifier.pointerHoverIcon(PointerIcon.Hand).onTap(onClick)
    val c = kind.colors
    val shape = RoundedCornerShape(5.dp)
    Box(
        click.clip(shape).background(c.fill, shape).border(1.dp, c.border, shape)
            .padding(horizontal = 7.dp, vertical = 1.dp),
    ) { Txt(label, 11.5.sp, c.text, FontWeight.Medium) }
}

/**
 * A lane's destructive actions, kept together and pushed to the lane's far end — out of the path a
 * pointer takes along the everyday ones, the way a dialog keeps Delete away from OK. Only in a lane
 * laid out full width: in one sized to its content there is no far end to push to, and the lane
 * would grow to claim it. When the group doesn't fit, it wraps to a line of its own as one unit.
 */
@Composable
fun androidx.compose.foundation.layout.FlowRowScope.DangerActions(content: @Composable () -> Unit) {
    // The group itself takes the weight, rather than a spacer before it: a spacer stays behind on
    // the line the group wrapped away from, and the group lands at the start of the next.
    androidx.compose.foundation.layout.Row(
        Modifier.weight(1f),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(11.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/**
 * [CopyPill]'s behaviour — clipboard, then "✓ Copied" in place for a moment — as a
 * [RowActionKind.VIEW] [RowAction], for the hover lanes. The pill form stays for the places it
 * appears alone, like the diff header.
 */
@Composable
fun CopyAction(value: String, label: String = "Copy") {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(value) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1200.milliseconds); copied = false } }
    val c = RowActionKind.VIEW.colors
    val shape = RoundedCornerShape(5.dp)
    Box(
        Modifier.pointerHoverIcon(PointerIcon.Hand)
            .onTap { scope.launch { clipboard.setText(value); copied = true } }
            .clip(shape).background(c.fill, shape).border(1.dp, c.border, shape)
            .padding(horizontal = 7.dp, vertical = 1.dp),
    ) { Txt(if (copied) "✓ Copied" else label, 11.5.sp, if (copied) Tokens.addFg else c.text, FontWeight.Medium) }
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
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(value) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1200.milliseconds); copied = false } }
    HoverTip(
        tip,
        modifier.pointerHoverIcon(PointerIcon.Hand)
            .onTap { scope.launch { clipboard.setText(value); copied = true } },
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

/**
 * A gear, drawn rather than typed.
 *
 * The obvious glyph, U+2699, carries the Unicode Emoji property, so a colour-emoji font claims it
 * and draws a blue-grey cog that ignores the colour asked for — the trap the note on `ThemeMode`
 * describes for the sun. The sun had a text-only twin to switch to; the gear has none, and asking
 * for text presentation with U+FE0E was tried and made no difference. Drawn, it is the same shape
 * in the requested colour on every platform and in both themes.
 */
@Composable
fun GearIcon(color: Color, size: Dp = 13.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val tooth = r * 0.34f
        // Eight teeth as short radial strokes, then the body as a ring drawn over their roots.
        for (i in 0 until 8) {
            val angle = Math.toRadians(i * 45.0)
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            drawLine(
                color,
                start = Offset(center.x + dx * r * 0.55f, center.y + dy * r * 0.55f),
                end = Offset(center.x + dx * r, center.y + dy * r),
                strokeWidth = tooth,
            )
        }
        drawCircle(color, radius = r * 0.52f, style = Stroke(width = r * 0.34f))
    }
}

/**
 * A pushpin: round head, needle below. Drawn for the reason [GearIcon] is — U+1F4CC and U+1F4CD are
 * emoji, and come out in the emoji font's colours rather than [color].
 */
@Composable
fun PinIcon(color: Color, filled: Boolean, size: Dp = 12.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.minDimension
        val head = w * 0.27f
        val headCenter = Offset(center.x, head + w * 0.06f)
        drawLine(color, Offset(center.x, headCenter.y), Offset(center.x, w), strokeWidth = w * 0.12f)
        if (filled) drawCircle(color, radius = head, center = headCenter)
        else {
            drawCircle(Tokens.surface, radius = head, center = headCenter)
            drawCircle(color, radius = head, center = headCenter, style = Stroke(width = w * 0.12f))
        }
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
