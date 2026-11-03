// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.model

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * What the background is doing right now: `git` processes running and started, scans in progress.
 *
 * Always counted, unlike [Perf], which is off unless asked for, because the stall report reads it and
 * the stall report is always on. It is how a frozen UI thread gets explained: on macOS every `git`
 * start is a `fork()` that holds locks the UI thread needs, so "37 git started while blocked" next
 * to a stall says what [Perf]'s totals can only hint at.
 *
 * Here rather than beside the code it counts so the UI layer can read it without reaching the git
 * runner, which only the state layer may.
 */
object Activity {
    private val gitRunning = AtomicInteger()
    private val gitStarted = AtomicLong()
    private val scans = AtomicInteger()

    val gitRunningNow: Int get() = gitRunning.get()
    val gitStartedTotal: Long get() = gitStarted.get()
    val scansRunningNow: Int get() = scans.get()

    /** Run [body] counted as one `git` process. */
    inline fun <T> git(body: () -> T): T {
        gitStart()
        try {
            return body()
        } finally {
            gitEnd()
        }
    }

    /** Run [body] counted as one scan in progress. */
    inline fun <T> scan(body: () -> T): T {
        scanStart()
        try {
            return body()
        } finally {
            scanEnd()
        }
    }

    @PublishedApi
    internal fun gitStart() {
        gitStarted.incrementAndGet()
        gitRunning.incrementAndGet()
    }

    @PublishedApi
    internal fun gitEnd() {
        gitRunning.decrementAndGet()
    }

    @PublishedApi
    internal fun scanStart() {
        scans.incrementAndGet()
    }

    @PublishedApi
    internal fun scanEnd() {
        scans.decrementAndGet()
    }
}
