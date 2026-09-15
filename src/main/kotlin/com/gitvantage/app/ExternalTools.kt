// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gitvantage.model.Settings

/**
 * The user's own answers to "Open in …", for the four launches people actually want to redirect.
 *
 * [Actions] carries a per-platform chain for each of these, and those chains are good defaults but
 * they cannot be right for everyone: a terminal is a matter of taste, an IDE is a matter of which
 * one you bought, and neither is discoverable from the system. This is where someone says so.
 *
 * An override is **rung 0 of the existing chain, not a replacement for it**. It is resolved the same
 * way every other rung is (see [Programs]) and, when it doesn't resolve, the platform defaults run
 * exactly as before. That is the whole safety story of the feature: a mistyped command costs you
 * nothing but the override, where a replacement would leave a button that silently does nothing —
 * and these launches are fire-and-forget, so silence is all the user would get.
 *
 * The four are terminal, file manager, IDE and browser. Not the git GUI or GitButler: those already
 * resolve a specific program that either is or isn't installed, and nobody asks to point "Git Gui"
 * at something that isn't git-gui.
 *
 * **Never repo-local.** These strings are executed. A `.gitvantage` file inside a cloned repository
 * saying which terminal to run would make `git clone` arbitrary code execution; `registry.json` under
 * the user's config directory is the same trust level as a shell rc, and that is the line.
 */
object ExternalTools {

    /**
     * A launch that can be overridden, and how its argument is passed when no placeholder says.
     *
     * [appendsArgument] is the difference between a terminal and everything else, and it is not a
     * detail. `konsole /repo` is not how you open a terminal at a directory — most emulators read a
     * bare path as something to *run* — so a terminal named with no placeholder gets the directory
     * as its working directory and no argument at all, which is what the `$TERMINAL` rung in
     * [Actions] has always done. The other three are the reverse: `nautilus` alone opens wherever it
     * feels like, `idea` alone reopens the last project, and a browser alone opens the home page, so
     * for those the argument has to land on the command line whether or not the user marked a spot.
     */
    enum class Tool(
        val label: String,
        val hint: String,
        val appendsArgument: Boolean,
        internal val read: (Settings) -> String,
        internal val write: (Settings, String) -> Settings,
    ) {
        TERMINAL(
            "Terminal", "e.g. kitty --directory {path} · wezterm start --cwd {path}",
            appendsArgument = false,
            read = { it.terminalCommand }, write = { s, v -> s.copy(terminalCommand = v) },
        ),
        FILE_MANAGER(
            "File manager", "e.g. nautilus {path} · thunar",
            appendsArgument = true,
            read = { it.fileManagerCommand }, write = { s, v -> s.copy(fileManagerCommand = v) },
        ),
        IDE(
            "IDE / editor", "e.g. zed {path} · emacsclient -c",
            appendsArgument = true,
            read = { it.ideCommand }, write = { s, v -> s.copy(ideCommand = v) },
        ),
        BROWSER(
            "Browser", "e.g. firefox --new-tab {url} · chromium",
            appendsArgument = true,
            read = { it.browserCommand }, write = { s, v -> s.copy(browserCommand = v) },
        ),
    }

    /**
     * Where the argument goes, in either spelling.
     *
     * `{url}` is the same slot as `{path}` rather than a second one. The browser field is handed a
     * URL and the other three a directory, so one name has to read wrong somewhere; accepting both
     * everywhere costs one extra `replace` and spares the user who typed the word the field's own
     * hint showed them.
     */
    private val PLACEHOLDERS = listOf("{path}", "{url}")

    /**
     * The longest override kept. Not a limit anyone reaches by typing — it is a bound on what a
     * hand-edited `registry.json` can put in front of a `ProcessBuilder`, in the same spirit as
     * [UiScale.clampPercent]: this file is editable by design, so what comes out of it is validated
     * rather than trusted.
     */
    private const val MAX_LENGTH = 500

    /** The current overrides, keyed by tool. Observable, so the settings fields redraw as typed. */
    private var overrides by mutableStateOf(load(Registry.settings()))

    private fun load(s: Settings): Map<Tool, String> =
        Tool.entries.associateWith { it.read(s).take(MAX_LENGTH) }

    /** What the user typed for [tool] — the settings field's value, verbatim and untrimmed. */
    fun raw(tool: Tool): String = overrides[tool].orEmpty()

    /**
     * Record a new override for [tool].
     *
     * In memory now, on disk later. This is called on every keystroke and [Registry]'s write ends in
     * an fsync, so it goes through `recordSettings` — which keeps the in-memory document
     * authoritative, so a chain built a moment later already sees the new command — and leaves the
     * disk to [flush]. Nothing is lost if it never comes: the window's close handler flushes
     * whatever is still recorded.
     */
    fun set(tool: Tool, command: String) {
        val next = command.take(MAX_LENGTH)
        if (next == overrides[tool]) return
        overrides = overrides + (tool to next)
        Registry.recordSettings { tool.write(it, next) }
    }

    /** Write the recorded overrides out. Called when the settings dialog closes. */
    fun flush() {
        Registry.flush()
    }

    /**
     * Whether [tool]'s override is set but cannot be read as a command — the one thing worth saying
     * back to the user, since everything else about an override is only discovered at the click.
     *
     * In practice this is an unbalanced quote, and it is deliberately not forgiven (see [tokenize]).
     */
    fun malformed(tool: Tool): Boolean =
        raw(tool).isNotBlank() && tokenize(raw(tool)).isEmpty()

    /**
     * The command [tool] should run to open [arg], or null when it has no usable override.
     *
     * Null covers both "nothing set" and "what is set doesn't parse", because they mean the same
     * thing to a chain: skip this rung and use the platform default.
     */
    fun command(tool: Tool, arg: String): List<String>? =
        tokenize(raw(tool)).takeIf { it.isNotEmpty() }?.let { place(it, arg, tool.appendsArgument) }

    /**
     * Put [arg] where the command asked for it, or where it has to go.
     *
     * Every occurrence of a placeholder is replaced, in every token, because a command may legitimately
     * name the path twice — `foo --cwd {path} {path}` — and replacing only the first would produce a
     * command the user can see is wrong but not why.
     *
     * With no placeholder anywhere, [appendWhenAbsent] decides: appended as a final argument, or left
     * off entirely for the terminal, which is handed the directory as its working directory instead.
     */
    fun place(tokens: List<String>, arg: String, appendWhenAbsent: Boolean): List<String> {
        if (tokens.none(::hasPlaceholder)) return if (appendWhenAbsent) tokens + arg else tokens
        return tokens.map { token -> PLACEHOLDERS.fold(token) { acc, p -> acc.replace(p, arg) } }
    }

    private fun hasPlaceholder(token: String) = PLACEHOLDERS.any { it in token }

    /**
     * Split a command line into program and arguments, honouring quotes.
     *
     * Quotes are the *only* grouping mechanism: there is no backslash escape, and there must not be.
     * A backslash is a path separator on Windows, so treating it as an escape would quietly mangle
     * every `C:\Program Files\…` an override could plausibly contain — the exact platform whose paths
     * most need quoting in the first place. `"…"` and `'…'` are equivalent and each is literal inside
     * the other, which is enough to spell any path there is.
     *
     * An unterminated quote yields nothing rather than being closed at the end of the line. Being
     * lenient there would turn a typo into a program name with a space in it, which resolves to
     * nothing anyway — so the only difference is whether the settings field can tell the user, and
     * it can only tell them if this says so.
     *
     * An empty quoted token is a real argument (`--title ""`), so emptiness is tracked separately
     * from the buffer's length.
     */
    fun tokenize(command: String): List<String> {
        val out = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var started = false
        for (c in command) {
            when {
                quote != null -> if (c == quote) quote = null else token.append(c)
                c == '"' || c == '\'' -> { quote = c; started = true }
                c.isWhitespace() -> if (started) { out += token.toString(); token.clear(); started = false }
                else -> { token.append(c); started = true }
            }
        }
        if (quote != null) return emptyList()
        if (started) out += token.toString()
        return out
    }
}
