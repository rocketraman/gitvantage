// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.app

import de.infix.testBalloon.framework.core.testSuite

/** The figures the diagnostics report adds beyond the counters: allocation, resident memory, other threads. */
val DiagnosticsFigures by testSuite {

    // ---- allocation ----

    test("threads are filed by what they do, and the UI thread by identity") {
        assert(AllocationMeter.groupOf("main", isUi = true) == AllocationMeter.Group.UI)
        assert(AllocationMeter.groupOf("DefaultDispatcher-worker-7", isUi = false) == AllocationMeter.Group.WORKERS)
        assert(AllocationMeter.groupOf("notify-rs fsevents loop", isUi = false) == AllocationMeter.Group.WATCHER)
        // The watchdog's name contains "watch" and must not be mistaken for the filesystem watcher.
        assert(AllocationMeter.groupOf("gitvantage-stall-watchdog", isUi = false) == AllocationMeter.Group.OTHER)
    }

    test("the interval rate reports what this thread allocated, and attributes it") {
        AllocationMeter.start()
        val sink = ArrayList<ByteArray>()
        repeat(64) { sink += ByteArray(1024 * 1024) } // 64MB, held so it can't be optimised away
        val line = AllocationMeter.interval()
        assert(sink.size == 64)
        assert(line.startsWith("alloc ") && "MB/s" in line) { "got $line" }
        assert("%)" in line) { "no breakdown: $line" }
    }

    test("the running total does not move the interval's baseline") {
        AllocationMeter.start()
        val held = ByteArray(32 * 1024 * 1024)
        assert(AllocationMeter.total().startsWith("allocated "))
        val line = AllocationMeter.interval()
        // The 32MB still counts towards the interval, though total() sampled in between.
        val seconds = line.substringAfter("over ").substringBefore("s").toDouble().coerceAtLeast(1.0)
        val rate = line.substringAfter("alloc ").substringBefore("MB/s").toDouble()
        assert(rate * seconds >= 32 || held.isEmpty()) { "got $line" }
    }

    // ---- resident memory ----

    test("a /proc VmRSS line is read in kB") {
        assert(Diagnostics.parseVmRssKb("VmRSS:\t  123456 kB") == 123456L)
        assert(Diagnostics.parseVmRssKb("VmRSS: garbage") == null)
    }

    test("resident memory is reported on Linux and macOS") {
        val os = System.getProperty("os.name")
        val rss = Diagnostics.residentMb()
        if (os.startsWith("Linux") || os.startsWith("Mac")) assert(rss != null && rss > 0) { "got $rss on $os" }
        assert(Diagnostics.vitals().any { it.startsWith("rss ") } == (rss != null))
    }

    // ---- other threads at a stall ----

    fun frame(cls: String, method: String) = StackTraceElement(cls, method, null, -1)
    val forking = listOf(
        frame("java.lang.ProcessImpl", "forkAndExec"),
        frame("com.gitvantage.git.Git", "spawnNow"),
    )
    val parked =
        listOf(frame("jdk.internal.misc.Unsafe", "park"), frame("java.util.concurrent.locks.LockSupport", "park"))

    test("identical stacks are folded into one entry with a count, the most common first") {
        val threads = (1..5).map { ThreadSummary.Snap("DefaultDispatcher-worker-$it", Thread.State.RUNNABLE, forking) } +
            ThreadSummary.Snap("other", Thread.State.BLOCKED, listOf(frame("x.Y", "z")))
        val lines = ThreadSummary.lines(threads)
        assert(lines[0].startsWith("  5× DefaultDispatcher-worker-1, DefaultDispatcher-worker-2, DefaultDispatcher-worker-3, …")) {
            "got ${lines[0]}"
        }
        assert(lines.count { "forkAndExec" in it } == 1) { "the shared stack was printed more than once: $lines" }
        assert(lines.any { it.startsWith("  1× other (BLOCKED)") })
    }

    test("idle pool threads are counted, not printed, but app threads waiting on git are kept") {
        val waitingOnGit =
            listOf(frame("java.lang.ProcessImpl", "waitFor"), frame("com.gitvantage.git.Git", "spawnNow"))
        val threads = listOf(
            ThreadSummary.Snap("DefaultDispatcher-worker-1", Thread.State.TIMED_WAITING, parked),
            ThreadSummary.Snap("DefaultDispatcher-worker-2", Thread.State.WAITING, waitingOnGit),
        )
        val lines = ThreadSummary.lines(threads)
        assert(lines.any { "waitFor" in it }) { "got $lines" }
        assert(lines.none { "park" in it }) { "got $lines" }
        assert(lines.last() == "  (1 idle threads not shown)") { "got $lines" }
    }

    test("threads waiting in a native call, or with no Java frames, count as idle") {
        val polling = listOf(StackTraceElement("sun.nio.fs.LinuxWatchService", "poll", null, -2)) // -2: native
        val threads = listOf(
            ThreadSummary.Snap("FileSystemWatchService", Thread.State.RUNNABLE, polling),
            ThreadSummary.Snap("Signal Dispatcher", Thread.State.RUNNABLE, emptyList()),
            ThreadSummary.Snap("worker", Thread.State.RUNNABLE, listOf(StackTraceElement("java.lang.ProcessImpl", "forkAndExec", null, -2), StackTraceElement("com.gitvantage.git.Git", "spawnNow", null, -1))),
        )
        val lines = ThreadSummary.lines(threads)
        assert(lines.first().startsWith("  1× worker")) { "got $lines" }
        assert(lines.last() == "  (2 idle threads not shown)") { "got $lines" }
    }

    test("a stall with nothing else going on says so") {
        val lines = ThreadSummary.lines(listOf(ThreadSummary.Snap("pool", Thread.State.WAITING, parked)))
        assert(lines.first() == "  (no other thread was doing anything)") { "got $lines" }
    }
}
