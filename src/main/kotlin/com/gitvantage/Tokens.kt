// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * Design tokens (the Adwaita-derived palette). Colors are exact hex values; the
 * helpers below keep call sites readable.
 *
 * There are two palettes — [LightPalette] and [DarkPalette] — and [Tokens] reads whichever
 * [Theme] currently has active. Every token is a `get()` rather than a stored value, so the
 * ~400 `Tokens.x` call sites need no ceremony: reading one inside composition (or inside a
 * draw scope) registers a snapshot read on [Theme.palette], and flipping the theme recomposes
 * exactly what depends on it. The same reason means **nothing may cache a token in a top-level
 * `val` or a `remember` without keying on the theme** — it would freeze at whatever the palette
 * was at first read.
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

/**
 * Every themeable color in the app, resolved for one appearance.
 *
 * The light values are the original hand-tuned Adwaita handoff and are reproduced here
 * unchanged. The dark ones are their counterparts: neutrals inverted in *order* (so what was
 * the brightest surface is still the frontmost one), hues taken from the lighter end of the
 * Adwaita ramp so they carry on a dark ground, and the tint/border families derived from those
 * hues by [tintOver] rather than hand-picked — a chip's fill is the chip's own color laid over
 * the surface it sits on, which keeps twenty-odd chip palettes consistent for free.
 */
class Palette(
    val isDark: Boolean,

    // Accent. [accent] is the standalone/foreground blue (it has to read against [surface]);
    // [accentFill] is the one a filled button is painted with, and [onAccent] is what goes on
    // top of it. Light keeps them the same color; dark lightens the foreground only, because a
    // blue legible on #242424 is too pale to put white text on.
    val accent: Color,
    val accentFill: Color,
    val onAccent: Color,
    val accentBorder: Color,
    /**
     * What goes on top of a fill painted in one of the *chip* hues — the count bubble inside an
     * active status chip, say. Distinct from [onAccent] because those hues are chosen to read
     * against the app's background: light in dark mode, dark in light mode. So the label on them
     * has to invert too, where [onAccent] can stay white on both themes' fixed [accentFill].
     */
    val onBright: Color,
    /** The fill under a destructive button's label. Dark enough for [onAccent] in both themes. */
    val dangerFill: Color,

    // Neutrals
    val windowBg: Color,
    val surface: Color,
    val panelF7: Color,
    val panelFa: Color,
    val panelFb: Color,
    val headerTop: Color,
    val headerBottom: Color,

    // Borders (subtlest → strongest)
    val rowBorder: Color,
    val borderEd: Color,
    val borderE6: Color,
    val groupHeaderBorder: Color,
    val borderE2: Color,
    val borderDc: Color,
    val borderD8: Color,
    val borderCb: Color,
    val rowHoverBg: Color,
    val groupHeaderBg: Color,
    val sectionBorder: Color,
    val hairline: Color,

    // Text
    val text: Color,
    val text2: Color,
    val secondary: Color,
    val muted: Color,
    val muted2: Color,

    // State colors
    val green: Color,
    val amber: Color,
    val red: Color,
    val redText: Color,
    val purple: Color,
    val gray: Color,
    val behind: Color,
    val untrackedSeg: Color,

    // Git-state badge tints
    val tintGreen: Color,
    val tintAmber: Color,
    val tintGray: Color,
    val tintRed: Color,
    val tintPurple: Color,
    val tintBlue: Color,

    // Segmented / control fills
    val segTrack: Color,
    val changeTrack: Color,

    // Reminder colors
    val remTeal: Color,
    val remTealBg: Color,
    val remOverdue: Color,
    val remOverdueBg: Color,

    // Snooze (amber pill + violet banner)
    val snoozeBtnBg: Color,
    val snoozeBtnBorder: Color,
    val snoozeBtnText: Color,
    val snoozeChipBg: Color,
    val snoozeChipBorder: Color,
    val snoozeChipText: Color,

    // Detail banners
    val cleanBg: Color,
    val cleanBorder: Color,
    val cleanText: Color,
    val warnBg: Color,
    val warnBorder: Color,
    val snoozeBannerBg: Color,
    val snoozeBannerBorder: Color,
    val snoozeBannerText: Color,

    // Window controls
    val winCtrlBg: Color,
    val winCtrlGlyph: Color,

    // File-section header colors
    val stagedHdr: Color,
    val modifiedHdr: Color,
    val untrackedHdr: Color,
    val stagedDot: Color,
    val modifiedDot: Color,
    val untrackedDot: Color,

    // Diff viewer. [addFg]/[delFg] are the +N/−N counters and the "✓ Copied" confirmation;
    // the diff* family is the side-by-side body, where Mark is the character-level highlight.
    val addFg: Color,
    val delFg: Color,
    val okBorder: Color,
    val diffAddBg: Color,
    val diffAddFg: Color,
    val diffAddMark: Color,
    val diffDelBg: Color,
    val diffDelFg: Color,
    val diffDelMark: Color,
    val diffEmptyBg: Color,

    // Overlays and tooltips
    val scrim: Color,
    val scrimSoft: Color,
    val tooltipBg: Color,
    val tooltipText: Color,
    val emptyGlyph: Color,

    /** ANSI foreground 30–37 / 90–97, tuned for the console background of this theme. */
    val ansi: List<Color>,

    /** Namespace chip palettes for tags ("owner:", "lang:", …) and the default for the rest. */
    val nsPalettes: Map<String, NsPalette>,
    val nsDefault: NsPalette,
    /** Status-filter chip palette: color / tint / border per status key. */
    val statusPalette: Map<String, NsPalette>,
    /** The "✎ note" chip that rides along with the git-state badges. */
    val noteChip: NsPalette,
)

/**
 * A chip fill or border: [c] laid over [surface] at [alpha].
 *
 * Only used to build the dark palette. Dark chips can't be hand-picked pastels the way the
 * light ones are — there is no "pale version" of a color on a #242424 ground, only a dimmer
 * one — so fill and border are the chip's own hue at two alphas over its background, which
 * gives every chip the same weight regardless of hue.
 */
private fun tintOver(surface: Color, c: Color, alpha: Float): Color =
    c.copy(alpha = alpha).compositeOver(surface)

private const val DARK_TINT = 0.16f
private const val DARK_BORDER = 0.38f

val LightPalette = Palette(
    isDark = false,

    // Accent (Adwaita blue). Alternatives in README: #2190a4, #813d9c, #c64600.
    accent = hex("#3584e4"),
    accentFill = hex("#3584e4"),
    onAccent = hex("#ffffff"),
    accentBorder = hex("#c3dcf8"),
    onBright = hex("#ffffff"),
    dangerFill = hex("#c0181f"),

    windowBg = hex("#e9e8e5"),
    surface = hex("#ffffff"),
    panelF7 = hex("#f7f6f4"),
    panelFa = hex("#faf9f7"),
    panelFb = hex("#fbfbfa"),
    headerTop = hex("#ffffff"),
    headerBottom = hex("#f6f5f3"),

    rowBorder = hex("#f2f1ee"),
    borderEd = hex("#edece9"),
    borderE6 = hex("#e6e5e2"),
    groupHeaderBorder = hex("#e4e3e0"),
    borderE2 = hex("#e2e1dd"),
    borderDc = hex("#dcdbd7"),
    borderD8 = hex("#d8d7d3"),
    borderCb = hex("#cbc9c5"),
    // The tint a detail-panel list row wears while the pointer is on it. Deliberately faint: its
    // job is to say "this row is the one you're asking about" as the hover-only actions appear,
    // not to compete with the badges sitting on the row.
    rowHoverBg = hex("#f2f1ee"),
    groupHeaderBg = hex("#f1f0ed"),
    sectionBorder = hex("#ecebe8"),
    hairline = Color.Black.copy(alpha = 0.07f),

    text = hex("#241f31"),
    text2 = hex("#3d3846"),
    secondary = hex("#5e5c64"),
    muted = hex("#77767b"),
    muted2 = hex("#9a9996"),

    green = hex("#2ec27e"),   // clean / staged
    amber = hex("#e5a50a"),   // dirty / modified
    red = hex("#e01b24"),     // warning / stale (dot)
    redText = hex("#c0181f"), // warning / stale (text)
    purple = hex("#813d9c"),  // stash
    gray = hex("#77767b"),    // untracked
    behind = hex("#986a44"),  // behind arrows
    untrackedSeg = hex("#c8c7c4"),

    tintGreen = hex("#e6f6ee"),
    tintAmber = hex("#fbf1d9"),
    tintGray = hex("#efeeeb"),
    tintRed = hex("#fbe9ea"),
    tintPurple = hex("#f3e9f6"),
    tintBlue = hex("#eaf2fc"),

    segTrack = hex("#efeeeb"),
    changeTrack = hex("#edece9"),

    remTeal = hex("#0e7490"),
    remTealBg = hex("#e0f2f5"),
    remOverdue = hex("#c0181f"),
    remOverdueBg = hex("#fbe9ea"),

    snoozeBtnBg = hex("#fdf3d7"),
    snoozeBtnBorder = hex("#ecd58a"),
    snoozeBtnText = hex("#8a6d0b"),
    snoozeChipBg = hex("#f1eefb"),
    snoozeChipBorder = hex("#ddc9ef"),
    snoozeChipText = hex("#813d9c"),

    cleanBg = hex("#e9f7ef"),
    cleanBorder = hex("#b8e6cd"),
    cleanText = hex("#1a8049"),
    warnBg = hex("#fceaea"),
    warnBorder = hex("#f2b8b9"),
    snoozeBannerBg = hex("#f5f1fb"),
    snoozeBannerBorder = hex("#ddc9ef"),
    snoozeBannerText = hex("#6c2d8f"),

    winCtrlBg = hex("#e4e3e0"),
    winCtrlGlyph = hex("#5e5c64"),

    stagedHdr = hex("#1a8049"),
    modifiedHdr = hex("#a06f08"),
    untrackedHdr = hex("#77767b"),
    stagedDot = hex("#2ec27e"),
    modifiedDot = hex("#e5a50a"),
    untrackedDot = hex("#a8a7a3"),

    addFg = hex("#1a7f37"),
    delFg = hex("#b0181f"),
    okBorder = hex("#9fd8b0"),
    diffAddBg = hex("#e6f9ee"),
    diffAddFg = hex("#12622f"),
    diffAddMark = hex("#a6ecbf"),
    diffDelBg = hex("#fce9ea"),
    diffDelFg = hex("#9a1720"),
    diffDelMark = hex("#f4aab0"),
    diffEmptyBg = hex("#f6f6f4"),

    scrim = Color(0x55000000),
    scrimSoft = Color(0x33000000),
    tooltipBg = hex("#241f31"),
    tooltipText = hex("#ffffff"),
    emptyGlyph = hex("#b0aeaa"),

    ansi = listOf(
        hex("#3d3d3d"), hex("#c0181f"), hex("#1a8049"), hex("#8a6d00"),
        hex("#1a5fb4"), hex("#9c27b0"), hex("#0e7490"), hex("#5e5c64"),
    ),

    nsPalettes = mapOf(
        "owner" to NsPalette(hex("#1c6fd6"), hex("#eaf2fc"), hex("#c3dcf8")),
        "lang" to NsPalette(hex("#0e7490"), hex("#e0f2f5"), hex("#a8dbe4")),
        "stage" to NsPalette(hex("#813d9c"), hex("#f3e9f6"), hex("#ddc9ef")),
        "priority" to NsPalette(hex("#b0500a"), hex("#fbeede"), hex("#f0cfa8")),
    ),
    nsDefault = NsPalette(hex("#5e5c64"), hex("#efeeeb"), hex("#dcdbd7")),

    statusPalette = mapOf(
        "all" to NsPalette(hex("#5e5c64"), hex("#efeeeb"), hex("#dcdbd7")),
        "dirty" to NsPalette(hex("#a06f08"), hex("#fbf1d9"), hex("#ecd58a")),
        "aging" to NsPalette(hex("#a06f08"), hex("#fbf1d9"), hex("#ecd58a")),
        "unpushed" to NsPalette(hex("#3584e4"), hex("#eaf2fc"), hex("#c3dcf8")),
        "behind" to NsPalette(hex("#b5531a"), hex("#fbe9dc"), hex("#f2c3a0")),
        // Blue, not amber/red: staleness is informational by default (stable code goes untouched);
        // repos marked stale-important render amber instead. Slate blue keeps it distinct from the
        // accent blue used by Unpushed.
        "stale" to NsPalette(hex("#3a6ea5"), hex("#e8eff7"), hex("#bcd3e8")),
        "problems" to NsPalette(hex("#813d9c"), hex("#f3e9f6"), hex("#ddc9ef")),
        // GitHub issue tracker. Open issues are informational, so a blue — but a teal-leaning one, to
        // stay distinguishable from Unpushed's accent blue and Stale's slate blue at chip size.
        "ghopen" to NsPalette(hex("#1a6f8b"), hex("#e4f1f6"), hex("#b3d8e5")),
        // Awaiting you is a call to action, so it sits in the amber family with Dirty and Aging.
        "ghawaiting" to NsPalette(hex("#a06f08"), hex("#fbf1d9"), hex("#ecd58a")),
        "stashes" to NsPalette(hex("#7a4f01"), hex("#f6eede"), hex("#e6d3a8")),
        // Extra checkouts are structural information, like Stashes — an indigo, far enough from the
        // three blues already in this row (Unpushed, Stale, Open Issues) to read as its own thing.
        "worktrees" to NsPalette(hex("#4c4f9c"), hex("#ecedf8"), hex("#c9cbe9")),
        "reminders" to NsPalette(hex("#0e7490"), hex("#e0f2f5"), hex("#a8dbe4")),
        "notes" to NsPalette(hex("#556080"), hex("#eef0f5"), hex("#cdd3e0")),
        "snoozed" to NsPalette(hex("#813d9c"), hex("#f1eefb"), hex("#ddc9ef")),
        "recent" to NsPalette(hex("#1a8049"), hex("#e6f6ee"), hex("#b8e6cd")),
        "recentmod" to NsPalette(hex("#0f766e"), hex("#e3f4f2"), hex("#a9dcd6")),
    ),
    noteChip = NsPalette(hex("#556080"), hex("#eef0f5"), hex("#cdd3e0")),
)

val DarkPalette = run {
    val surface = hex("#242424")
    fun chip(c: Color) = NsPalette(c, tintOver(surface, c, DARK_TINT), tintOver(surface, c, DARK_BORDER))

    val accent = hex("#78aeed")
    val green = hex("#33d17a")
    val amber = hex("#f5c211")
    val red = hex("#f66151")
    val redText = hex("#f87f7a")
    val purple = hex("#c061cb")
    val gray = hex("#949290")
    val teal = hex("#4fc3dd")
    val snoozeText = hex("#c98ad8")
    val cleanText = hex("#6ede9c")
    val amberText = hex("#f0c674")

    Palette(
        isDark = true,

        accent = accent,
        // The filled-button blue stays the light theme's: it is a *background*, and white on
        // #3584e4 is the pair that was contrast-checked. Lightening it too would leave the label
        // floating on a washed-out fill.
        accentFill = hex("#3584e4"),
        onAccent = hex("#ffffff"),
        accentBorder = tintOver(surface, accent, DARK_BORDER),
        onBright = hex("#1b1b1b"),
        // Adwaita red4 rather than the palette's own [red]: #f66151 is picked to be legible *on*
        // a dark ground, which makes it far too pale to be one.
        dangerFill = hex("#c01c28"),

        windowBg = hex("#181818"),
        surface = surface,
        panelF7 = hex("#1e1e1e"),
        panelFa = hex("#212121"),
        panelFb = hex("#232323"),
        headerTop = hex("#2a2a2a"),
        headerBottom = hex("#242424"),

        // Inverted in direction, not in value: on a dark ground a border reads by being *lighter*
        // than the surface, so the ramp runs up from #2e2e2e instead of down from #f2f1ee — but
        // the ordering (rowBorder faintest → borderCb strongest) is preserved, so every call site
        // keeps the weight it was written for.
        rowBorder = hex("#2e2e2e"),
        borderEd = hex("#313131"),
        borderE6 = hex("#363636"),
        groupHeaderBorder = hex("#3a3a3a"),
        borderE2 = hex("#3d3d3d"),
        borderDc = hex("#454545"),
        borderD8 = hex("#4d4d4d"),
        borderCb = hex("#565656"),
        rowHoverBg = hex("#2f2f2f"),
        groupHeaderBg = hex("#2c2c2c"),
        sectionBorder = hex("#333333"),
        hairline = Color.White.copy(alpha = 0.10f),

        text = hex("#f5f4f3"),
        text2 = hex("#dcdad7"),
        secondary = hex("#b5b3b0"),
        muted = hex("#949290"),
        muted2 = hex("#7c7a78"),

        green = green,
        amber = amber,
        red = red,
        redText = redText,
        purple = purple,
        gray = gray,
        behind = hex("#cf9a63"),
        untrackedSeg = hex("#565452"),

        tintGreen = tintOver(surface, green, DARK_TINT),
        tintAmber = tintOver(surface, amber, DARK_TINT),
        tintGray = tintOver(surface, gray, DARK_TINT),
        tintRed = tintOver(surface, red, DARK_TINT),
        tintPurple = tintOver(surface, purple, DARK_TINT),
        tintBlue = tintOver(surface, accent, DARK_TINT),

        // Recessed rather than raised: a track is a hole the thumb sits in, so it goes darker
        // than the surface in both themes.
        segTrack = hex("#1a1a1a"),
        changeTrack = hex("#333333"),

        remTeal = teal,
        remTealBg = tintOver(surface, teal, DARK_TINT),
        remOverdue = redText,
        remOverdueBg = tintOver(surface, red, DARK_TINT),

        snoozeBtnBg = tintOver(surface, amber, DARK_TINT),
        snoozeBtnBorder = tintOver(surface, amber, DARK_BORDER),
        snoozeBtnText = amberText,
        snoozeChipBg = tintOver(surface, purple, DARK_TINT),
        snoozeChipBorder = tintOver(surface, purple, DARK_BORDER),
        snoozeChipText = snoozeText,

        cleanBg = tintOver(surface, green, DARK_TINT),
        cleanBorder = tintOver(surface, green, DARK_BORDER),
        cleanText = cleanText,
        warnBg = tintOver(surface, red, DARK_TINT),
        warnBorder = tintOver(surface, red, DARK_BORDER),
        snoozeBannerBg = tintOver(surface, purple, DARK_TINT),
        snoozeBannerBorder = tintOver(surface, purple, DARK_BORDER),
        snoozeBannerText = snoozeText,

        winCtrlBg = hex("#3a3a3a"),
        winCtrlGlyph = hex("#c0bfbc"),

        stagedHdr = cleanText,
        modifiedHdr = amberText,
        untrackedHdr = gray,
        stagedDot = green,
        modifiedDot = amber,
        untrackedDot = hex("#6b6967"),

        addFg = cleanText,
        delFg = redText,
        okBorder = tintOver(surface, green, DARK_BORDER),
        // The diff body is its own surface (it sits on a white sheet in light mode), so its
        // add/delete grounds are mixed over that sheet's dark counterpart, not over [surface].
        diffAddBg = hex("#16301f"),
        diffAddFg = hex("#8fe3b0"),
        diffAddMark = hex("#2f6b45"),
        diffDelBg = hex("#3a1d1f"),
        diffDelFg = hex("#f7a5a0"),
        diffDelMark = hex("#7d3237"),
        diffEmptyBg = hex("#1c1c1c"),

        // Heavier than the light theme's: a 33% black veil over a dark app leaves the modal
        // barely separated from what it covers.
        scrim = Color(0x8C000000),
        scrimSoft = Color(0x73000000),
        // A raised grey, not the light theme's inverted near-black — an *even darker* tooltip on a
        // dark app reads as a hole rather than something floating above the page.
        tooltipBg = hex("#3d3d3d"),
        tooltipText = hex("#ffffff"),
        emptyGlyph = hex("#5c5a58"),

        ansi = listOf(
            hex("#dcdad7"), redText, cleanText, amber,
            accent, hex("#d18ade"), teal, hex("#b5b3b0"),
        ),

        nsPalettes = mapOf(
            "owner" to chip(hex("#7fb2f0")),
            "lang" to chip(teal),
            "stage" to chip(snoozeText),
            "priority" to chip(hex("#e79a5c")),
        ),
        nsDefault = chip(hex("#a9a7a4")),

        statusPalette = mapOf(
            "all" to chip(hex("#a9a7a4")),
            "dirty" to chip(amberText),
            "aging" to chip(amberText),
            "unpushed" to chip(accent),
            "behind" to chip(hex("#e3945a")),
            "stale" to chip(hex("#8ab4dd")),
            "problems" to chip(snoozeText),
            "ghopen" to chip(hex("#6cbcd6")),
            "ghawaiting" to chip(amberText),
            "stashes" to chip(hex("#dcb567")),
            "worktrees" to chip(hex("#9a9de0")),
            "reminders" to chip(teal),
            "notes" to chip(hex("#a3aecb")),
            "snoozed" to chip(snoozeText),
            "recent" to chip(cleanText),
            "recentmod" to chip(hex("#57c9bd")),
        ),
        noteChip = chip(hex("#a3aecb")),
    )
}

/**
 * The active palette, one token at a time.
 *
 * Every member delegates to [Theme.palette]; see the file header for why they are all `get()`.
 */
object Tokens {
    val accent: Color get() = Theme.palette.accent
    val accentFill: Color get() = Theme.palette.accentFill
    val onAccent: Color get() = Theme.palette.onAccent
    val accentBorder: Color get() = Theme.palette.accentBorder
    val onBright: Color get() = Theme.palette.onBright
    val dangerFill: Color get() = Theme.palette.dangerFill

    val windowBg: Color get() = Theme.palette.windowBg
    val surface: Color get() = Theme.palette.surface
    val panelF7: Color get() = Theme.palette.panelF7
    val panelFa: Color get() = Theme.palette.panelFa
    val panelFb: Color get() = Theme.palette.panelFb
    val headerTop: Color get() = Theme.palette.headerTop
    val headerBottom: Color get() = Theme.palette.headerBottom

    val rowBorder: Color get() = Theme.palette.rowBorder
    val borderEd: Color get() = Theme.palette.borderEd
    val borderE6: Color get() = Theme.palette.borderE6
    val groupHeaderBorder: Color get() = Theme.palette.groupHeaderBorder
    val borderE2: Color get() = Theme.palette.borderE2
    val borderDc: Color get() = Theme.palette.borderDc
    val borderD8: Color get() = Theme.palette.borderD8
    val borderCb: Color get() = Theme.palette.borderCb
    val rowHoverBg: Color get() = Theme.palette.rowHoverBg
    val groupHeaderBg: Color get() = Theme.palette.groupHeaderBg
    val sectionBorder: Color get() = Theme.palette.sectionBorder
    val hairline: Color get() = Theme.palette.hairline

    val text: Color get() = Theme.palette.text
    val text2: Color get() = Theme.palette.text2
    val secondary: Color get() = Theme.palette.secondary
    val muted: Color get() = Theme.palette.muted
    val muted2: Color get() = Theme.palette.muted2

    val green: Color get() = Theme.palette.green
    val amber: Color get() = Theme.palette.amber
    val red: Color get() = Theme.palette.red
    val redText: Color get() = Theme.palette.redText
    val purple: Color get() = Theme.palette.purple
    val gray: Color get() = Theme.palette.gray
    val behind: Color get() = Theme.palette.behind
    val untrackedSeg: Color get() = Theme.palette.untrackedSeg

    val tintGreen: Color get() = Theme.palette.tintGreen
    val tintAmber: Color get() = Theme.palette.tintAmber
    val tintGray: Color get() = Theme.palette.tintGray
    val tintRed: Color get() = Theme.palette.tintRed
    val tintPurple: Color get() = Theme.palette.tintPurple
    val tintBlue: Color get() = Theme.palette.tintBlue

    val segTrack: Color get() = Theme.palette.segTrack
    val changeTrack: Color get() = Theme.palette.changeTrack

    val remTeal: Color get() = Theme.palette.remTeal
    val remTealBg: Color get() = Theme.palette.remTealBg
    val remOverdue: Color get() = Theme.palette.remOverdue
    val remOverdueBg: Color get() = Theme.palette.remOverdueBg

    val snoozeBtnBg: Color get() = Theme.palette.snoozeBtnBg
    val snoozeBtnBorder: Color get() = Theme.palette.snoozeBtnBorder
    val snoozeBtnText: Color get() = Theme.palette.snoozeBtnText
    val snoozeChipBg: Color get() = Theme.palette.snoozeChipBg
    val snoozeChipBorder: Color get() = Theme.palette.snoozeChipBorder
    val snoozeChipText: Color get() = Theme.palette.snoozeChipText

    val cleanBg: Color get() = Theme.palette.cleanBg
    val cleanBorder: Color get() = Theme.palette.cleanBorder
    val cleanText: Color get() = Theme.palette.cleanText
    val warnBg: Color get() = Theme.palette.warnBg
    val warnBorder: Color get() = Theme.palette.warnBorder
    val snoozeBannerBg: Color get() = Theme.palette.snoozeBannerBg
    val snoozeBannerBorder: Color get() = Theme.palette.snoozeBannerBorder
    val snoozeBannerText: Color get() = Theme.palette.snoozeBannerText

    val winCtrlBg: Color get() = Theme.palette.winCtrlBg
    val winCtrlGlyph: Color get() = Theme.palette.winCtrlGlyph

    val stagedHdr: Color get() = Theme.palette.stagedHdr
    val modifiedHdr: Color get() = Theme.palette.modifiedHdr
    val untrackedHdr: Color get() = Theme.palette.untrackedHdr
    val stagedDot: Color get() = Theme.palette.stagedDot
    val modifiedDot: Color get() = Theme.palette.modifiedDot
    val untrackedDot: Color get() = Theme.palette.untrackedDot

    val addFg: Color get() = Theme.palette.addFg
    val delFg: Color get() = Theme.palette.delFg
    val okBorder: Color get() = Theme.palette.okBorder
    val diffAddBg: Color get() = Theme.palette.diffAddBg
    val diffAddFg: Color get() = Theme.palette.diffAddFg
    val diffAddMark: Color get() = Theme.palette.diffAddMark
    val diffDelBg: Color get() = Theme.palette.diffDelBg
    val diffDelFg: Color get() = Theme.palette.diffDelFg
    val diffDelMark: Color get() = Theme.palette.diffDelMark
    val diffEmptyBg: Color get() = Theme.palette.diffEmptyBg

    val scrim: Color get() = Theme.palette.scrim
    val scrimSoft: Color get() = Theme.palette.scrimSoft
    val tooltipBg: Color get() = Theme.palette.tooltipBg
    val tooltipText: Color get() = Theme.palette.tooltipText
    val emptyGlyph: Color get() = Theme.palette.emptyGlyph

    val ansi: List<Color> get() = Theme.palette.ansi
    val noteChip: NsPalette get() = Theme.palette.noteChip
}

/** A namespace's (color, tint, border) triple used for tag chips and headings. */
data class NsPalette(val c: Color, val t: Color, val b: Color)

fun nsPalette(ns: String): NsPalette = Theme.palette.nsPalettes[ns] ?: Theme.palette.nsDefault

/** The status-filter chip palette for [key] (§3), in the active theme. */
fun statusPalette(key: String): NsPalette = Theme.palette.statusPalette[key] ?: Theme.palette.nsDefault
