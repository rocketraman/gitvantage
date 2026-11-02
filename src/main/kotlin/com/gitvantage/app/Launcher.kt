// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.app

/**
 * The programs "Open in ▾" can start for a folder, one of which is pinned as the click on the
 * control's main half.
 *
 * Terminal is pinned out of the box: it's the one reached often enough to have had a link of its
 * own before the menu absorbed it. But which launcher is "the one you use" depends on whether you
 * live in a terminal or an IDE, so the choice is the user's, and it is app-wide — the toolbar and
 * every worktree card name the same program, rather than each remembering its own.
 *
 * [id] is what `registry.json` stores, so the enum can be renamed without moving anyone's pin.
 */
enum class Launcher(val id: String, val label: String) {
    TERMINAL("terminal", "Terminal"),
    IDE("ide", "IDE"),
    FOLDER("folder", "Folder"),
    GITBUTLER("gitbutler", "GitButler"),
    GIT_GUI("gitgui", "Git Gui"),
    ;

    companion object {
        /** An unknown or hand-mangled id falls back to the default rather than failing to load. */
        fun fromId(id: String): Launcher = entries.firstOrNull { it.id == id } ?: TERMINAL
    }
}
