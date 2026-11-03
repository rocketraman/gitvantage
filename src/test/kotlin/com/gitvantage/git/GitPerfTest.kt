// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git

import com.gitvantage.model.Perf
import de.infix.testBalloon.framework.core.testSuite

/** What [Git] records into [Perf] for each spawn, beyond its count and time. */
val GitPerfCounters by testSuite {
    testFixture { Sandbox() } asContextForEach {

        test("each subcommand's output is counted in characters, under its own name") {
            val work = repo()
            Perf.reset()
            Perf.enable(true)
            try {
                val out = Git.read(work, "log", "--format=%H%n%s")
                Git.read(work, "rev-parse", "HEAD")
                Git.read(work, "log", "-1", "--format=%H")

                val lines = Perf.report()
                val logChars = lines.single { it.trim().startsWith("git.log.chars ") }
                    .substringAfter("n=").trim().toLong()
                // Both log calls land in one counter, and rev-parse in its own.
                assert(logChars == out.length + 41L) { "got $logChars; lines: $lines" }
                assert(lines.any { it.trim().startsWith("git.rev-parse.chars ") }) { "lines: $lines" }
            } finally {
                Perf.enable(false)
                Perf.reset()
            }
        }
    }
}
