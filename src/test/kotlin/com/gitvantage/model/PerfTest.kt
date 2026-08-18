// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.model

import de.infix.testBalloon.framework.core.testSuite

/**
 * The counters behind `GITVANTAGE_PERF=1`.
 *
 * Two properties matter and the rest is arithmetic: recording must cost nothing when it's switched
 * off, since these sit inside every `git` spawn and every filesystem event; and the maximum has to
 * survive concurrent recording, because a scan records from every repo's thread at once.
 *
 * The suite runs against whatever the environment says, so it asserts the *relationship* between
 * enabled and recorded rather than assuming either — a test that only passes with the variable set
 * is a test nobody runs.
 */
val PerfCounters by testSuite {

    test("recording is a no-op when disabled, and lands when enabled") {
        Perf.reset()
        Perf.count("probe")
        Perf.time("probe.timed", 1_000_000)
        val lines = Perf.report()

        if (Perf.enabled) {
            assert(lines.any { it.contains("probe") }) { "nothing recorded while enabled: $lines" }
        } else {
            assert(lines.isEmpty()) { "recorded while disabled — this runs inside every git spawn: $lines" }
        }
        Perf.reset()
    }

    test("timed returns the body's value, switched on or off") {
        // The wrapper is on the hot path of every subprocess, so it must be transparent: a value
        // swallowed here would be a git command's output silently lost.
        assert(Perf.timed("probe.value") { 42 } == 42)
        assert(Perf.timed("probe.value") { "out" } == "out")
        Perf.reset()
    }

    test("timed propagates exceptions rather than swallowing them") {
        val thrown = runCatching { Perf.timed<Unit>("probe.throw") { error("boom") } }
        assert(thrown.isFailure) { "an exception was swallowed by the measurement wrapper" }
        Perf.reset()
    }

    test("a stat keeps the largest sample, not the latest") {
        val s = Perf.Stat()
        s.record(5_000_000)
        s.record(1_000_000)   // smaller, and last — it must not become the maximum
        s.record(3_000_000)

        assert(s.count.get() == 3L)
        assert(s.totalNanos.get() == 9_000_000L)
        assert(s.maxNanos == 5_000_000L) { "max was overwritten by a later, smaller sample" }
    }

    test("the maximum survives threads recording at once") {
        // Every repo in a scan records from its own thread. A read-then-write maximum loses samples
        // here; the compare-and-set retry is what this pins.
        val s = Perf.Stat()
        val threads = (1..8).map { t ->
            Thread {
                for (i in 1..500) s.record((t * 1_000L) + i)
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assert(s.count.get() == 4_000L) { "lost records: ${s.count.get()}" }
        assert(s.maxNanos == 8_500L) { "lost the maximum under contention: ${s.maxNanos}" }
    }
}
