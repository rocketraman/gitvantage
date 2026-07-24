// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reads the commits in an arbitrary revision range (`git log <range>`) into a list of
 * [Commit]s for [LogView]. The first consumer is "incoming" commits when a branch is behind
 * its upstream (`HEAD..@{upstream}`), but the range is a plain string so any set of commits
 * works. All process work runs on [Dispatchers.IO] so the UI thread is never blocked.
 */
object LogOps {

    data class Commit(
        val fullHash: String,
        val shortHash: String,
        val author: String,
        val relDate: String,   // "3 days ago" (committer, relative)
        val isoDate: String,   // absolute ISO-8601 (committer)
        val subject: String,
        val body: String,      // remaining message lines (may be blank)
    )

    private const val US = "\u001F"   // field separator within a record
    private const val RS = "\u001E"   // record separator between commits
    private const val FMT = "%H$US%h$US%an$US%cr$US%cI$US%s$US%b$RS"

    /** Commits a branch [ref] introduced since it forked from mainline (`git log <mainline>..<ref>`)
     *  — the log companion to [DiffOps.loadBranchDiff]. */
    suspend fun loadBranchLog(repoPath: String, ref: String): List<Commit> = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        if (!File(dir, ".git").exists()) return@withContext emptyList()
        val base = DiffOps.mainlineFor(dir, ref) ?: return@withContext emptyList()
        loadRange(repoPath, "$base..$ref")
    }

    /** Commits reachable in [range] (e.g. "HEAD..origin/main"), newest first, capped at [max]. */
    suspend fun loadRange(repoPath: String, range: String, max: Int = 500): List<Commit> = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        if (!File(dir, ".git").exists()) return@withContext emptyList()
        val out = git(dir, "log", "--max-count=$max", "--format=$FMT", range)
        out.split(RS).mapNotNull { rec ->
            val r = rec.trim('\n', '\r')
            if (r.isBlank()) return@mapNotNull null
            val f = r.split(US)
            if (f.size < 6) return@mapNotNull null
            Commit(
                fullHash = f[0].trim(),
                shortHash = f[1].trim(),
                author = f[2].trim(),
                relDate = f[3].trim(),
                isoDate = f[4].trim(),
                subject = f[5].trim(),
                body = f.getOrElse(6) { "" }.trim(),
            )
        }
    }

    private fun git(dir: File, vararg args: String): String = try {
        val proc = ProcessBuilder(listOf("git", *args))
            .directory(dir).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(30, TimeUnit.SECONDS)) { proc.destroyForcibly(); "" }
        else if (proc.exitValue() != 0) "" else out
    } catch (e: Exception) {
        ""
    }
}
