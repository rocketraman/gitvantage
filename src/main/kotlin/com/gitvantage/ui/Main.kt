// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.rememberWindowState
import com.gitvantage.app.AppState
import com.gitvantage.app.Registry
import com.gitvantage.app.Theme
import com.gitvantage.app.ThemeMode
import com.gitvantage.app.Tokens
import com.gitvantage.app.UiScale
import com.gitvantage.smoke.runSmokeChecks
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.TitleBar
import kotlin.system.exitProcess
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlin.time.Duration.Companion.milliseconds

/**
 * `--smoke-test` runs the subsystem checks in [runSmokeChecks] and exits, without opening a window.
 *
 * It lives in the shipped binary on purpose. The subsystems it exercises — the filesystem watcher's
 * native callback, D-Bus, the XDG portal — are reached reflectively, through a ServiceLoader, or
 * from native code, so GraalVM's closed-world analysis can drop them and the app then degrades in
 * silence rather than failing. Running the checks from a development classpath cannot detect that;
 * only running them inside the image can, which is why this is an app flag and not a test fixture.
 */
@OptIn(FlowPreview::class)
fun main(args: Array<String>) {
    if (args.contains("--smoke-test")) {
        exitProcess(runSmokeChecks())
    }
    runApp()
}

/**
 * Give the composition thread a context class loader, if the platform left it without one.
 *
 * On macOS the Tao event loop has to own process thread 0 (AppKit's), so the native side calls
 * back into the JVM rather than being driven from it — the crash report that prompted this has no
 * Java frame below `TaoApplication$EventDispatcher.onEvent`. A thread that enters the JVM through
 * JNI gets a *null* context class loader, and Compose's resource loader dereferences that
 * unconditionally (`Thread.currentThread().contextClassLoader!!`, Resources.desktop.kt:59 —
 * their own TODO, CMP-6099). So the window icon and the bundled Cantarell/JetBrains Mono faces
 * NPE before the first frame ever paints.
 *
 * Linux and Windows drive the same loop from the JVM's own main thread, and a GraalVM native image
 * never has a null loader — which is why this only ever bit `./gradlew run` on a Mac, and never the
 * shipped binary. Setting it here also covers every thread later spawned from this one, which would
 * otherwise inherit the null, and anything else that reads it (ServiceLoader lookups, SLF4J).
 */
private fun adoptContextClassLoader() {
    val thread = Thread.currentThread()
    if (thread.contextClassLoader == null) {
        thread.contextClassLoader = AppState::class.java.classLoader
    }
}

@OptIn(FlowPreview::class)
private fun runApp() = nucleusApplication(backend = NucleusBackend.Tao) {
    adoptContextClassLoader()
    val saved = Registry.settings()
    // Before the first frame, so the window is painted in the right theme rather than flashing
    // light and correcting itself.
    Theme.load()
    // Same reasoning: a zoom applied after the first frame is a visible re-layout.
    UiScale.load()
    val windowState = rememberWindowState(size = DpSize(saved.windowWidth.dp, saved.windowHeight.dp))
    // Built here rather than inside the window so the close handler below — which is a parameter of
    // [DecoratedWindow], evaluated outside its content — can reach it. Same composition either way,
    // so [uiScope] is still the UI-dispatched scope AppState requires.
    val uiScope = rememberCoroutineScope()
    val app = remember { AppState(uiScope) }
    // Keep the window hidden for one beat so it can be created and sized to the saved [windowState]
    // off-screen; revealing it afterward avoids the visible "opens small, then jumps bigger" flash.
    // Driven from the application scope (not the window content) so it fires even while hidden.
    var windowVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(48.milliseconds)
        windowVisible = true
    }
    // Hands the title bar (which Nucleus draws, and which supplies the native window controls)
    // the same answer the app content uses, so the two halves of the window never disagree.
    NucleusDecoratedWindowTheme(isDark = Theme.isDark) {
        DecoratedWindow(
            // Get any coalesced registry write to disk first: closing cancels the composition and
            // with it the scope the debounce timer is waiting on, so a note typed in the last
            // half-second would otherwise never be written. See AppState.flushPendingWrites.
            onCloseRequest = { app.flushPendingWrites(); exitApplication() },
            state = windowState,
            visible = windowVisible,
            title = "GitVantage",
            icon = painterResource("app-icon.png"),   // taskbar / window icon (X11/Win/macOS; Wayland uses app_id)
            onPreviewKeyEvent = ::zoomShortcut,
        ) {
            // Everything the window draws — the Nucleus-drawn title bar included — sizes itself
            // against one density. Wrapping only the app content would let the two halves
            // disagree at any zoom other than 100%.
            UiScale.Provide {
                // Remember the window size across sessions (debounced so resizes don't thrash the file).
                LaunchedEffect(Unit) {
                    snapshotFlow { windowState.size }.debounce(600).collect {
                        app.saveWindowSize(it.width.value.toInt(), it.height.value.toInt())
                    }
                }
                // Adwaita-style header: app icon + title + repo count live in the real
                // window title bar (which supplies the native GNOME window controls).
                TitleBar { _ ->
                    Row(
                        Modifier.align(Alignment.Start).padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Image(
                            painter = painterResource("app-icon.png"),
                            contentDescription = "GitVantage",
                            modifier = Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)),
                        )
                        Txt("GitVantage", 14.sp, Tokens.text, FontWeight.Bold)
                        Txt("— ${app.counts()["all"]} repositories", 12.sp, Tokens.muted2)
                    }
                }
                GitVantageApp(app)
            }
        }
    }
}

/**
 * Ctrl+Shift+= / Ctrl+Shift+- step the zoom, so the size of the UI can be corrected without first
 * finding the Appearance dialog — which is the awkward case, since the reason to reach for zoom at
 * all is usually that the UI is too small to comfortably read the dialog's own contents.
 *
 * A window-level *preview* handler, deliberately: it is the only placement that answers everywhere,
 * including while a modal holds focus and before anything has been clicked at all (nothing in the
 * window is focused when it opens, so a handler further in would simply not be reached). Nothing
 * else in the app claims this chord, so previewing it steals no keystroke from a focused field.
 *
 * Three keys map to "in" because which one arrives is the platform's business, not ours. Under Tao
 * on Linux a shifted `=` is delivered as [Key.Equals] — the physical key, with Shift reported as a
 * modifier — which is what this was verified against; a path that reports the `+` the key *produces*
 * instead would arrive as [Key.Plus], and the numpad's own `+` is the same request by a third key.
 * Accepting all three costs nothing, and none of them means anything else in this app. Meta
 * alongside Ctrl for macOS, the same pairing [onTap] already uses for modifier-clicks.
 */
private fun zoomShortcut(ev: KeyEvent): Boolean {
    if (ev.type != KeyEventType.KeyDown) return false
    if (!(ev.isCtrlPressed || ev.isMetaPressed) || !ev.isShiftPressed) return false
    return when (ev.key) {
        Key.Equals, Key.Plus, Key.NumPadAdd -> { UiScale.zoomIn(); true }
        Key.Minus, Key.NumPadSubtract -> { UiScale.zoomOut(); true }
        else -> false
    }
}

@Composable
fun GitVantageApp(app: AppState) {
    // Follow the desktop while "Match system" is chosen. Polled rather than subscribed: there is
    // no change notification we can take without a D-Bus interface and the native-image
    // reachability risk that comes with it (see SystemAppearance), and one short-lived subprocess
    // a minute is nothing beside the periodic fetch already running across every tracked repo.
    // Keyed on the mode, so choosing "Match system" restarts this and reads the desktop at once
    // rather than after the first delay.
    LaunchedEffect(Theme.mode) {
        while (Theme.mode == ThemeMode.SYSTEM) {
            Theme.refreshSystemPreferenceAsync()
            delay(60_000.milliseconds)
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Tokens.surface)) {
            Toolbar(app)
            StatusBar(app)
            if (app.bulkCount > 0) BulkActionBar(app)   // appears while repos are checked
            TagBar(app)
            Row(Modifier.fillMaxWidth().weight(1f)) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    RepoListBody(app)
                }
                app.selected()?.let { DetailPanel(app, it) }
            }
            // Git console docked along the bottom (stays open while you work).
            if (app.consoleOpen) GitConsolePanel(app)
        }
        // "Add repo" step 2: the multi-select chooser modal, drawn over everything.
        if (app.chooserOpen) RepoChooserOverlay(app)
        // Commit-list (log) overlay, and the diff overlay on top of it — so opening a commit's diff
        // from a log row layers over the list and closing it returns to the list.
        if (app.logOpen) LogView(app)
        if (app.diffOpen) DiffView(app)
        // Tag / Snooze / Remind / Commit / Confirm modals, and the transient op toast.
        app.popup?.let { PopupHost(app, it) }
        app.opStatus?.let { Toast(app, it) }
    }
}
