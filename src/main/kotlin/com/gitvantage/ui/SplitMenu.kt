// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.gitvantage.app.Tokens
import com.gitvantage.app.UiFont

/**
 * One button split by a hairline: the label does the common thing in one click, the ▾ opens the
 * menu of everything else. "Open in" and Snooze both have this shape — a short list of choices of
 * which one is used far more than the rest — and the menu is where the user pins which one that is.
 *
 * [onMain] null makes the label open the menu too, for a state in which the one-click action would
 * be the wrong default (a repo that is already snoozed).
 */
@Composable
fun SplitMenuButton(
    label: String,
    kind: RowActionKind,
    size: SplitSize,
    menuTip: String,
    onMain: (() -> Unit)?,
    menu: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val belowPx = with(LocalDensity.current) { (size.height + 5.dp).roundToPx() }
    val c = kind.colors
    val shape = RoundedCornerShape(size.radius)

    @Composable
    fun Half(text: String, onClick: () -> Unit) = Box(
        Modifier.fillMaxHeight().pointerHoverIcon(PointerIcon.Hand).onTap(onClick)
            .padding(horizontal = size.padH),
        contentAlignment = Alignment.Center,
    ) { Txt(text, size.font, c.text, size.weight) }

    // The popup hangs from the whole control rather than from the ▾, so it doesn't move when the
    // label changes length under it — which pinning does, with the menu still open.
    Box {
        Row(
            Modifier.height(size.height).clip(shape).background(c.fill, shape).border(1.dp, c.border, shape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Half(label, onMain ?: { open = !open })
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.border))
            HoverTip(menuTip) { Half("▾") { open = !open } }
        }
        if (open) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, belowPx),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                val menuShape = RoundedCornerShape(10.dp)
                Column(
                    Modifier.width(210.dp).shadow(10.dp, menuShape).clip(menuShape)
                        .background(Tokens.surface, menuShape).border(1.dp, Tokens.borderDc, menuShape)
                        .dismissOnEscape { open = false }
                        .padding(vertical = 5.dp),
                ) { menu { open = false } }
            }
        }
    }
}

/** A hairline between groups of menu items. */
@Composable
fun MenuDivider() {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .height(1.dp).background(Tokens.borderEd),
    )
}

/**
 * One menu entry. With [onPin], it can be pinned as the [SplitMenuButton]'s one click: the pinned
 * one shows a filled pin always, the others an outline pin only while hovered, so the menu reads as
 * a plain list until you go looking. The pin is its own click target — choosing an entry and pinning
 * it are different acts, and a click on the name should never quietly change what the button does.
 * Pinning leaves the menu open: the button's label changes in place, which is the confirmation.
 */
@Composable
fun MenuItem(
    label: String,
    onClick: () -> Unit,
    pinned: Boolean = false,
    color: Color = Tokens.text2,
    onPin: (() -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth()
            .background(if (hovered) Tokens.rowHoverBg else Color.Transparent)
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand).onTap(onClick)
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(
            label, 12.sp, color, if (pinned) FontWeight.Bold else FontWeight.Medium, font = UiFont,
            modifier = Modifier.weight(1f).padding(vertical = 7.dp),
        )
        if (onPin != null && (pinned || hovered)) {
            HoverTip(if (pinned) "Pinned — the button's one click" else "Pin — make this the button's one click") {
                Box(Modifier.onTap(onPin).padding(6.dp)) {
                    PinIcon(if (pinned) Tokens.accent else Tokens.muted, filled = pinned)
                }
            }
        }
    }
}
