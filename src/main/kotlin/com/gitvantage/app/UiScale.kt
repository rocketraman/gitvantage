// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * How big the UI is drawn, relative to what the display reports.
 *
 * The windowing layer already resolves the display's scale factor, and at the default 100 this
 * changes nothing about it — [Provide] hands the content straight through with no override at all.
 * What this adds is the user's own say in the matter, for the ordinary reasons anyone reaches for a
 * zoom control: a dense dashboard on a large monitor, or the reverse.
 *
 * It also stands as the escape hatch for the case where the platform reports its own scale wrongly.
 * Two such defects were reported against 1.1.0 on a Retina Mac with a 1× external display — a
 * window opening on the secondary display drawn at 2×, and a window dragged between displays drawn
 * at 0.5×. Both were Nucleus bugs, and both are fixed as of the 2.4.0 this app now pins (PR #509
 * and commit 68337964 respectively), so nothing here compensates for them. But a scale factor is
 * ultimately a claim made by the platform about hardware we cannot see, and this is the control
 * that keeps a wrong claim from making the app unusable while it gets fixed properly.
 */
object UiScale {

    /** Zoom steps offered in the Appearance picker. 100 = whatever the display reports. */
    val STEPS = listOf(50, 67, 75, 90, 100, 110, 125, 150, 175, 200)

    const val MIN_PERCENT = 50
    const val MAX_PERCENT = 300

    /** The user's zoom, as a percentage. 100 means "leave the display's own answer alone". */
    var percent by mutableStateOf(100)
        private set

    /** Load the persisted zoom. Called before the first frame, alongside [Theme.load]. */
    fun load() {
        percent = clampPercent(Registry.settings().uiScalePercent)
    }

    fun zoomTo(p: Int) {
        val next = clampPercent(p)
        if (next == percent) return
        percent = next
        Registry.saveSettings(Registry.settings().copy(uiScalePercent = next))
    }

    /** One [STEPS] notch up, for the Ctrl+Shift+= shortcut. */
    fun zoomIn() = zoomTo(stepFrom(percent, up = true))

    /** One [STEPS] notch down, for the Ctrl+Shift+- shortcut. */
    fun zoomOut() = zoomTo(stepFrom(percent, up = false))

    /**
     * The offered step one notch [up] (or down) from [from], or [from] itself when there is none.
     *
     * Stepping walks [STEPS] rather than adding a fixed percentage, so the shortcut and the picker
     * land on exactly the same set of zooms — pressing the shortcut twice and clicking two chips
     * along are the same thing, and no zoom is reachable one way but not the other.
     *
     * It reads [from] as a *bound* rather than as a member of the list, because it need not be one:
     * [MAX_PERCENT] is above the largest step, and registry.json is hand-editable, so anything in
     * range can arrive here. From 105% the next step up is 110 and the next down is 100, which is
     * the only sensible answer to give an in-between value.
     */
    fun stepFrom(from: Int, up: Boolean): Int =
        if (up) STEPS.firstOrNull { it > from } ?: from else STEPS.lastOrNull { it < from } ?: from

    /**
     * Keeps a zoom inside the range the app stays usable at, wherever the value came from — the
     * picker, or a hand-edited registry.json, which is the one that can hold anything at all.
     */
    fun clampPercent(p: Int): Int = p.coerceIn(MIN_PERCENT, MAX_PERCENT)

    /** [reported] scaled by [percent]. Pure, so the arithmetic is testable without a display. */
    fun effectiveDensity(reported: Float, percent: Int): Float =
        reported * clampPercent(percent) / 100f

    /**
     * Draws [content] at the user's zoom.
     *
     * Wraps the *whole* window, title bar included, so the two halves can't end up sized against
     * different densities.
     *
     * The provider is **unconditional**, and must stay that way. Skipping it at 100% to "avoid a
     * pointless wrapper" puts `content()` at two different call sites, and Compose keys state by
     * call site: changing the zoom then moves the whole app between them, disposing the subtree and
     * every `remember` in it — including the `remember { AppState(…) }` that holds the repo list,
     * the selection, and the open dialog. It presented as the Appearance dialog closing the moment
     * a zoom step was clicked, which is the mildest symptom it could have had. Providing a value
     * equal to the current one is cheap and invalidates nothing, so there is no cost to pay here.
     */
    @Composable
    fun Provide(content: @Composable () -> Unit) {
        val reported = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(effectiveDensity(reported.density, percent), reported.fontScale),
            content = content,
        )
    }
}
