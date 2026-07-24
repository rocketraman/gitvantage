// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

import androidx.compose.ui.graphics.Color

/**
 * Design tokens (the Adwaita-derived palette). Colors are exact hex values; the
 * helpers below keep call sites readable.
 */

/** Parse a CSS-style "#rrggbb" (or "#rrggbbaa") string into a Compose [Color]. */
fun hex(s: String): Color {
    val h = s.removePrefix("#")
    return when (h.length) {
        6 -> Color(0xFF000000 or h.toLong(16))
        8 -> {
            val rgba = h.toLong(16)
            val a = rgba and 0xFF
            val rgb = rgba ushr 8
            Color((a shl 24) or rgb)
        }
        else -> error("bad hex $s")
    }
}

object Tokens {
    // Accent (Adwaita blue). Alternatives in README: #2190a4, #813d9c, #c64600.
    val accent = hex("#3584e4")

    // Neutrals
    val windowBg = hex("#e9e8e5")
    val surface = hex("#ffffff")
    val panelF7 = hex("#f7f6f4")
    val panelFa = hex("#faf9f7")
    val panelFb = hex("#fbfbfa")
    val headerTop = hex("#ffffff")
    val headerBottom = hex("#f6f5f3")

    // Borders (light → dark)
    val borderE6 = hex("#e6e5e2")
    val borderEd = hex("#edece9")
    val borderE2 = hex("#e2e1dd")
    val borderDc = hex("#dcdbd7")
    val borderD8 = hex("#d8d7d3")
    val rowBorder = hex("#f2f1ee")
    val groupHeaderBg = hex("#f1f0ed")
    val groupHeaderBorder = hex("#e4e3e0")

    // Text
    val text = hex("#241f31")
    val text2 = hex("#3d3846")
    val secondary = hex("#5e5c64")
    val muted = hex("#77767b")
    val muted2 = hex("#9a9996")

    // State colors
    val green = hex("#2ec27e")   // clean / staged
    val amber = hex("#e5a50a")   // dirty / modified
    val red = hex("#e01b24")     // warning / stale (dot)
    val redText = hex("#c0181f") // warning / stale (text)
    val purple = hex("#813d9c")  // stash
    val gray = hex("#77767b")    // untracked
    val behind = hex("#986a44")  // behind arrows
    val untrackedSeg = hex("#c8c7c4")

    // Git-state badge tints
    val tintGreen = hex("#e6f6ee")
    val tintAmber = hex("#fbf1d9")
    val tintGray = hex("#efeeeb")
    val tintRed = hex("#fbe9ea")
    val tintPurple = hex("#f3e9f6")
    val tintBlue = hex("#eaf2fc")

    // Segmented / control fills
    val segTrack = hex("#efeeeb")
    val changeTrack = hex("#edece9")

    // Reminder colors
    val remTeal = hex("#0e7490")
    val remTealBg = hex("#e0f2f5")
    val remOverdue = hex("#c0181f")
    val remOverdueBg = hex("#fbe9ea")

    // Snooze (amber pill + violet banner)
    val snoozeBtnBg = hex("#fdf3d7")
    val snoozeBtnBorder = hex("#ecd58a")
    val snoozeBtnText = hex("#8a6d0b")
    val snoozeChipBg = hex("#f1eefb")
    val snoozeChipBorder = hex("#ddc9ef")
    val snoozeChipText = hex("#813d9c")

    // Detail banners
    val cleanBg = hex("#e9f7ef"); val cleanBorder = hex("#b8e6cd"); val cleanText = hex("#1a8049")
    val warnBg = hex("#fceaea"); val warnBorder = hex("#f2b8b9")
    val snoozeBannerBg = hex("#f5f1fb"); val snoozeBannerBorder = hex("#ddc9ef"); val snoozeBannerText = hex("#6c2d8f")
    val stashCardBg = hex("#f8f0fb"); val stashCardBorder = hex("#e6ccf0")

    // Window controls
    val winCtrlBg = hex("#e4e3e0")
    val winCtrlGlyph = hex("#5e5c64")

    // File-section header colors
    val stagedHdr = hex("#1a8049")
    val modifiedHdr = hex("#a06f08")
    val untrackedHdr = hex("#77767b")
    val stagedDot = hex("#2ec27e")
    val modifiedDot = hex("#e5a50a")
    val untrackedDot = hex("#a8a7a3")
}

/** A namespace's (color, tint, border) triple used for tag chips and headings. */
data class NsPalette(val c: Color, val t: Color, val b: Color)

val nsPalettes: Map<String, NsPalette> = mapOf(
    "owner" to NsPalette(hex("#1c6fd6"), hex("#eaf2fc"), hex("#c3dcf8")),
    "lang" to NsPalette(hex("#0e7490"), hex("#e0f2f5"), hex("#a8dbe4")),
    "stage" to NsPalette(hex("#813d9c"), hex("#f3e9f6"), hex("#ddc9ef")),
    "priority" to NsPalette(hex("#b0500a"), hex("#fbeede"), hex("#f0cfa8")),
)
val nsDefaultPalette = NsPalette(hex("#5e5c64"), hex("#efeeeb"), hex("#dcdbd7"))

fun nsPalette(ns: String): NsPalette = nsPalettes[ns] ?: nsDefaultPalette

/** Status-filter chip palette (§3): color / tint / border per status key. */
val statusPalette: Map<String, NsPalette> = mapOf(
    "all" to NsPalette(hex("#5e5c64"), hex("#efeeeb"), hex("#dcdbd7")),
    "dirty" to NsPalette(hex("#a06f08"), hex("#fbf1d9"), hex("#ecd58a")),
    "aging" to NsPalette(hex("#a06f08"), hex("#fbf1d9"), hex("#ecd58a")),
    "unpushed" to NsPalette(Tokens.accent, hex("#eaf2fc"), hex("#c3dcf8")),
    "behind" to NsPalette(hex("#b5531a"), hex("#fbe9dc"), hex("#f2c3a0")),
    // Blue, not amber/red: staleness is informational by default (stable code goes untouched);
    // repos marked stale-important render amber instead. Slate blue keeps it distinct from the
    // accent blue used by Unpushed.
    "stale" to NsPalette(hex("#3a6ea5"), hex("#e8eff7"), hex("#bcd3e8")),
    "issues" to NsPalette(hex("#813d9c"), hex("#f3e9f6"), hex("#ddc9ef")),
    "stashes" to NsPalette(hex("#7a4f01"), hex("#f6eede"), hex("#e6d3a8")),
    "reminders" to NsPalette(hex("#0e7490"), hex("#e0f2f5"), hex("#a8dbe4")),
    "notes" to NsPalette(hex("#556080"), hex("#eef0f5"), hex("#cdd3e0")),
    "snoozed" to NsPalette(hex("#813d9c"), hex("#f1eefb"), hex("#ddc9ef")),
    "recent" to NsPalette(hex("#1a8049"), hex("#e6f6ee"), hex("#b8e6cd")),
    "recentmod" to NsPalette(hex("#0f766e"), hex("#e3f4f2"), hex("#a9dcd6")),
)
