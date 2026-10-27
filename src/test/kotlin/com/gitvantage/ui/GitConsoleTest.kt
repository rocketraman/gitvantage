// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.ui

import de.infix.testBalloon.framework.core.testSuite

/** The console's rendering of git's coloured output. */
val GitConsoleOutput by testSuite {

    test("git's colour escapes are parsed, not printed") {
        // As `git merge` prints a diffstat with color.ui=always — including the bare `ESC[m` reset.
        val line = " a.txt | 3 \u001B[1;32m++\u001B[m\u001B[1;31m-\u001B[m"

        val text = colorizeOutput(line).text

        assert(text == " a.txt | 3 ++-") { "escapes left in the output: ${text.replace("\u001B", "ESC")}" }
    }
}
