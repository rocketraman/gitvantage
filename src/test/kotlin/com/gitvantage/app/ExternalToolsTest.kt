// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.app

import de.infix.testBalloon.framework.core.testSuite

/**
 * How a user's "Open in …" command is read: the tokenizer, and where the path lands in it.
 *
 * Both are pure, and they are the whole of what can go wrong silently. Everything downstream of
 * them is [Programs], which has a suite of its own, or a `ProcessBuilder` — so a command that is
 * split wrongly here is launched wrongly, and a fire-and-forget launch has no way to say so.
 */
val ExternalToolsParsing by testSuite {

    val terminal = ExternalTools.Tool.TERMINAL
    val browser = ExternalTools.Tool.BROWSER

    test("a plain command splits on whitespace") {
        assert(ExternalTools.tokenize("kitty --directory {path}") == listOf("kitty", "--directory", "{path}"))
        // Runs of whitespace, and leading and trailing whitespace, are all just separators — which
        // is what lets the settings field store what was typed rather than a trimmed copy of it.
        assert(ExternalTools.tokenize("  code   -n  {path} ") == listOf("code", "-n", "{path}"))
        assert(ExternalTools.tokenize("").isEmpty())
        assert(ExternalTools.tokenize("   ").isEmpty())
    }

    test("quotes group a path containing spaces") {
        // The case that makes a bare command string insufficient on a Mac.
        val cmd = "\"/Applications/My Terminal.app/Contents/MacOS/term\" -d {path}"
        assert(
            ExternalTools.tokenize(cmd) == listOf(
                "/Applications/My Terminal.app/Contents/MacOS/term", "-d", "{path}",
            ),
        )
        // Single quotes are the same thing, and each kind is literal inside the other — enough to
        // spell any path there is without an escape character.
        assert(ExternalTools.tokenize("'/opt/my term/bin/t' -x") == listOf("/opt/my term/bin/t", "-x"))
        assert(ExternalTools.tokenize("""t --title "it's here"""") == listOf("t", "--title", "it's here"))
    }

    test("a backslash is a path separator, never an escape") {
        // Windows is the platform whose paths most need quoting, so a backslash escape would mangle
        // exactly the commands this feature exists to accept.
        assert(
            ExternalTools.tokenize("\"C:\\Program Files\\WezTerm\\wezterm.exe\" start --cwd {path}") ==
                listOf("C:\\Program Files\\WezTerm\\wezterm.exe", "start", "--cwd", "{path}"),
        )
    }

    test("a quoted empty string is a real argument") {
        assert(ExternalTools.tokenize("""t --title "" -e sh""") == listOf("t", "--title", "", "-e", "sh"))
    }

    test("an unterminated quote yields nothing, so the override is ignored") {
        // Not closed at end-of-line on purpose. Being lenient would turn the typo into a program
        // name with a space in it, which resolves to nothing anyway — the only difference is
        // whether the settings field can tell the user, and it can only tell them if this says so.
        assert(ExternalTools.tokenize("\"/Applications/My Term -d {path}").isEmpty())
        assert(ExternalTools.tokenize("t --title 'unclosed").isEmpty())
    }

    test("{path} is substituted wherever it appears, in every token") {
        val tokens = listOf("wt", "-d", "{path}")
        assert(ExternalTools.place(tokens, "/repo", terminal.appendsArgument) == listOf("wt", "-d", "/repo"))
        // Inside a larger token, not only as a whole one.
        assert(
            ExternalTools.place(listOf("gnome-terminal", "--working-directory={path}"), "/repo", false) ==
                listOf("gnome-terminal", "--working-directory=/repo"),
        )
        // Twice, because a command may legitimately name it twice and replacing only the first
        // produces something the user can see is wrong but not why.
        assert(
            ExternalTools.place(listOf("t", "--cwd", "{path}", "{path}"), "/repo", false) ==
                listOf("t", "--cwd", "/repo", "/repo"),
        )
    }

    test("{url} is the same slot as {path}") {
        // One name has to read wrong somewhere — the browser field is handed a URL and the other
        // three a directory — so both spellings work everywhere.
        assert(
            ExternalTools.place(listOf("firefox", "--new-tab", "{url}"), "https://x/", true) ==
                listOf("firefox", "--new-tab", "https://x/"),
        )
        assert(
            ExternalTools.place(listOf("firefox", "{path}"), "https://x/", true) ==
                listOf("firefox", "https://x/"),
        )
    }

    test("with no placeholder the terminal gets no argument and everything else gets one") {
        // A terminal is started *in* the directory: `konsole /repo` is not how you open a terminal
        // at a path, and most emulators would read the bare path as something to run.
        assert(!terminal.appendsArgument)
        assert(ExternalTools.place(listOf("konsole"), "/repo", terminal.appendsArgument) == listOf("konsole"))
        // The other three are the reverse — a browser with no URL opens the home page.
        assert(browser.appendsArgument)
        assert(
            ExternalTools.place(listOf("chromium"), "https://x/", browser.appendsArgument) ==
                listOf("chromium", "https://x/"),
        )
        // And a placeholder always wins over appending, so the argument is never passed twice.
        assert(
            ExternalTools.place(listOf("chromium", "--app={url}"), "https://x/", browser.appendsArgument) ==
                listOf("chromium", "--app=https://x/"),
        )
    }

    test("every tool reads and writes its own field, and nothing else's") {
        // The enum carries the accessors, so a copy-paste slip between four near-identical entries
        // would silently make two tools share one setting.
        val tools = ExternalTools.Tool.entries
        tools.forEach { tool ->
            val written = tool.write(com.gitvantage.model.Settings(), "mark-${tool.name}")
            assert(tool.read(written) == "mark-${tool.name}") { "$tool does not read back its own write" }
            tools.filter { it != tool }.forEach { other ->
                assert(other.read(written).isEmpty()) { "$tool wrote into $other's field" }
            }
        }
    }
}
