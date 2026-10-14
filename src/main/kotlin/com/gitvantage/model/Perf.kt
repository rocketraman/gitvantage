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
 * So: switch it on and the numbers come out on their own. `GITVANTAGE_PERF=1` does it from a
 * terminal; the Diagnostics section of Settings does it for someone who launched from a dock and has
 * no terminal to export anything in — which is most of the people this was ever needed for, and the
 * reason it stopped being an environment variable alone. Recording is a couple of atomic adds behind
 * one volatile read when it's off, and nothing is printed unless asked for.
 *
 * Deliberately not percentiles. Keeping samples to compute them costs more than the measurement is
 * worth, and the question these answer — "is a `git` spawn five milliseconds or two hundred" — is
 * settled by a mean and a maximum. A distribution would be the next step, not the first one.
 */
object Perf {

    /** Set by the environment, which then wins: a terminal run asked for this explicitly. */
    val forcedOn: Boolean = System.getenv("GITVANTAGE_PERF") == "1"

    /**
     * Whether to record at all.
     *
     * Volatile rather than the constant it used to be, so it can be switched on in a session that
     * has *already* become slow — restarting to collect data discards the state being asked about.
     * The read sits in front of every `git` spawn, where it is noise beside the spawn itself.
     */
    @Volatile
    var enabled: Boolean = forcedOn
        private set

    /** When recording last went from off to on; meaningless while it is off. */
    @Volatile
    private var enabledAtNanos = System.nanoTime()

    /** Start or stop recording. Stopping keeps what was recorded; [forcedOn] cannot be undone. */
    fun enable(on: Boolean) {
        val next = on || forcedOn
        if (next && !enabled) enabledAtNanos = System.nanoTime()
        enabled = next
    }

    /**
     * How long recording has been on, in seconds, or null while it is off.
     *
     * The counts mean nothing without it: collection is usually switched on partway through a
     * session, so dividing by the app's uptime understates every rate by however long it was off.
     */
    fun collectingSeconds(): Long? =
        if (enabled) (System.nanoTime() - enabledAtNanos) / 1_000_000_000 else null

    /** One measured thing: how often, how long in total, and the worst single case. */
    class Stat {
        val count = AtomicLong()
        val totalNanos = AtomicLong()
        private val maxNanosRef = AtomicLong()
        private val recentMaxNanosRef = AtomicLong()

        val maxNanos: Long get() = maxNanosRef.get()

        fun record(nanos: Long) {
            count.incrementAndGet()
            totalNanos.addAndGet(nanos)
            raise(maxNanosRef, nanos)
            raise(recentMaxNanosRef, nanos)
        }

        /** The worst case since this was last asked, forgetting it — see [Perf.report]. */
        fun takeRecentMaxNanos(): Long = recentMaxNanosRef.getAndSet(0)
    }

    /** How many of one thing are running at once: now, and at worst. */
    private class Level {
        val now = AtomicLong()
        val peak = AtomicLong()
    }

    // Compare-and-set rather than a lock: contended by every scan thread at once, and a maximum
    // that loses a race understates by one sample rather than corrupting.
    private fun raise(ref: AtomicLong, value: Long) {
        while (true) {
            val seen = ref.get()
            if (value <= seen || ref.compareAndSet(seen, value)) break
        }
    }

    private val stats = ConcurrentHashMap<String, Stat>()
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val levels = ConcurrentHashMap<String, Level>()

    /** Count and total of each stat at the last [report] that marked an interval. */
    private val marked = ConcurrentHashMap<String, Pair<Long, Long>>()

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
     * Run [body] counted as one more [name] in flight, remembering the most there ever were.
     *
     * A mean spawn time says nothing about forty of them running at once, and that is its own way
     * for a machine to feel slow: each `git` is quick and the fans are on anyway.
     */
    inline fun <T> concurrent(name: String, body: () -> T): T {
        // Decided once: recording can be switched on or off while [body] runs, and an enter
        // without its leave would leave the level reading one too many for the rest of the session.
        if (!enabled) return body()
        enter(name)
        try {
            return body()
        } finally {
            leave(name)
        }
    }

    @PublishedApi
    internal fun enter(name: String) {
        val level = levels.computeIfAbsent(name) { Level() }
        raise(level.peak, level.now.incrementAndGet())
    }

    @PublishedApi
    internal fun leave(name: String) {
        levels[name]?.now?.decrementAndGet()
    }

    /**
     * Everything recorded since the last [reset], as lines fit for a log.
     *
     * Sorted by total time rather than by name, so the line that matters is the first one read.
     *
     * Each timed line ends with what happened *recently* — since the last call that passed
     * [markInterval] — because the totals alone cannot tell "slow all along" from "fine for three
     * hours and slow for the last ten minutes", and the second is what people actually report. The
     * periodic dump marks; a report built for the clipboard does not, or reading the numbers would
     * move the baseline the next dump is measured from.
     */
    fun report(markInterval: Boolean = false): List<String> {
        if (stats.isEmpty() && counters.isEmpty() && levels.isEmpty()) return emptyList()
        val timed = stats.entries
            .sortedByDescending { it.value.totalNanos.get() }
            .map { (name, s) ->
                val count = s.count.get()
                val totalNanos = s.totalNanos.get()
                val (countThen, totalThen) = marked[name] ?: (0L to 0L)
                val recentMax = if (markInterval) s.takeRecentMaxNanos() else null
                if (markInterval) marked[name] = count to totalNanos
                val total = totalNanos / 1_000_000.0
                val line = "  %-28s n=%-7d total=%9.1fms  mean=%7.2fms  max=%8.1fms  | recent n=%-5d total=%8.1fms"
                    .format(
                        name, count, total, total / count.coerceAtLeast(1), s.maxNanos / 1_000_000.0,
                        count - countThen, (totalNanos - totalThen) / 1_000_000.0,
                    )
                if (recentMax == null) line else line + "  max=%8.1fms".format(recentMax / 1_000_000.0)
            }
        val counted = counters.entries
            .sortedByDescending { it.value.get() }
            .map { (name, c) -> "  %-28s n=%d".format(name, c.get()) }
        val levelled = levels.entries
            .sortedBy { it.key }
            .map { (name, l) -> "  %-28s peak=%d now=%d".format(name, l.peak.get(), l.now.get()) }
        return timed + counted + levelled
    }

    fun reset() {
        stats.clear()
        counters.clear()
        levels.clear()
        marked.clear()
    }
}
