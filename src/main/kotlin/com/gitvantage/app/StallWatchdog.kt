// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.app

import com.gitvantage.model.Perf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Watches the UI thread for the one failure this app keeps producing: something slow run where the
 * interface is drawn, so the window stops answering.
 *
 * It measures **round-trip time on the UI dispatcher**, not frame time, and the distinction is the
 * whole point. Frame pacing here is the windowing layer's business and has already been measured as
 * such; a frame-time metric would rediscover that and bury the thing being looked for. Round-trip
 * asks only "how long until work handed to the UI thread starts running", which is answered by the
 * app and nothing else.
 *
 * The stack is captured **while the thread is still stuck**, which is what makes the report worth
 * having. Timing the stall and printing afterwards says a stall happened; sampling mid-stall says
 * what it was doing — an `fsync`, a `waitFor` on a subprocess, a tree walk. Every UI-thread bug
 * found in this codebase would have been named outright by one of those stacks, and each instead
 * took a reproduction built by hand on a machine that wasn't the one having the problem.
 *
 * Always on, because a diagnostic that has to be switched on before it can see anything is a
 * diagnostic that is off when the report arrives. It costs one empty coroutine per [INTERVAL_MS]
 * and prints nothing until something is wrong. `GITVANTAGE_STALL_MS=0` turns it off; any other
 * number sets the threshold.
 */
object StallWatchdog {

    /** How often to probe. Fast enough to catch a stall while it lasts, slow enough to be free. */
    private const val INTERVAL_MS = 250L

    /**
     * How long the UI thread may be unresponsive before it's worth saying so.
     *
     * Half a second is past "a slow frame" and into "the window is not answering". Lower would
     * report ordinary recomposition under load; much higher and the short stalls that add up to a
     * dashboard feeling sticky would never be named.
     */
    private const val DEFAULT_THRESHOLD_MS = 500L

    /** Giving up on a heartbeat that never comes back — a cancelled scope at shutdown, normally. */
    private const val ABANDON_MS = 30_000L

    /** How long after startup [scheduleDeliberateStall] fires, when it is switched on at all. */
    private const val DELIBERATE_STALL_AFTER_MS = 6_000L

    /** Frames of the captured stack to print. Enough to cross the framework and reach app code. */
    private const val STACK_FRAMES = 30

    private val thresholdMs: Long =
        System.getenv("GITVANTAGE_STALL_MS")?.toLongOrNull() ?: DEFAULT_THRESHOLD_MS

    @Volatile
    private var uiThread: Thread? = null

    @Volatile
    private var started = false

    /** Number of stalls seen, so the report can say whether this is the first or the fortieth. */
    private var stalls = 0L

    /**
     * Begin watching [scope], which must be the UI-dispatched scope.
     *
     * The thread is taken from the caller, synchronously, because this is constructed during
     * composition and so is already *on* the UI thread. Dispatching a task to go and find out
     * instead — the obvious way — queues that task behind whatever the UI thread is already doing,
     * which means the first stall of the session is reported with no stack at all. That is the
     * startup stall: the one worth having, and the one that arrives before any answer could.
     */
    fun start(scope: CoroutineScope) {
        if (thresholdMs <= 0 || started) return
        started = true
        uiThread = Thread.currentThread()
        thread(isDaemon = true, name = "gitvantage-stall-watchdog") { watch(scope) }
        scheduleDeliberateStall(scope)
    }

    /**
     * Block the UI thread on purpose, so this can be seen working.
     *
     * A watchdog nobody has watched work is a watchdog nobody should believe, and this one is
     * particularly hard to take on trust: it is meant to fire during exactly the situations that are
     * awkward to arrange deliberately, and it has already shipped one bug — reporting a stall with no
     * stack — that only running it revealed.
     *
     * The obvious way to fake a hang from outside, `kill -STOP`, cannot work. This measures the UI
     * thread *against another thread that is still running*; SIGSTOP freezes the whole process, so
     * the watchdog is suspended alongside its subject and no relative delay exists to see. The
     * frozen time also lands almost entirely inside this loop's own `sleep`, so on SIGCONT it simply
     * takes a fresh timestamp and finds the UI thread perfectly responsive.
     *
     * Blocking one thread from inside the process is the only thing that reproduces the real
     * failure, and this is deliberately a *blocking* sleep on the UI dispatcher rather than a
     * suspending `delay`, because a suspension frees the thread and would be no stall at all.
     */
    private fun scheduleDeliberateStall(scope: CoroutineScope) {
        val blockMs = System.getenv("GITVANTAGE_DEBUG_STALL_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: return
        scope.launch {
            // Far enough in that startup has settled, so the stall stands out from the composition
            // and class-loading work that legitimately occupies the first second or two.
            delay(DELIBERATE_STALL_AFTER_MS)
            System.err.println(
                "GitVantage: blocking the UI thread for ${blockMs}ms on purpose " +
                    "(GITVANTAGE_DEBUG_STALL_MS is set) — the watchdog should report this",
            )
            @Suppress("BlockingMethodInNonBlockingContext")
            Thread.sleep(blockMs)
        }
    }

    private fun watch(scope: CoroutineScope) {
        while (true) {
            Thread.sleep(INTERVAL_MS)
            val sent = System.nanoTime()
            val arrived = CountDownLatch(1)
            // A cancelled scope silently never runs this, which is why the wait below is bounded.
            runCatching {
                scope.launch {
                    uiThread = Thread.currentThread() // stays correct however start() was reached
                    arrived.countDown()
                }
            }.onFailure { return }

            if (arrived.await(thresholdMs, TimeUnit.MILLISECONDS)) {
                Perf.time("ui.dispatch", System.nanoTime() - sent)
                continue
            }
            // Still waiting, so the thread is stuck *now* — take the stack before it recovers.
            val stack = uiThread?.stackTrace
            val recovered = arrived.await(ABANDON_MS, TimeUnit.MILLISECONDS)
            val blockedMs = (System.nanoTime() - sent) / 1_000_000
            Perf.count("ui.stalls")
            Perf.time("ui.dispatch", System.nanoTime() - sent)
            report(blockedMs, recovered, stack)
            if (!recovered) return // the scope is gone; nothing left to watch
        }
    }

    private fun report(blockedMs: Long, recovered: Boolean, stack: Array<StackTraceElement>?) {
        stalls++
        val err = StringBuilder()
        err.append("GitVantage: UI thread blocked for ${blockedMs}ms")
        if (!recovered) err.append(" (and had not recovered when this was written)")
        err.append(" — stall #$stalls\n")
        if (stack == null || stack.isEmpty()) {
            err.append("  (no stack: the UI thread had not been identified yet)\n")
        } else {
            stack.take(STACK_FRAMES).forEach { err.append("    at ").append(it).append('\n') }
            if (stack.size > STACK_FRAMES) err.append("    … ${stack.size - STACK_FRAMES} more frames\n")
        }
        System.err.print(err)
    }
}
