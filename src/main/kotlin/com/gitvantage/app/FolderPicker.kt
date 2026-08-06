// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.app

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import java.io.File

/**
 * Parent-folder picker for "Add repo", backed by FileKit's cross-platform native
 * directory dialog. FileKit picks the native chooser per OS (XDG Desktop Portal on
 * Linux, NSOpenPanel on macOS, the Win32 folder dialog on Windows), so we don't have
 * to shell out to per-OS binaries (the old zenity/kdialog/Swing trilemma) — and the
 * portal-hosted dialog keeps the app window's z-order.
 *
 * FileKit has no *multi*-directory mode, so "Add repo" is a two-step flow: the user
 * picks one parent folder here, then [AppState.openChooserFor] lists the git repos
 * discovered directly beneath it and lets the user multi-select which ones to track.
 *
 * [openDirectoryPicker] is `suspend` and dispatches to IO internally; it blocks the
 * caller's coroutine (not the UI thread) until the user chooses or cancels.
 */
object FolderPicker {
    /** Show a single-directory picker at [startDir]; null if the user cancels. */
    suspend fun pickParent(startDir: String): String? {
        val start = File(startDir).takeIf { it.isDirectory }?.let { PlatformFile(it.absolutePath) }
        val picked: PlatformFile? = FileKit.openDirectoryPicker(
            directory = start,
            dialogSettings = FileKitDialogSettings.createDefault(),
        )
        return picked?.file?.absolutePath
    }
}
