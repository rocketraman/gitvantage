// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

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
 * [mode] and the last-detected [systemDark] — are snapshot state, so a change to either
 * recomposes whatever read a token, with no theme object threaded through the tree.
 */
object Theme {
    var mode by mutableStateOf(ThemeMode.SYSTEM)
        private set

    /**
     * The *desktop's* preference as of the last refresh — not what the app is currently wearing.
     * The two only agree while [mode] is [ThemeMode.SYSTEM]; under a Light or Dark override this
     * still reports what the desktop asked for, which is what the picker's "Match system" row and
     * the toolbar tooltip need in order to say where that option would land you.
     */
    var systemDark by mutableStateOf(false)
        private set

    /**
     * Whether the desktop told us anything at all. False means every probe below came up empty
     * (no portal, no `defaults`, headless session…), which is why "Match system" says so in the
     * picker instead of silently pretending the desktop chose light.
     */
    var systemKnown by mutableStateOf(false)
        private set

    val isDark: Boolean
        get() = when (mode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> systemDark
        }

    val palette: Palette get() = if (isDark) DarkPalette else LightPalette

    /** Load the persisted choice and take a first reading of the desktop preference. */
    fun load() {
        mode = ThemeMode.from(Registry.settings().theme)
        refreshSystemPreference()
    }

    fun switchTo(m: ThemeMode) {
        if (m == mode) return
        mode = m
        // No re-probe here: this runs on the UI thread from a click handler, and the probe can
        // block for up to its timeout. The poller in `GitVantageApp` is keyed on [mode], so
        // choosing "Match system" restarts it and it reads the desktop immediately, off-thread.
        Registry.saveSettings(Registry.settings().copy(theme = m.key))
    }

    /**
     * Re-probe the desktop's light/dark preference, blocking until it answers.
     *
     * Only for [load], which runs before the first frame and *wants* to block — painting the
     * window in the wrong theme and correcting it is a visible flash. Everywhere else, use
     * [refreshSystemPreferenceAsync]: the probe shells out with a timeout, and that is far too
     * long to hold the UI thread.
     */
    fun refreshSystemPreference() {
        val detected = SystemAppearance.prefersDark() ?: return
        systemKnown = true
        systemDark = detected
    }

    /**
     * [refreshSystemPreference] with the probe moved off the calling thread.
     *
     * Polling is how a desktop theme change reaches us at all: there is no cross-platform change
     * notification we can subscribe to without taking on a D-Bus interface, a dynamic proxy, and
     * the native-image reachability problem that comes with it (see [SystemAppearance]).
     *
     * Called both by the poller and whenever the picker opens. The picker needs it because the
     * poller only runs under [ThemeMode.SYSTEM]: sit in Dark for an hour and [systemDark] is
     * frozen at whatever it was when you left, so the "Match system" row would state a stale
     * preference with total confidence.
     */
    suspend fun refreshSystemPreferenceAsync() {
        val detected = withContext(Dispatchers.IO) { SystemAppearance.prefersDark() } ?: return
        systemKnown = true
        systemDark = detected
    }
}

/**
 * Asks the OS whether the user prefers a dark UI.
 *
 * Every probe is a subprocess rather than a library call, deliberately. The alternative on Linux
 * is talking to the XDG appearance portal over D-Bus, which needs a custom `DBusInterface` and
 * therefore a dynamic-proxy registration that GraalVM's closed-world analysis cannot see — the
 * exact failure mode documented for the file chooser in `SmokeChecks`, where the app degrades in
 * silence. `git` is already run this way on every scan, so the subprocess path is the one already
 * proven to survive the native image.
 *
 * Returns null when nothing could be determined, which callers treat as "leave it as it was"
 * rather than "light".
 */
private object SystemAppearance {
    fun prefersDark(): Boolean? {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("mac") -> macOS()
            os.contains("win") -> windows()
            else -> linux()
        }
    }

    /**
     * The freedesktop appearance setting first (it is what GNOME, KDE and the portal-aware
     * toolkits all agree on), then GNOME's own gsettings key, then KDE's config. Each returns
     * null rather than false when it isn't the right question for this desktop, so the next
     * probe gets its turn.
     */
    private fun linux(): Boolean? {
        // org.freedesktop.appearance color-scheme: 0 = no preference, 1 = prefer dark, 2 = prefer light.
        val portal = run(
            "gdbus", "call", "--session",
            "--dest", "org.freedesktop.portal.Desktop",
            "--object-path", "/org/freedesktop/portal/desktop",
            "--method", "org.freedesktop.portal.Settings.ReadOne",
            "org.freedesktop.appearance", "color-scheme",
        )
        // "(<uint32 1>,)" — the number is all we need, and a "no preference" 0 is not an answer.
        Regex("uint32 (\\d)").find(portal.orEmpty())?.groupValues?.get(1)?.let {
            when (it) {
                "1" -> return true
                "2" -> return false
            }
        }
        run("gsettings", "get", "org.gnome.desktop.interface", "color-scheme")?.let {
            if ("prefer-dark" in it) return true
            if ("prefer-light" in it || "'default'" in it) return false
        }
        // KDE names its scheme rather than stating a preference, and every dark one says so.
        for (tool in listOf("kreadconfig6", "kreadconfig5")) {
            run(tool, "--file", "kdeglobals", "--group", "General", "--key", "ColorScheme")?.let {
                if (it.isNotBlank()) return it.contains("dark", ignoreCase = true)
            }
        }
        return null
    }

    /** `defaults read -g AppleInterfaceStyle` prints "Dark", and *fails* when the theme is light. */
    private fun macOS(): Boolean = run("defaults", "read", "-g", "AppleInterfaceStyle")
        ?.contains("dark", ignoreCase = true) ?: false

    /** AppsUseLightTheme is a REG_DWORD printed as "0x0" (dark) or "0x1" (light). */
    private fun windows(): Boolean? {
        val out = run(
            "reg", "query",
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
            "/v", "AppsUseLightTheme",
        ) ?: return null
        return Regex("0x(\\d+)").find(out)?.groupValues?.get(1)?.let { it.toInt() == 0 }
    }

    /** Run [args]; stdout on success, null on a non-zero exit, a timeout, or a missing binary. */
    private fun run(vararg args: String): String? = try {
        val proc = ProcessBuilder(args.toList())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(2, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            null
        } else if (proc.exitValue() == 0) {
            out.trim()
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}
