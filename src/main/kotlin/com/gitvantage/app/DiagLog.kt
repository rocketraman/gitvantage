// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.app

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Where the app's diagnostics go: stderr, as always, and a small file beside the registry.
 *
 * The file is the point. Everything here used to be printed to stderr alone, on the reasoning that
 * someone running the binary from a terminal to find out why it is slow is who it was for. That
 * person turned out not to exist: the reports come from people who started the app from a dock,
 * where stderr is connected to nothing, so the stall watchdog — always on precisely so that it
 * would already have the answer when a report arrived — had been naming the culprit to nobody.
 *
 * Still not a logging framework, and deliberately. There are no levels and no categories; this is
 * one append-only file of the few things worth saying, bounded so that it can be left on forever.
 */
object DiagLog {

    /** `diagnostics.log`, next to `registry.json` — the one directory this app already owns. */
    val file: File by lazy { File(Registry.file.absoluteFile.parentFile, "diagnostics.log") }

    private val sink by lazy { DiagSink(file) }

    /** Record [text], which may be several lines, as one entry. Never throws. */
    fun write(text: String) {
        System.err.println(text)
        sink.append(text)
    }

    /** The newest [maxBytes] of what has been recorded, across a rotation. Reads the disk. */
    fun tail(maxBytes: Int = DiagSink.DEFAULT_TAIL_BYTES): String = sink.tail(maxBytes)
}

/**
 * An append-only text file that cannot grow without bound and cannot take the app down with it.
 *
 * Split out from [DiagLog] so the two properties that matter can be tested against a scratch file:
 * that it stays within [maxBytes] however long the app runs, and that a disk it cannot write to
 * costs the diagnostics and nothing else.
 *
 * One generation of history, `<name>.1`, rather than a numbered series. The live file is moved
 * aside when the next entry would take it past [maxBytes], so the two together hold between one
 * and two times that — enough to cover the hours before a report, which is all this is for.
 *
 * Nothing is forced to the platter. An fsync per entry is exactly the kind of stall this exists to
 * report (the registry's own writes had to be moved off the UI thread for it), and a diagnostics
 * line lost to a power cut is not worth one.
 */
class DiagSink(
    private val file: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val now: () -> Instant = Instant::now,
) {
    private val previous = File(file.path + ".1")
    private val lock = Any()

    /** Said once rather than per entry: a full disk would otherwise fill the terminal as well. */
    private var failureReported = false

    fun append(text: String) {
        val entry = buildString {
            append(STAMP.format(now())).append("  ")
            // Continuation lines are indented under the timestamp, so an entry stays one visual
            // unit and a stack trace cannot be mistaken for a run of separate events.
            append(text.trimEnd().replace("\n", "\n" + " ".repeat(STAMP_WIDTH + 2)))
            append('\n')
        }.toByteArray()
        synchronized(lock) {
            runCatching {
                file.absoluteFile.parentFile?.mkdirs()
                if (file.length() + entry.size > maxBytes && file.length() > 0) {
                    Files.move(file.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                Files.write(file.toPath(), entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }.onFailure { e ->
                if (!failureReported) {
                    failureReported = true
                    System.err.println("GitVantage: couldn't write ${file.path} — $e")
                }
            }
        }
    }

    fun tail(maxBytes: Int = DEFAULT_TAIL_BYTES): String = synchronized(lock) {
        runCatching {
            val live = lastBytes(file, maxBytes)
            // Only reach into the previous generation for what the live file couldn't supply, so a
            // report taken just after a rotation isn't three lines long.
            val older = if (live.size < maxBytes) lastBytes(previous, maxBytes - live.size) else ByteArray(0)
            val text = String(older + live)
            // A cut made by byte count lands mid-line; drop the fragment rather than show it.
            if (older.size + live.size < maxBytes) text else text.substringAfter('\n', text)
        }.getOrDefault("")
    }

    private fun lastBytes(f: File, max: Int): ByteArray {
        if (max <= 0 || !f.isFile) return ByteArray(0)
        RandomAccessFile(f, "r").use { raf ->
            val take = minOf(raf.length(), max.toLong()).toInt()
            raf.seek(raf.length() - take)
            return ByteArray(take).also { raf.readFully(it) }
        }
    }

    companion object {
        /** Per generation, so at most twice this on disk. */
        const val DEFAULT_MAX_BYTES = 1L shl 20

        /** What a copied report carries: enough for several stalls and a few counter dumps. */
        const val DEFAULT_TAIL_BYTES = 48 * 1024

        private const val STAMP_WIDTH = 19
        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
    }
}
