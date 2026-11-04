// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.app

import java.lang.management.ManagementFactory

/**
 * How fast the app allocates, and which threads do it.
 *
 * The collector's figures say how often it ran and for how long; they cannot say how much was
 * allocated to make it run, and that is the number a busy collector is a symptom of. A field report
 * showed 2.5 young collections a second and 6% of the time in GC with a steady ~55MB live — about
 * 1GB/s allocated, if the young generation was the 10% of max heap the native image defaults to —
 * and that had to be worked out by hand from a flag default, because nothing measured it.
 *
 * Grouped by thread because the groups are where the possible answers live: the UI thread is
 * composition and text layout, the dispatcher workers are scans and the `git` output they read, the
 * watcher threads are filesystem events. A total alone would confirm the problem and leave the
 * cause exactly where it was.
 *
 * Measured from the per-thread allocation counters, which a native image may not support; when it
 * doesn't, the line says so rather than reporting zero. That includes the native image's way of not
 * supporting them, which is to say it does and then read 0 for every thread: a field report showed
 * "allocated 0MB since start" beside 218 young collections. A thread that ends between two samples takes
 * whatever it allocated since the last one with it, so the figures are a slight undercount.
 */
object AllocationMeter {

    /** Where a thread's allocations are filed — see [groupOf]. */
    enum class Group(val label: String) { UI("ui"), WORKERS("workers"), WATCHER("watcher"), OTHER("other") }

    private val bean: com.sun.management.ThreadMXBean? =
        runCatching { ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean }.getOrNull()
            ?.takeIf { runCatching { it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled }.getOrDefault(false) }

    /** Each live thread's allocation counter when it was last read. */
    private val lastSeen = HashMap<Long, Long>()

    /** Everything attributed since the process started, by group. */
    private val total = LongArray(Group.entries.size)

    /** Attributed since the last [interval], by group. */
    private val recent = LongArray(Group.entries.size)
    private var recentSince = System.nanoTime()

    /** Whether [start] has taken the baseline [recent] is measured from. */
    private var baselined = false

    /** Whether any thread's counter has ever read above zero — which a working one does at once. */
    private var counted = false

    private const val UNSUPPORTED = "alloc unavailable (this runtime has no per-thread allocation counters)"
    private const val ALL_ZERO = "alloc unavailable (this runtime's per-thread counters all read 0)"

    /**
     * Which group a thread belongs to, by its name — and by identity for the UI thread, whose name
     * is the platform's ("main" or an AppKit thread) and says nothing.
     */
    internal fun groupOf(name: String, isUi: Boolean): Group = when {
        isUi -> Group.UI
        name.startsWith("DefaultDispatcher-worker") -> Group.WORKERS
        name.startsWith("notify-rs") -> Group.WATCHER
        else -> Group.OTHER
    }

    /** Read every live thread's counter and file what it allocated since the last read. */
    private fun sample(bean: com.sun.management.ThreadMXBean) {
        val ids = bean.allThreadIds
        // Depth 0: names and nothing else. Stacks would cost a safepoint per sample for no reason.
        val infos = bean.getThreadInfo(ids, 0)
        val bytes = bean.getThreadAllocatedBytes(ids)
        val ui = StallWatchdog.uiThreadId
        val live = HashSet<Long>(ids.size)
        ids.forEachIndexed { i, id ->
            val now = bytes[i]
            val name = infos[i]?.threadName
            if (now < 0 || name == null) return@forEachIndexed // ended since it was listed
            live += id
            if (now > 0) counted = true
            val delta = now - (lastSeen.put(id, now) ?: 0L)
            if (delta <= 0) return@forEachIndexed
            val g = groupOf(name, id == ui).ordinal
            total[g] += delta
            recent[g] += delta
        }
        lastSeen.keys.retainAll(live)
    }

    /**
     * The allocation rate since the last call, for the periodic perf dump: "alloc 980MB/s over 60s
     * (workers 71% · ui 22% · …)", or a line saying the counters aren't available.
     */
    @Synchronized
    fun interval(): String {
        val b = bean ?: return UNSUPPORTED
        if (!baselined) {
            start()
            return "alloc measuring from now"
        }
        runCatching { sample(b) }.onFailure { return "alloc unavailable (${it.javaClass.simpleName})" }
        if (!counted) return ALL_ZERO
        val now = System.nanoTime()
        val seconds = ((now - recentSince) / 1_000_000_000.0).coerceAtLeast(0.001)
        val line = "alloc %.1fMB/s over %ds".format(recent.sum() / seconds / (1024 * 1024), seconds.toLong()) +
            breakdown(recent)
        recent.fill(0)
        recentSince = now
        return line
    }

    /**
     * Take the baseline the next [interval] is measured from — whenever collection switches on, so
     * the first reading covers the time it has been on rather than the whole session.
     *
     * Without one the first reading would file everything each thread allocated since it started
     * under an interval of no length at all; [interval] takes it itself if nobody did.
     */
    @Synchronized
    fun start() {
        val b = bean ?: return
        runCatching { sample(b) }
        recent.fill(0)
        recentSince = System.nanoTime()
        baselined = true
    }

    /** Everything attributed since the process started, for the clipboard report. Doesn't move [interval]'s baseline. */
    @Synchronized
    fun total(): String {
        val b = bean ?: return UNSUPPORTED
        runCatching { sample(b) }.onFailure { return "alloc unavailable (${it.javaClass.simpleName})" }
        if (!counted) return ALL_ZERO
        return "allocated ${mb(total.sum().toDouble())}MB since start" + breakdown(total)
    }

    private fun breakdown(byGroup: LongArray): String {
        val sum = byGroup.sum()
        if (sum <= 0) return ""
        return Group.entries
            .map { it to byGroup[it.ordinal] }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .joinToString(" · ", prefix = " (", postfix = ")") { (g, n) -> "${g.label} ${n * 100 / sum}%" }
    }

    private fun mb(bytes: Double): Long = (bytes / (1024 * 1024)).toLong()
}
