// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

/**
 * Fonts from the README tokens (§ Fonts): UI = Cantarell, mono = JetBrains Mono.
 * The .ttf/.otf files are bundled under src/main/resources/fonts and loaded from the
 * classpath, so the app doesn't depend on system font installs. If loading fails for
 * any reason we fall back to the platform sans / monospace families.
 *
 * Cantarell ships only Regular (400) and Bold (700); intermediate UI weights
 * (Medium/SemiBold) resolve to the nearest available weight.
 */
private fun loadUiFont(): FontFamily = runCatching {
    FontFamily(
        Font("fonts/Cantarell-Regular.otf", FontWeight.Normal),
        Font("fonts/Cantarell-Bold.otf", FontWeight.Bold),
    )
}.getOrDefault(FontFamily.SansSerif)

private fun loadMonoFont(): FontFamily = runCatching {
    FontFamily(
        Font("fonts/JetBrainsMono-Regular.ttf", FontWeight.Normal),
        Font("fonts/JetBrainsMono-Medium.ttf", FontWeight.Medium),
        Font("fonts/JetBrainsMono-SemiBold.ttf", FontWeight.SemiBold),
        Font("fonts/JetBrainsMono-Bold.ttf", FontWeight.Bold),
    )
}.getOrDefault(FontFamily.Monospace)

/** UI font family (Cantarell), used everywhere except mono contexts. */
val UiFont: FontFamily = loadUiFont()

/** Monospace family (JetBrains Mono) for branches, counts, file paths, tag input. */
val MonoFont: FontFamily = loadMonoFont()
