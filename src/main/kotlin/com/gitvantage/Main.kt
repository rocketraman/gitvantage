// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.TitleBar

@OptIn(FlowPreview::class)
fun main() = nucleusApplication(backend = NucleusBackend.Tao) {
    val saved = Registry.settings()
    val windowState = rememberWindowState(size = DpSize(saved.windowWidth.dp, saved.windowHeight.dp))
    // Keep the window hidden for one beat so it can be created and sized to the saved [windowState]
    // off-screen; revealing it afterward avoids the visible "opens small, then jumps bigger" flash.
    // Driven from the application scope (not the window content) so it fires even while hidden.
    var windowVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(48)
        windowVisible = true
    }
    NucleusDecoratedWindowTheme(isDark = false) {
        DecoratedWindow(
            onCloseRequest = ::exitApplication,
            state = windowState,
            visible = windowVisible,
            title = "GitVantage",
            icon = painterResource("app-icon.png"),   // taskbar / window icon (X11/Win/macOS; Wayland uses app_id)
        ) {
            val uiScope = rememberCoroutineScope()
            val app = remember { AppState(uiScope) }
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

@Composable
fun GitVantageApp(app: AppState) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Color.White)) {
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
