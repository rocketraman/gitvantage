// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.app

import com.gitvantage.BuildInfo
import com.gitvantage.model.Perf
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

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
        "GitVantage ${BuildInfo.VERSION} ($image) · $platform · ${Runtime.getRuntime().availableProcessors()} cpus"
    }

    /**
     * "native" inside a native image, "jvm" on a JVM. Worth saying: the two have different
     * collectors and different startup, and "it's slow" means different things on each.
     */
    val image: String by lazy {
        // The property is "runtime" inside a native image and absent on a JVM.
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) "native" else "jvm"
    }

    /** The OS name, version and architecture, as the JVM reports them. */
    val platform: String by lazy {
        fun prop(name: String) = System.getProperty(name).orEmpty()
        "${prop("os.name")} ${prop("os.version")} ${prop("os.arch")}"
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
        residentMb()?.let { add("rss ${it}MB") }
        runCatching {
            ManagementFactory.getGarbageCollectorMXBeans()
                .filter { it.collectionCount > 0 }
                .joinToString(" · ") { "${it.name} n=${it.collectionCount} ${it.collectionTime}ms" }
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { add("gc $it") }
        runCatching { ManagementFactory.getThreadMXBean().threadCount }.getOrNull()?.let { add("threads $it") }
    }

    /**
     * The process's resident memory in MB, or null where it can't be read.
     *
     * The heap figures are only the Java part. A field report's 1.7GB footprint sat beside ~55MB of
     * live heap, and nothing logged could say how the rest grew — the collector keeping memory it had
     * touched, or native memory (graphics, the watcher library) the heap never sees. This is the
     * number that answers it over a long session.
     *
     * Linux reads `/proc`. macOS has no such file and no Java API for it, so it asks `ps` — a process
     * per call, which is why this is read once a minute while collecting and not more. Windows reports
     * nothing.
     */
    internal fun residentMb(): Long? = runCatching {
        val os = System.getProperty("os.name").orEmpty()
        when {
            os.startsWith("Linux") ->
                File("/proc/self/status").useLines { lines ->
                    lines.firstOrNull { it.startsWith("VmRSS:") }?.let(::parseVmRssKb)
                }?.let { it / 1024 }
            os.startsWith("Mac") -> {
                val p = ProcessBuilder("ps", "-o", "rss=", "-p", ProcessHandle.current().pid().toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start()
                val out = p.inputStream.bufferedReader().readText()
                if (p.waitFor(5, TimeUnit.SECONDS)) out.trim().toLongOrNull()?.let { it / 1024 } else null
            }
            else -> null
        }
    }.getOrNull()

    /** The kB figure from a `/proc` `VmRSS:` line — "VmRSS:   123456 kB". */
    internal fun parseVmRssKb(line: String): Long? =
        line.removePrefix("VmRSS:").trim().substringBefore(' ').toLongOrNull()

    /**
     * The whole report: [environment], [inventory], [vitals], the counters and [watched] — the
     * caller's `WatchStats` lines, labelled by it — then the recent log.
     *
     * [inventory] is the caller's, because how much is being tracked is application state and this
     * object holds none. Reads the log from disk, so not for the UI thread.
     */
    fun report(inventory: String, watched: List<String> = emptyList()): String = buildString {
        appendLine(environment)
        appendLine(inventory)
        vitals().forEach { appendLine(it) }
        appendLine(AllocationMeter.total())
        appendLine()
        val counters = Perf.report()
        when {
            counters.isNotEmpty() -> {
                val since = Perf.collectingSeconds()?.let { "collecting for ${formatUptime(it)}" }
                appendLine("Counters (${since ?: "collection now off"})")
                counters.forEach { appendLine(it) }
                watched.forEach { appendLine(it) }
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
