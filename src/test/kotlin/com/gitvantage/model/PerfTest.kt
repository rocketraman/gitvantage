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
 * Recording is switched on and off by the suite itself, now that it can be, and left as the
 * environment had it afterwards. The one thing that cannot be asserted from here is "off" when
 * `GITVANTAGE_PERF=1` is exported, since that deliberately cannot be undone — so the disabled case
 * is skipped there rather than failed.
 */
val PerfCounters by testSuite {

    test("recording is a no-op when disabled, and lands when enabled") {
        Perf.reset()
        Perf.enable(false)
        if (!Perf.forcedOn) {
            Perf.count("probe")
            Perf.time("probe.timed", 1_000_000)
            Perf.concurrent("probe.level") { }
            val lines = Perf.report()
            assert(lines.isEmpty()) { "recorded while disabled — this runs inside every git spawn: $lines" }
        }

        Perf.enable(true)
        Perf.count("probe")
        Perf.time("probe.timed", 1_000_000)
        val lines = Perf.report()
        assert(lines.any { it.contains("probe.timed") }) { "nothing recorded while enabled: $lines" }

        Perf.enable(false)
        Perf.reset()
    }

    test("what was recorded can still be read after collection is switched off") {
        // The order people actually do it in: notice the slowness, switch off, then copy.
        Perf.reset()
        Perf.enable(true)
        Perf.time("probe.kept", 2_000_000)
        Perf.enable(false)

        assert(Perf.report().any { it.contains("probe.kept") })
        Perf.reset()
    }

    test("how long collection has run is known only while it is on") {
        Perf.enable(false)
        if (!Perf.forcedOn) assert(Perf.collectingSeconds() == null)
        Perf.enable(true)
        assert(Perf.collectingSeconds() == 0L)
        Perf.enable(false)
    }

    test("a marked report separates the recent interval from the running total") {
        Perf.reset()
        Perf.enable(true)
        Perf.time("probe.interval", 50_000_000)
        Perf.time("probe.interval", 10_000_000)
        Perf.report(markInterval = true)

        // One more, smaller, after the mark: the total keeps the 50ms maximum, the interval must not.
        Perf.time("probe.interval", 4_000_000)
        val line = Perf.report(markInterval = true).single { it.contains("probe.interval") }
        val (overall, recent) = line.split("|").map { it.trim().replace(Regex("\\s+"), " ") }

        assert(overall.contains("n=3 ") && overall.contains("max= 50.0ms"))
        assert(recent == "recent n=1 total= 4.0ms max= 4.0ms")

        Perf.enable(false)
        Perf.reset()
    }

    test("reading the report without marking leaves the interval where it was") {
        // The clipboard report must not move the baseline the periodic dump measures from.
        Perf.reset()
        Perf.enable(true)
        Perf.time("probe.unmarked", 1_000_000)
        Perf.report()
        Perf.report()
        val line = Perf.report(markInterval = true).single { it.contains("probe.unmarked") }

        assert(line.substringAfter("|").contains("n=1 "))
        Perf.enable(false)
        Perf.reset()
    }

    test("a level remembers the most that ran at once, and returns to zero") {
        Perf.reset()
        Perf.enable(true)
        val inside = java.util.concurrent.CountDownLatch(4)
        val release = java.util.concurrent.CountDownLatch(1)
        val threads = (1..4).map {
            Thread { Perf.concurrent("probe.level") { inside.countDown(); release.await() } }.apply { start() }
        }
        inside.await()
        release.countDown()
        threads.forEach { it.join() }

        val line = Perf.report().single { it.contains("probe.level") }.trim().replace(Regex("\\s+"), " ")
        assert(line == "probe.level peak=4 now=0")
        Perf.enable(false)
        Perf.reset()
    }

    test("a level is not left raised when collection is switched off mid-flight") {
        // Enter with recording on, leave with it off: skipping the leave would strand the level at
        // one for the rest of the session, and every later reading would be off by it.
        Perf.reset()
        Perf.enable(true)
        Perf.concurrent("probe.toggle") { Perf.enable(false) }

        if (!Perf.forcedOn) assert(Perf.report().single { it.contains("probe.toggle") }.trim().endsWith("now=0"))
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
        s.record(1_000_000) // smaller, and last — it must not become the maximum
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
