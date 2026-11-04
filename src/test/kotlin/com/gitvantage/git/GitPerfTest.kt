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

/**
 * A git that never finishes is killed when its time is up, with whatever it started.
 *
 * The timeout used to be checked only after stdout reached EOF, which a hung git never gets to, so
 * a stalled `fetch` ran for hours. The alias runs `sleep` under a shell: a grandchild holding git's
 * stdout, the way a fetch's `ssh` does, so killing git alone would still leave the read waiting.
 */
val GitTimeout by testSuite {
    testFixture { Sandbox() } asContextForEach {

        test("a hung git and its children are killed at the timeout") {
            val work = repo()
            val started = System.nanoTime()
            val out = Git.readOrNull(work, "-c", "alias.hang=!sleep 60", "hang", timeoutSeconds = 1)
            val tookMs = (System.nanoTime() - started) / 1_000_000

            assert(out == null) { "a timed-out git read as success: '$out'" }
            assert(tookMs < 10_000) { "the 1s timeout took ${tookMs}ms to fire" }
        }

        test("a git that finishes in time is unaffected") {
            val work = repo()
            assert(Git.readOrNull(work, "rev-parse", "--is-inside-work-tree", timeoutSeconds = 5)?.trim() == "true")
        }
    }
}
