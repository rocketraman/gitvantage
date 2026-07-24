// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

import java.io.File

/**
 * "Open in …" launches for the detail panel. All fire-and-forget: the external app is
 * started detached (we never wait on it), and each entry point tries a list of candidate
 * commands so it works across setups, returning false only if none could be started.
 */
object Actions {

    fun openTerminal(path: String): Boolean {
        val term = System.getenv("TERMINAL")?.takeIf { it.isNotBlank() }
        val candidates = buildList {
            if (term != null) add(listOf(term))
            add(listOf("konsole", "--workdir", path))       // KDE
            add(listOf("gnome-terminal", "--working-directory=$path"))
            add(listOf("kgx", "--working-directory=$path"))
            add(listOf("alacritty", "--working-directory", path))
            add(listOf("kitty", "--directory", path))
            add(listOf("xterm"))
        }
        return launchFirst(candidates, File(path))
    }

    fun openFolder(path: String): Boolean = launchFirst(listOf(listOf("xdg-open", path)), File(path))

    /** Open a URL in the default browser. */
    fun openUrl(url: String): Boolean =
        launchFirst(listOf(listOf("xdg-open", url), listOf("open", url)), File(System.getProperty("user.home")))

    fun openIde(path: String): Boolean = launchFirst(
        listOf(listOf("idea", path), listOf("idea.sh", path), listOf("code", path), listOf("codium", path)),
        File(path),
    )

    fun openGitGui(path: String): Boolean = launchFirst(listOf(listOf("git", "gui")), File(path))

    fun openGitButler(path: String): Boolean =
        launchFirst(listOf(listOf("but", "--path", path), listOf("gitbutler-tauri"), listOf("gitbutler")), File(path))

    /** Try each command in [cmds] with cwd [dir]; true on the first that starts. */
    private fun launchFirst(cmds: List<List<String>>, dir: File): Boolean {
        for (cmd in cmds) if (launch(cmd, dir)) return true
        return false
    }

    private fun launch(cmd: List<String>, dir: File): Boolean = try {
        ProcessBuilder(cmd)
            .directory(dir.takeIf { it.isDirectory })
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        true
    } catch (e: Exception) {
        false   // command not found / not launchable → try the next candidate
    }
}
