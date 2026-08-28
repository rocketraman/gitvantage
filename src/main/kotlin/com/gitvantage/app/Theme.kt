// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.nucleusframework.darkmodedetector.IDarkModeDetector
import dev.nucleusframework.darkmodedetector.NoopDarkModeDetector
import dev.nucleusframework.darkmodedetector.getPlatformDarkModeDetector
import java.util.function.Consumer

/**
 * Which appearance the app wears. [SYSTEM] follows the desktop's own light/dark preference;
 * the other two override it and stay put.
 *
 * [short] is the toolbar button's label, [label] the picker's fuller phrasing.
 *
 * The sun is U+263C WHITE SUN WITH RAYS, **not** U+2600 BLACK SUN WITH RAYS — do not "fix" it
 * back. U+2600 carries the Unicode Emoji property, so a colour-emoji font claims it and wins the
 * fallback: it renders as a large orange emoji that ignores the requested colour, while the moon
 * and half-circle (neither of which is an emoji codepoint) come out as hairline text glyphs. The
 * button then changed visual weight drastically with its own state, and reading as good as
 * invisible in dark. U+263C is claimed only by text fonts, so all three stay consistent.
 */
enum class ThemeMode(val key: String, val label: String, val short: String, val glyph: String) {
    SYSTEM("system", "Match system", "System", "◐"),
    LIGHT("light", "Light", "Light", "☼"),
    DARK("dark", "Dark", "Dark", "☾"),
    ;

    companion object {
        /** Registry values are strings so the file stays hand-editable; unknown ones fall back. */
        fun from(key: String): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/**
 * The app's active appearance, and the one place that decides it.
 *
 * [palette] is what every token in [Tokens] reads. Both of the things it depends on — the user's
 * [mode] and the desktop's own [systemDark] — are snapshot state, so a change to either
 * recomposes whatever read a token, with no theme object threaded through the tree.
 */
object Theme {
    var mode by mutableStateOf(ThemeMode.SYSTEM)
        private set

    /**
     * The *desktop's* preference — not what the app is currently wearing. The two only agree while
     * [mode] is [ThemeMode.SYSTEM]; under a Light or Dark override this still reports what the
     * desktop asked for, which is what the picker's "Match system" row and the toolbar tooltip
     * need in order to say where that option would land you.
     *
     * Kept current by the detector rather than re-read on demand: [detector] pushes every change
     * as the desktop makes it, so this is live even while an override is in force.
     */
    var systemDark by mutableStateOf(false)
        private set

    /**
     * Nucleus's OS dark-mode detector — the same one [dev.nucleusframework.application.nucleusApplication]
     * already installs over Compose's `LocalSystemTheme`, so the app and every library drawing
     * inside it answer this question identically.
     *
     * Talking to it directly rather than through `isSystemInDarkTheme()` because this object is
     * read from outside the composition (and [load] runs before it), and because the listener
     * below is what replaces polling entirely.
     *
     * On Linux it reads `org.freedesktop.appearance color-scheme` from the XDG desktop portal
     * through a bundled JNI library, and subscribes to the portal's `SettingChanged` signal.
     * Deliberately *not* Skiko's `currentSystemTheme`, which asks the same portal but loads
     * libdbus by the unversioned soname — `dlopen("libdbus-1.so")`, a symlink that only exists
     * with a distro's dbus *devel* package installed. On a stock install that load fails and Skiko
     * answers UNKNOWN forever: a light-themed app on a dark desktop for anyone who is not also a C
     * developer (issue #9, https://youtrack.jetbrains.com/issue/SKIKO-1177). Nucleus's library is
     * link-time bound to `libdbus-1.so.3` and has no such failure mode.
     *
     * Acquiring it runs its native library load, so a platform whose library is missing or
     * unloadable would throw here — an `UnsatisfiedLinkError` out of an object initializer, at
     * startup, before the first frame. Falling back to [NoopDarkModeDetector] means that costs the
     * app its "Match system" accuracy (it reports light) rather than its ability to start. The
     * packaged binary's own `--smoke-test` covers the case, since nothing in the UI would.
     */
    private val detector: IDarkModeDetector =
        runCatching { getPlatformDarkModeDetector() }.getOrElse { NoopDarkModeDetector }

    /**
     * Held as one instance so [load] is idempotent: the detector stores listeners in a set, so
     * re-registering the same object is a no-op rather than a second delivery of every change.
     *
     * Called from the detector's own thread — a D-Bus dispatch thread on Linux — which is fine to
     * write snapshot state from: the write lands in the global snapshot and wakes whatever read it.
     */
    private val onSystemThemeChanged = Consumer<Boolean> { systemDark = it }

    val isDark: Boolean
        get() = when (mode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> systemDark
        }

    val palette: Palette get() = if (isDark) DarkPalette else LightPalette

    private var loaded = false

    /**
     * Load the persisted choice, take a first reading of the desktop preference, and subscribe to
     * every later change.
     *
     * The first reading is synchronous by design: this runs before the first frame and *wants* to
     * block, because painting the window in the wrong theme and correcting it is a visible flash.
     * It is one in-process D-Bus round trip. Everything after it arrives on the detector's own
     * signal, so no caller ever needs to ask again.
     *
     * Runs its body once and then does nothing, because the call site is a composable body: the
     * application scope in `Main.kt` reads [isDark] (it hands the window's title bar its theme), so
     * every change to the desktop preference recomposes the very function that calls this. Without
     * the guard the re-read would race the [detector]'s own signal — the listener sets [systemDark],
     * that recomposes the caller, and the re-read overwrites it with whatever a fresh blocking poll
     * of the portal answers. Both readings agree once the portal has settled, so the damage is a
     * flicker and a blocking D-Bus round trip on the UI thread per recomposition, not a wrong theme.
     */
    fun load() {
        if (loaded) return
        loaded = true
        mode = ThemeMode.from(Registry.settings().theme)
        systemDark = runCatching { detector.isDark() }.getOrDefault(false)
        detector.registerListener(onSystemThemeChanged)
    }

    fun switchTo(m: ThemeMode) {
        if (m == mode) return
        mode = m
        Registry.saveSettings(Registry.settings().copy(theme = m.key))
    }
}
