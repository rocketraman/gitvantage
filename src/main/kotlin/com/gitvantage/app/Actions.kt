// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.app

import com.gitvantage.git.GitLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * "Open in …" launches for the detail panel. All fire-and-forget: the external app is
 * started detached (we never wait on it), and each entry point tries a list of candidate
 * commands so it works across setups, returning false only if none could be started.
 *
 * Every chain is per-platform, because none of these actions has a portable spelling. What the
 * chains share is that a rung is *resolved* before it is tried (see [Programs]) rather than being
 * tried to find out whether it exists — start-means-success is a Linux-only test, and building
 * macOS and Windows on it is why these did nothing at all there.
 */
object Actions {

    // ---- Terminal -----------------------------------------------------------------------------

    fun openTerminal(path: String): Boolean = launchFirst(terminals(path), File(path)) != null

    /**
     * Where a terminal comes from, per platform.
     *
     * Linux is the list this file has always had, now resolved rather than probed by exec.
     *
     * macOS has no system-wide "default terminal" setting to consult — there is no Launch Services
     * handler for the role — so the order is the useful one: whatever `$TERMINAL` names, then
     * iTerm, then Ghostty, then Terminal.app, which is always installed and so is a real floor.
     * Every rung is `open -a`, which hands the directory to the app and gets a window at that
     * directory; the list is deliberately short, holding only apps whose handling of a directory
     * argument is dependable. A rung that "works" but opens somewhere else is worse than falling
     * through to Terminal.app, which opens in the right place.
     *
     * Windows does have a default-terminal setting, and [winConsole] is how it gets honoured.
     */
    private fun terminals(path: String): List<Candidate> {
        val term = System.getenv("TERMINAL")?.takeIf { it.isNotBlank() }
        return buildList {
            when (Os.current) {
                Os.LINUX -> {
                    if (term != null) add(exe(term))
                    add(exe("konsole", "--workdir", path)) // KDE
                    add(exe("gnome-terminal", "--working-directory=$path"))
                    add(exe("kgx", "--working-directory=$path"))
                    add(exe("alacritty", "--working-directory", path))
                    add(exe("kitty", "--directory", path))
                    add(exe("xterm"))
                }
                Os.MAC -> {
                    if (term != null) add(exe(term))
                    add(macApp("iTerm", path))
                    add(macApp("Ghostty", path))
                    add(macApp("Terminal", path))
                }
                Os.WINDOWS -> {
                    // Windows Terminal first, and by name: it is a GUI application, so unlike the
                    // console shells below it can be started directly and told which directory to
                    // open. `-d` also keeps the user's chosen default profile.
                    add(exe("wt.exe", "-d", path))
                    add(winConsole())
                }
            }
        }
    }

    /**
     * A console shell in a window of its own.
     *
     * It has to go through `cmd`'s `start` builtin. A console program started straight from this
     * process gets no window: `ProcessBuilder` has no way to ask Windows for `CREATE_NEW_CONSOLE`,
     * and this is a GUI process with no console of its own to inherit — so `cmd.exe` would run
     * invisibly, attached to the pipes [launch] hands it, and the user would see nothing happen.
     *
     * `start` is also what makes the user's choice count. Allocating a new console is the moment
     * Windows 11 consults its console-delegation setting and hands the session to whichever
     * terminal was set as the default, so this rung is "the default terminal" rather than merely
     * "cmd" — and degrades to conhost on Windows 10, which is the documented worst case.
     *
     * The directory comes from [launch]'s working directory, which `start` passes to the child;
     * spelling it as an argument instead would mean quoting a path through two levels of parsing.
     */
    private fun winConsole() = Candidate(listOf("cmd.exe", "/c", "start", "", "cmd.exe")) {
        Launch(listOf(Programs.comSpec(), "/c", "start", "", "cmd.exe"))
    }

    // ---- Folder, URL, IDE, git clients --------------------------------------------------------

    fun openFolder(path: String): Boolean = launchFirst(folders(path), File(path)) != null

    /** `explorer.exe` is a GUI application, so it needs no `start` wrapper — and it exits 1 even
     *  when it succeeded, which costs nothing here because nothing waits on it. */
    private fun folders(path: String): List<Candidate> = when (Os.current) {
        Os.LINUX -> listOf(exe("xdg-open", path))
        Os.MAC -> listOf(exe("open", path))
        Os.WINDOWS -> listOf(exe("explorer.exe", path))
    }

    /** Open a URL in the default browser. */
    fun openUrl(url: String): Boolean =
        launchFirst(urls(url), File(System.getProperty("user.home"))) != null

    /**
     * Windows goes through `explorer.exe` rather than the more familiar `cmd /c start <url>`
     * because `start` is a shell builtin and the shell reads `&` as a command separator — every
     * URL with a second query parameter would be cut in half and the tail run as a command.
     * `explorer` is executed directly, with no shell in the way to reinterpret anything.
     */
    private fun urls(url: String): List<Candidate> = when (Os.current) {
        Os.LINUX -> listOf(exe("xdg-open", url))
        Os.MAC -> listOf(exe("open", url))
        Os.WINDOWS -> listOf(
            exe("explorer.exe", url),
            exe("rundll32.exe", "url.dll,FileProtocolHandler", url),
        )
    }

    fun openIde(path: String): Boolean = launchFirst(ides(path), File(path)) != null

    /** The `.app` rungs are for macOS installs done entirely through the GUI — JetBrains Toolbox
     *  and VS Code both leave their `PATH` shims out unless asked, so the editor is plainly there
     *  and no command names it. */
    private fun ides(path: String): List<Candidate> = buildList {
        add(exe("idea", path))
        add(exe("idea.sh", path))
        add(exe("code", path))
        add(exe("codium", path))
        if (Os.current == Os.MAC) {
            add(macApp("IntelliJ IDEA", path))
            add(macApp("Visual Studio Code", path))
        }
    }

    fun openGitGui(path: String): Boolean = launchGitClient(path, gitGuis())

    /**
     * `git gui` is a subcommand, not a program, and that distinction is the whole bug.
     *
     * Resolving `git` says nothing about whether `git gui` exists, because git-gui ships
     * separately from git nearly everywhere — its own Homebrew formula on macOS, its own package
     * on most distributions. So the ordinary machine has git and no git-gui, and there `git gui`
     * starts perfectly well, writes "git: 'gui' is not a git command" to the stream [launch]
     * discards, and exits 1 long after this fire-and-forget launch reported success. A menu item
     * that does nothing, in silence, and even logs itself to the git console as launched.
     *
     * So each git is asked where its subcommands live and only one that actually has git-gui
     * becomes a rung. Every git on the search path, not just the first: launched from Finder, the
     * first git on a Mac is Apple's `/usr/bin/git`, which has no git-gui, and stopping there would
     * report "not installed" on precisely the Macs where someone installed it.
     */
    private fun gitGuis(): List<Candidate> {
        val gits = Programs.locate("git")
        // Nothing to offer, but the chain still needs a rung to name in the git console — the
        // record of a launch that could not happen is the only trace the user gets.
        if (gits.isEmpty()) return listOf(Candidate(listOf("git", "gui")) { null })
        return gits.map { git ->
            Candidate(listOf("git", "gui")) {
                findGitGui(git)?.let { gui ->
                    // Both directories, because finding git-gui is only half of it: the git we are
                    // about to run has to find it too, and it looks on its *own* PATH, not ours.
                    // A `.app` launched from Finder is handed launchd's PATH — /usr/bin and three
                    // more — so a Homebrew git-gui is invisible to the child even though we just
                    // located it. git's own directory goes on as well, so git-gui shells out to
                    // the git it was matched with rather than to whichever one comes first.
                    Launch(listOf(git.absolutePath, "gui"), listOfNotNull(gui.parentFile, git.parentFile))
                }
            }
        }
    }

    /** Where a git-gui has already been found, keyed by the git that will run it. Hits only. */
    private val gitGuis = ConcurrentHashMap<String, File>()

    /**
     * The git-gui [git] would run, or null if it has none.
     *
     * This mirrors how git resolves a subcommand, which is the only definition that matches what
     * happens at the click. git looks in its exec-path *and then falls back to `PATH`* — the
     * fallback being how every third-party `git-foo` works at all — so checking `--exec-path`
     * alone answers the question wrongly for the ordinary macOS install, where Homebrew ships
     * git-gui as its own package and lands it in `/opt/homebrew/bin` while git's exec-path sits
     * off in the Cellar. That reports "not installed" for a git-gui that runs fine in a terminal.
     *
     * The middle rung covers the packagers who instead link git-gui beside git rather than inside
     * git's own keg: for a git at `<prefix>/bin/git`, `<prefix>/libexec/git-core`. Both the literal
     * and the symlink-resolved prefix, since a `bin/git` that is a symlink into a versioned
     * directory has two plausible answers and they cost one `stat` each.
     */
    private fun findGitGui(git: File): File? = gitGuis[git.absolutePath] ?: run {
        val homes = buildList {
            execPathOf(git)?.let { add(File(it)) }
            listOf(git, git.canonicalFile).forEach { g ->
                g.parentFile?.parentFile?.let { add(File(it, "libexec/git-core")) }
            }
        }
        val found = homes.firstNotNullOfOrNull { home ->
            GIT_GUI_NAMES.map { File(home, it) }.firstOrNull { it.isFile }
        } ?: Programs.locate("git-gui").firstOrNull()
        found?.also { gitGuis[git.absolutePath] = it }
    }

    /** Where [git] keeps its subcommands, as git itself reports it. Reads no repository and
     *  touches no network, so it is cheap enough to ask on a click. */
    private fun execPathOf(git: File): String? = runCatching {
        val probe = ProcessBuilder(git.absolutePath, "--exec-path")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        // Read before waiting: the pipe would fill and deadlock a chatty command, and the read
        // ends at the process's own exit anyway, so the wait below is already satisfied.
        val out = probe.inputStream.bufferedReader().use { it.readText() }.trim()
        if (probe.waitFor(1, TimeUnit.SECONDS) && probe.exitValue() == 0) out.ifEmpty { null } else null
    }.getOrNull()

    /** Git for Windows spells the subcommand `git-gui.exe`; everyone else has no extension. */
    private val GIT_GUI_NAMES = listOf("git-gui", "git-gui.exe")

    fun openGitButler(path: String): Boolean = launchGitClient(
        path,
        listOf(exe("but", "--path", path), exe("gitbutler-tauri"), exe("gitbutler")),
    )

    // ---- Machinery ----------------------------------------------------------------------------

    /**
     * One rung of a chain: how it reads, and what it would actually run.
     *
     * [cmd] is the human-facing spelling — `code /repo`, not the absolute path to `code.cmd` with
     * an interpreter in front of it — and is what the git console records. [resolve] answers with
     * the real command, or null when the program isn't installed, which is the question a chain
     * has to be able to ask before it commits to a rung.
     */
    private class Candidate(val cmd: List<String>, val resolve: () -> Launch?)

    /**
     * A resolved rung: the command, and any directories the child must see on its `PATH`.
     *
     * [pathPrefix] is empty for almost everything, because almost everything here is launched by
     * absolute path and needs to look nothing up. It exists for `git gui`, where the program we
     * run is git and the thing that has to be found is a *subcommand* — a lookup performed by the
     * child, against the child's environment, after we have already stopped being able to see it.
     */
    private class Launch(val cmd: List<String>, val pathPrefix: List<File> = emptyList())

    /** A rung naming a program to be found on `PATH`. */
    private fun exe(program: String, vararg args: String) = Candidate(listOf(program) + args) {
        Programs.resolve(program)?.plus(args)?.let(::Launch)
    }

    /**
     * A rung naming a macOS application bundle, opened with the directory as its argument.
     *
     * The guard is the point. `open -a Ghostty` starts, and returns, whether or not Ghostty is
     * installed — the failure is `open`'s own exit code, long after this fire-and-forget launch
     * has reported success — so without a prior Launch Services lookup the first `.app` rung would
     * swallow the whole chain on every Mac.
     */
    private fun macApp(app: String, vararg args: String): Candidate {
        val cmd = listOf("open", "-a", app) + args
        return Candidate(cmd) { Launch(cmd).takeIf { macAppInstalled(app) } }
    }

    /** Applications a probe has already found. Hits only, for the reason [Programs] caches hits
     *  only: an app installed while the dashboard is open should show up on the next click. */
    private val macApps = ConcurrentHashMap.newKeySet<String>()

    /** `open -Ra` resolves an application without launching it, exiting 0 only if it is registered
     *  — which finds it wherever it lives, including `~/Applications` and Setapp, as a search of
     *  `/Applications` would not. Bounded: this runs on the UI thread from a click, and a Launch
     *  Services lookup that has not answered within a second is not going to. */
    private fun macAppInstalled(app: String): Boolean = app in macApps || run {
        runCatching {
            val probe = ProcessBuilder("open", "-Ra", app)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            if (probe.waitFor(1, TimeUnit.SECONDS)) probe.exitValue() == 0 else { probe.destroy(); false }
        }.getOrDefault(false).also { if (it) macApps += app }
    }

    /** Try each rung of [cmds] with cwd [dir]; the first that started, or null if none did.
     *  Returns the command rather than a flag so the git-client launchers can record what ran. */
    private fun launchFirst(cmds: List<Candidate>, dir: File): List<String>? =
        cmds.firstNotNullOfOrNull { candidate ->
            candidate.resolve()?.takeIf { launch(it, dir) }?.cmd
        }

    /** Launch a git client and record it: what the user does inside one is real git mutation, so
     *  the entry is the console's only account of the dashboard state that jumps afterwards.
     *  Names the command that actually started, or the preferred candidate if none would. */
    private fun launchGitClient(path: String, cmds: List<Candidate>): Boolean {
        val dir = File(path)
        val started = launchFirst(cmds, dir)
        GitLog.recordLaunch(dir.name.ifEmpty { path }, started ?: cmds.first().cmd, started != null)
        return started != null
    }

    /**
     * What each "Open in …" action would run right now, or null where nothing resolved.
     *
     * For `--smoke-test`. These launches fail silently by nature — a chain that resolves nothing
     * looks exactly like a button that does nothing — and this is the only way to ask the question
     * on a machine without clicking, which matters most on the two platforms where the answer used
     * to be "nothing, on every rung".
     */
    internal fun resolutions(path: String): List<Pair<String, List<String>?>> = listOf(
        "terminal" to firstResolved(terminals(path)),
        "file manager" to firstResolved(folders(path)),
        "browser" to firstResolved(urls("https://example.com/")),
        "IDE" to firstResolved(ides(path)),
        "git GUI" to firstResolved(gitGuis()),
    )

    private fun firstResolved(cmds: List<Candidate>): List<String>? =
        cmds.firstNotNullOfOrNull { it.resolve()?.cmd }

    /** Launch [what] in [dir] and return true on success or false on failure, e.g. if the command
     *  was not found or is not launchable.
     */
    private fun launch(what: Launch, dir: File): Boolean = runCatching {
        val pb = ProcessBuilder(what.cmd)
            .directory(dir.takeIf { it.isDirectory })
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
        if (what.pathPrefix.isNotEmpty()) {
            val env = pb.environment()
            // Prepended, not replaced: the inherited PATH is still the user's, and the child may
            // need the rest of it. PATH_SEPARATOR rather than a literal, since this runs on
            // Windows too, where it is a semicolon.
            val prefix = what.pathPrefix.map { it.absolutePath }.distinct()
            env["PATH"] = (prefix + env["PATH"].orEmpty())
                .filter { it.isNotBlank() }
                .joinToString(File.pathSeparator)
        }
        pb.start()
    }.isSuccess
}
