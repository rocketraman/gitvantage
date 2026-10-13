// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.app

import de.infix.testBalloon.framework.core.testSuite
import java.io.File
import java.time.Instant
import kotlin.io.path.createTempDirectory

/**
 * The file behind [DiagLog], against a scratch directory.
 *
 * It is always on and never cleaned up by anyone, so the two things that have to hold are the ones
 * nobody would notice failing until much later: it stays bounded, and a disk it cannot write to
 * costs the diagnostics rather than the app.
 */
val DiagSinkFile by testSuite {

    fun scratch(): File = createTempDirectory("gitvantage-diag").toFile().also { it.deleteOnExit() }

    test("an entry is stamped, and its continuation lines stay under the stamp") {
        val file = File(scratch(), "diagnostics.log")
        DiagSink(file, now = { Instant.parse("2026-10-13T12:00:00Z") }).append("UI thread blocked\n    at a.b.C\n")

        val lines = file.readLines()
        assert(lines.size == 2)
        assert(lines[0].endsWith("  UI thread blocked"))
        // Indented past the timestamp, so a stack can't be read as a run of separate entries.
        val indent = lines[0].length - "UI thread blocked".length
        assert(lines[1] == " ".repeat(indent) + "    at a.b.C")
    }

    test("the directory is created on first write") {
        // A first launch reports its start before the registry has ever been saved.
        val file = File(scratch(), "not/yet/there/diagnostics.log")
        DiagSink(file).append("hello")

        assert(file.readText().contains("hello"))
    }

    test("the log stays within two generations however much is written") {
        val file = File(scratch(), "diagnostics.log")
        val sink = DiagSink(file, maxBytes = 2_000)
        repeat(500) { sink.append("entry $it " + "x".repeat(40)) }

        val previous = File(file.path + ".1")
        assert(file.length() <= 2_000)
        assert(previous.length() <= 2_000)
        assert(file.parentFile.list()!!.sorted() == listOf("diagnostics.log", "diagnostics.log.1"))
        // Rotation discards the oldest, never the newest.
        assert(file.readText().contains("entry 499 "))
    }

    test("the tail reaches back across a rotation, newest last") {
        val file = File(scratch(), "diagnostics.log")
        val sink = DiagSink(file, maxBytes = 2_000)
        repeat(60) { sink.append("entry $it " + "x".repeat(40)) }
        // Only meaningful if the last few entries really do straddle the two files.
        assert(File(file.path + ".1").exists())
        val liveEntries = file.readLines().size

        val tail = sink.tail(maxBytes = 100_000).lines().filter { it.isNotBlank() }
        val seen = tail.map { it.substringAfter("entry ").substringBefore(' ').toInt() }

        assert(seen.size > liveEntries)
        assert(seen == seen.sorted())
        assert(seen.last() == 59)
    }

    test("a tail cut by size starts on a whole line") {
        val file = File(scratch(), "diagnostics.log")
        val sink = DiagSink(file)
        repeat(50) { sink.append("entry $it") }

        val tail = sink.tail(maxBytes = 200)
        assert(tail.length <= 200)
        assert(tail.lines().filter { it.isNotBlank() }.all { Regex("""\d{4}-\d\d-\d\d \d\d:\d\d:\d\d  entry \d+""").matches(it) })
    }

    test("a log that cannot be written is survived, and so is one that was never written") {
        // A regular file where the directory should be: mkdirs fails and so does the open.
        val blocker = File(scratch(), "blocker").apply { writeText("") }
        val sink = DiagSink(File(blocker, "diagnostics.log"))

        sink.append("lost")
        sink.append("lost again")

        assert(sink.tail() == "")
    }

    test("uptime reads in the largest two or three units that matter") {
        assert(Diagnostics.formatUptime(42) == "0m 42s")
        assert(Diagnostics.formatUptime(3_725) == "1h 2m")
        assert(Diagnostics.formatUptime(4 * 86_400 + 3 * 3_600 + 120) == "4d 3h 2m")
    }
}
