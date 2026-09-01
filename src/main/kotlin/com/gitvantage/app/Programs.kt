// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.app

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds the external programs [Actions] launches, and says how to run them.
 *
 * [Actions] used to answer "is this installed?" by trying to start it and seeing whether the exec
 * failed. That is only an answer on Linux. `open -a iTerm` and `cmd /c start` start perfectly well
 * whether or not the thing they front exists, so a chain built on start-means-success stops at its
 * first rung on macOS and Windows and silently does nothing at all. Resolving the program up front
 * is what lets a fallback chain fall back.
 */
internal object Programs {

    /**
     * Directories searched in addition to `PATH`.
     *
     * A macOS `.app` launched from Finder or the Dock inherits launchd's `PATH`, which is
     * `/usr/bin:/bin:/usr/sbin:/sbin` and nothing else — not the `PATH` the user's shell builds.
     * Every CLI shim someone actually installed lives outside it: Homebrew in `/opt/homebrew/bin`
     * or `/usr/local/bin`, MacPorts in `/opt/local/bin`, JetBrains Toolbox and GitButler in
     * `~/.local/bin`. Scanning `PATH` alone would find none of them and report a bare Mac.
     *
     * Harmless on Linux, where these are the same directories a session `PATH` already lists.
     */
    private val extraDirs: List<String> =
        if (Os.current == Os.WINDOWS) emptyList()
        else listOf(
            "/usr/local/bin", "/opt/homebrew/bin", "/opt/local/bin",
            System.getProperty("user.home") + "/.local/bin",
        )

    private val searchDirs: List<File> by lazy {
        (System.getenv("PATH").orEmpty().split(File.pathSeparatorChar) + extraDirs)
            .filter { it.isNotBlank() }
            .distinct()
            .map(::File)
    }

    /**
     * The extensions a bare name may carry, in the order Windows tries them; a single empty string
     * everywhere else. The empty string is appended on Windows too, so a candidate already spelled
     * with its extension (`wt.exe`) still resolves once every real extension has missed.
     */
    private val searchExts: List<String> by lazy {
        if (Os.current != Os.WINDOWS) listOf("")
        else (System.getenv("PATHEXT") ?: ".COM;.EXE;.BAT;.CMD")
            .split(';').filter { it.isNotBlank() } + ""
    }

    /**
     * Resolved invocations, keyed by the name asked for. Hits only: a program that was found is
     * not going to move, but one that was missing may well be installed while the app is running,
     * and remembering that it was absent would mean "Open in IDE" stayed dead until a restart. A
     * repeated miss costs a directory scan, which is a few stats.
     */
    private val hits = ConcurrentHashMap<String, List<String>>()

    /**
     * How to run [program], or null if it isn't installed. The result is a complete command
     * prefix: usually the resolved absolute path, but see [invocation] for the Windows exception.
     */
    fun resolve(program: String): List<String>? =
        hits[program] ?: find(program, searchDirs, searchExts)
            ?.let { invocation(it, Os.current) }
            ?.also { hits[program] = it }

    /**
     * *Every* executable named [program] on the real search path, in search order — where
     * [resolve] answers "which one runs", this answers "which ones are there".
     *
     * The distinction only matters when the first hit is not necessarily the right one. Asking a
     * git for `git gui` is the case that forced it: on a Mac the first git is Apple's
     * `/usr/bin/git`, which carries no git-gui, while the Homebrew git that does is further down.
     * Taking the first and concluding "not installed" would be wrong on exactly the machines where
     * someone went and installed it.
     *
     * Uncached, unlike [resolve]: the callers scan the results, so a stale list is worse than a
     * few stats.
     */
    fun locate(program: String): List<File> = findAll(program, searchDirs, searchExts)

    /**
     * The first executable named [program] under [dirs], trying each of [exts] in turn.
     *
     * [dirs] is the outer loop and [exts] the inner one, which is the order Windows itself
     * searches: an earlier directory's `foo.cmd` beats a later directory's `foo.exe`.
     */
    fun find(program: String, dirs: List<File>, exts: List<String>): File? =
        findAll(program, dirs, exts).firstOrNull()

    /**
     * As [find], but every match rather than the first.
     *
     * A name that already carries a separator is a location rather than a name, so it is tested
     * exactly as given — that is how a `$TERMINAL` set to an absolute path resolves — and there is
     * then at most one answer to give.
     */
    fun findAll(program: String, dirs: List<File>, exts: List<String>): List<File> {
        if (program.contains('/') || program.contains('\\')) {
            return listOfNotNull(File(program).takeIf(::executable))
        }
        return dirs.flatMap { dir ->
            exts.mapNotNull { ext -> File(dir, program + ext).takeIf(::executable) }
        }
    }

    /**
     * `canExecute()` means nothing on Windows — it reports the read-only attribute, not an execute
     * bit, so it is true for every readable file and would reject nothing. There, landing on a
     * `PATHEXT` name is the whole test. File *size* deliberately isn't part of it: a Windows
     * app-execution alias — which is how `wt.exe` appears on `PATH` — is a zero-byte reparse
     * point, and requiring a non-empty file would hide Windows Terminal on every machine.
     */
    private fun executable(f: File): Boolean = f.isFile && (Os.current == Os.WINDOWS || f.canExecute())

    /**
     * Windows cannot execute a batch file. `CreateProcess` starts real images only, so `code.cmd`
     * — which is how VS Code, and nearly every Node-based CLI, lands on `PATH` — has to be handed
     * to the interpreter instead. This is why "Open in IDE" did nothing on Windows even though
     * `code` was plainly installed: the exec failed, and the chain read that as "not installed".
     */
    fun invocation(f: File, os: Os): List<String> {
        val path = f.absolutePath
        val isBatch = os == Os.WINDOWS && path.substringAfterLast('.', "").lowercase() in BATCH_EXTS
        return if (isBatch) listOf(comSpec(), "/c", path) else listOf(path)
    }

    private val BATCH_EXTS = setOf("cmd", "bat")

    /** The command interpreter, from the environment rather than assumed — a Windows install can
     *  sit somewhere other than `C:\Windows\System32`, and `ComSpec` is where it says so. */
    fun comSpec(): String = System.getenv("ComSpec")?.takeIf { it.isNotBlank() } ?: "cmd.exe"
}
