// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.model

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Counters for the things that have actually gone wrong here, off by default.
 *
 * Every performance question this app has raised came down to one of four numbers: how many `git`
 * processes a scan spawns and how long each takes, how long a scan takes end to end, how many
 * filesystem events arrive and how many scans they turn into, and how long a registry write blocks
 * for. Each was answered once by attaching `strace` or a profiler to a reproduction built by hand —
 * which works on the machine you own and not at all on the machine that has the problem.
 *
 * So: `GITVANTAGE_PERF=1` and the numbers come out on their own. Recording is a couple of atomic
 * adds behind a `val` the JIT folds away when it's off, and nothing is printed unless asked for.
 *
 * Deliberately not percentiles. Keeping samples to compute them costs more than the measurement is
 * worth, and the question these answer — "is a `git` spawn five milliseconds or two hundred" — is
 * settled by a mean and a maximum. A distribution would be the next step, not the first one.
 */
object Perf {

    /** Whether to record at all. Read once: the check has to be free at every call site. */
    val enabled: Boolean = System.getenv("GITVANTAGE_PERF") == "1"

    /** One measured thing: how often, how long in total, and the worst single case. */
    class Stat {
        val count = AtomicLong()
        val totalNanos = AtomicLong()
        private val maxNanosRef = AtomicLong()

        val maxNanos: Long get() = maxNanosRef.get()

        fun record(nanos: Long) {
            count.incrementAndGet()
            totalNanos.addAndGet(nanos)
            // Compare-and-set rather than a lock: contended by every scan thread at once, and a
            // maximum that loses a race understates by one sample rather than corrupting.
            while (true) {
                val seen = maxNanosRef.get()
                if (nanos <= seen || maxNanosRef.compareAndSet(seen, nanos)) break
            }
        }
    }

    private val stats = ConcurrentHashMap<String, Stat>()
    private val counters = ConcurrentHashMap<String, AtomicLong>()

    /** Record that [name] happened once — for things with no duration, like an event arriving. */
    fun count(name: String, n: Long = 1) {
        if (!enabled) return
        counters.computeIfAbsent(name) { AtomicLong() }.addAndGet(n)
    }

    /** Record that [name] took [nanos]. */
    fun time(name: String, nanos: Long) {
        if (!enabled) return
        stats.computeIfAbsent(name) { Stat() }.record(nanos)
    }

    /** Run [body], recording how long it took under [name]. Returns whatever [body] returns. */
    inline fun <T> timed(name: String, body: () -> T): T {
        if (!enabled) return body()
        val start = System.nanoTime()
        try {
            return body()
        } finally {
            time(name, System.nanoTime() - start)
        }
    }

    /**
     * Everything recorded since the last [reset], as lines fit for stderr.
     *
     * Sorted by total time rather than by name, so the line that matters is the first one read.
     */
    fun report(): List<String> {
        if (!enabled) return emptyList()
        val timed = stats.entries
            .sortedByDescending { it.value.totalNanos.get() }
            .map { (name, s) ->
                val n = s.count.get().coerceAtLeast(1)
                val total = s.totalNanos.get() / 1_000_000.0
                "  %-28s n=%-7d total=%8.1fms  mean=%7.2fms  max=%8.1fms"
                    .format(name, s.count.get(), total, total / n, s.maxNanos / 1_000_000.0)
            }
        val counted = counters.entries
            .sortedByDescending { it.value.get() }
            .map { (name, c) -> "  %-28s n=%d".format(name, c.get()) }
        return timed + counted
    }

    /**
     * Print everything recorded so far to stderr, under [label].
     *
     * stderr rather than a file or a UI panel, for the same reason the rest of this app's
     * diagnostics go there: there is deliberately no logging framework, and someone running the
     * binary from a terminal to find out why it is slow is exactly who this is for.
     */
    fun dump(label: String) {
        if (!enabled) return
        val lines = report()
        if (lines.isEmpty()) return
        System.err.println("GitVantage perf [$label]")
        lines.forEach { System.err.println(it) }
    }

    fun reset() {
        stats.clear()
        counters.clear()
    }
}
