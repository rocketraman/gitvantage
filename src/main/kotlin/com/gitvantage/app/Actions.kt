// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.app

import com.gitvantage.git.GitLog
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
        return launchFirst(candidates, File(path)) != null
    }

    fun openFolder(path: String): Boolean = launchFirst(listOf(listOf("xdg-open", path)), File(path)) != null

    /** Open a URL in the default browser. */
    fun openUrl(url: String): Boolean =
        launchFirst(listOf(listOf("xdg-open", url), listOf("open", url)), File(System.getProperty("user.home"))) != null

    fun openIde(path: String): Boolean = launchFirst(
        listOf(listOf("idea", path), listOf("idea.sh", path), listOf("code", path), listOf("codium", path)),
        File(path),
    ) != null

    fun openGitGui(path: String): Boolean = launchGitClient(path, listOf(listOf("git", "gui")))

    fun openGitButler(path: String): Boolean = launchGitClient(
        path,
        listOf(listOf("but", "--path", path), listOf("gitbutler-tauri"), listOf("gitbutler")),
    )

    /** Try each command in [cmds] with cwd [dir]; the first that starts, or null if none did.
     *  Returns the command rather than a flag so the git-client launchers can record what ran. */
    private fun launchFirst(cmds: List<List<String>>, dir: File): List<String>? =
        cmds.firstOrNull { launch(it, dir) }

    /** Launch a git client and record it: what the user does inside one is real git mutation, so
     *  the entry is the console's only account of the dashboard state that jumps afterwards.
     *  Names the command that actually started, or the preferred candidate if none would. */
    private fun launchGitClient(path: String, cmds: List<List<String>>): Boolean {
        val dir = File(path)
        val started = launchFirst(cmds, dir)
        GitLog.recordLaunch(dir.name.ifEmpty { path }, started ?: cmds.first(), started != null)
        return started != null
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
