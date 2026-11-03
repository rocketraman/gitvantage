// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.app

/**
 * Every other thread's stack at the moment the UI thread was caught stuck, reduced to what is worth
 * reading.
 *
 * The UI thread's own stack is often no help. On macOS it usually reads `nativeRunBlocking` and
 * nothing else — 18 of 20 stalls in one field report — because the thread is inside AppKit or the
 * allocator, where a Java stack cannot follow, waiting on a lock some *other* thread holds. So what
 * names the cause is what the rest of the process was doing: a dozen workers in `forkAndExec`, say.
 *
 * Idle threads are left out, since a pool parked waiting for work says nothing, and threads doing the
 * same thing are folded into one entry with a count, since twelve identical stacks are one fact.
 */
internal object ThreadSummary {

    /** One thread as captured: what it's called, what state it was in, and its frames. */
    data class Snap(val name: String, val state: Thread.State, val frames: List<StackTraceElement>)

    /**
     * Whether [t] is doing something: inside app code (starting or waiting on a `git` process, say),
     * blocked on a monitor, or running Java code.
     *
     * `RUNNABLE` alone is not enough. A thread parked in a native call — the JDK's watch-service
     * `poll`, the reference handler — reports `RUNNABLE` while it waits for nothing in particular,
     * and a thread with no Java frames at all has nothing here to read. Both are left out unless app
     * code is on the stack, which is what keeps a worker inside the native `forkAndExec` in.
     */
    fun interesting(t: Snap): Boolean = when {
        t.frames.any { it.className.startsWith("com.gitvantage.") } -> true
        t.state == Thread.State.BLOCKED -> true
        t.state == Thread.State.RUNNABLE -> t.frames.firstOrNull()?.isNativeMethod == false
        else -> false
    }

    /**
     * The interesting threads in [threads] as report lines, identical stacks folded together, the
     * most common first: at most [maxGroups] entries of at most [maxFrames] frames each.
     */
    fun lines(threads: List<Snap>, maxGroups: Int = 8, maxFrames: Int = 12): List<String> {
        val shown = threads.filter(::interesting)
        if (shown.isEmpty()) return listOf("  (no other thread was doing anything)")
        val groups = shown
            .groupBy { it.state to it.frames.take(maxFrames) }
            .entries
            .sortedByDescending { it.value.size }
        return buildList {
            groups.take(maxGroups).forEach { (key, members) ->
                val (state, frames) = key
                val names = members.map { it.name }.distinct()
                val named = names.take(3).joinToString(", ") + if (names.size > 3) ", …" else ""
                add("  ${members.size}× $named ($state)")
                frames.forEach { add("      at $it") }
                if (frames.isEmpty()) add("      (no Java frames)")
            }
            if (groups.size > maxGroups) {
                add("  … and ${groups.drop(maxGroups).sumOf { it.value.size }} more threads in ${groups.size - maxGroups} other states")
            }
            val idle = threads.size - shown.size
            if (idle > 0) add("  ($idle idle threads not shown)")
        }
    }
}
