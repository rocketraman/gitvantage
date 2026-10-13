// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.app

import com.gitvantage.BuildInfo
import com.gitvantage.model.Perf
import java.lang.management.ManagementFactory

/**
 * The report someone pastes into an issue, and the pieces it is assembled from.
 *
 * Everything a first reply would otherwise have to ask for — which build, which machine, how long it
 * had been running, how much it was tracking — because each of those questions costs a day when it
 * is asked of someone who only wanted to say the app got slow.
 *
 * What it leaves out is as deliberate: no paths, no remote URLs, no branch names. The counters are
 * keyed by subcommand and the stall reports are stack traces, so the one place a name can appear is
 * a filesystem-watch error, which says which folder it could not watch.
 */
object Diagnostics {

    private val startedNanos = System.nanoTime()

    /** Which build this is and what it is running on — constant for the life of the process. */
    val environment: String by lazy {
        // "runtime" inside a native image, absent on a JVM. Worth saying: the two have different
        // collectors and different startup, and "it's slow" means different things on each.
        val image = if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) "native" else "jvm"
        fun prop(name: String) = System.getProperty(name).orEmpty()
        "GitVantage ${BuildInfo.VERSION} ($image) · ${prop("os.name")} ${prop("os.version")} ${prop("os.arch")}" +
            " · ${Runtime.getRuntime().availableProcessors()} cpus"
    }

    /**
     * The process's own health, as lines: how long it has run, its heap, its collector, its threads.
     *
     * Read fresh on every call, and cheap enough to be — these are the numbers that separate "slow
     * because it is doing too much" from "slow because it has been open for four days", which the
     * counters cannot, since a leak spends its time in the collector rather than in anything timed.
     *
     * The collector and thread figures come from the platform beans, which a native image supports
     * only in part, so each is fetched on its own and simply missing where the image has none.
     */
    fun vitals(): List<String> = buildList {
        val rt = Runtime.getRuntime()
        add("uptime ${formatUptime((System.nanoTime() - startedNanos) / 1_000_000_000)}")
        add(
            "heap used=${mb(rt.totalMemory() - rt.freeMemory())}MB committed=${mb(rt.totalMemory())}MB " +
                "max=${mb(rt.maxMemory())}MB",
        )
        runCatching {
            ManagementFactory.getGarbageCollectorMXBeans()
                .filter { it.collectionCount > 0 }
                .joinToString(" · ") { "${it.name} n=${it.collectionCount} ${it.collectionTime}ms" }
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { add("gc $it") }
        runCatching { ManagementFactory.getThreadMXBean().threadCount }.getOrNull()?.let { add("threads $it") }
    }

    /**
     * The whole report: [environment], [inventory], [vitals], the counters, then the recent log.
     *
     * [inventory] is the caller's, because how much is being tracked is application state and this
     * object holds none. Reads the log from disk, so not for the UI thread.
     */
    fun report(inventory: String): String = buildString {
        appendLine(environment)
        appendLine(inventory)
        vitals().forEach { appendLine(it) }
        appendLine()
        val counters = Perf.report()
        when {
            counters.isNotEmpty() -> {
                val since = Perf.collectingSeconds()?.let { "collecting for ${formatUptime(it)}" }
                appendLine("Counters (${since ?: "collection now off"})")
                counters.forEach { appendLine(it) }
            }
            Perf.enabled -> appendLine("Counters: collecting, nothing recorded yet")
            else -> appendLine("Counters: not collected — turn on \"Collect performance data\" in Settings")
        }
        appendLine()
        appendLine("Recent log (${DiagLog.file.name})")
        append(DiagLog.tail().ifBlank { "  (empty)\n" })
    }

    internal fun formatUptime(seconds: Long): String {
        val d = seconds / 86_400
        val h = seconds % 86_400 / 3_600
        val m = seconds % 3_600 / 60
        return when {
            d > 0 -> "${d}d ${h}h ${m}m"
            h > 0 -> "${h}h ${m}m"
            else -> "${m}m ${seconds % 60}s"
        }
    }

    private fun mb(bytes: Long) = bytes / (1024 * 1024)
}
