// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Parses a repo's changes into a side-by-side diff model for [DiffView], split into
 * **Staged / Modified / Untracked** sections. Each hunk's removed/added lines are paired
 * into left/right [Row]s with line numbers; paired modifications get a character-level
 * highlight (common prefix/suffix stripped) so the actual edit stands out. Loads either
 * the working tree (`load`) or a stash (`loadStash`).
 */
object DiffOps {

    enum class Side { CONTEXT, ADD, DEL }

    /** [hlStart]/[hlEnd] mark the intra-line changed span (−1 = none). */
    data class Cell(val no: Int?, val text: String, val side: Side, val hlStart: Int = -1, val hlEnd: Int = -1)

    sealed interface Item
    data class Section(val label: String) : Item
    data class FileHead(val path: String, val added: Int, val removed: Int) : Item
    data class Hunk(val text: String) : Item
    data class Row(val left: Cell?, val right: Cell?) : Item

    data class FileRef(val path: String, val added: Int, val removed: Int, val index: Int, val section: String)

    data class Diff(val files: List<FileRef>, val items: List<Item>)

    private const val MAX_UNTRACKED_BYTES = 400_000L

    suspend fun load(repoPath: String): Diff = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        if (!File(dir, ".git").exists()) return@withContext Diff(emptyList(), emptyList())
        val items = mutableListOf<Item>()
        val files = mutableListOf<FileRef>()
        appendSection("Staged", parseUnified(git(dir, "-c", "color.ui=never", "diff", "--cached")), items, files)
        appendSection("Modified", parseUnified(git(dir, "-c", "color.ui=never", "diff")), items, files)
        appendSection("Untracked", untrackedDiff(dir), items, files)
        Diff(files, items)
    }

    suspend fun loadStash(repoPath: String, ref: String): Diff = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        if (!File(dir, ".git").exists()) return@withContext Diff(emptyList(), emptyList())
        val items = mutableListOf<Item>()
        val files = mutableListOf<FileRef>()
        appendSection("Stash", parseUnified(git(dir, "-c", "color.ui=never", "stash", "show", "-p", ref)), items, files)
        Diff(files, items)
    }

    /** Diff of what a branch [ref] introduced since it forked from mainline:
     *  `git diff <mainline>...<ref>` (base = merge-base, so only the branch's own changes show). */
    suspend fun loadBranchDiff(repoPath: String, ref: String): Diff = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        if (!File(dir, ".git").exists()) return@withContext Diff(emptyList(), emptyList())
        val base = mainlineFor(dir, ref) ?: return@withContext Diff(emptyList(), emptyList())
        val items = mutableListOf<Item>()
        val files = mutableListOf<FileRef>()
        appendSection("$ref ← $base", parseUnified(git(dir, "-c", "color.ui=never", "diff", "$base...$ref")), items, files)
        Diff(files, items)
    }

    /** Diff of a commit range `[base]..[target]` in [repoPath] — used to show the file changes a
     *  submodule pointer move would/did advance across. */
    suspend fun loadRangeDiff(repoPath: String, base: String, target: String, label: String): Diff = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        if (!File(dir, ".git").exists()) return@withContext Diff(emptyList(), emptyList())
        val items = mutableListOf<Item>()
        val files = mutableListOf<FileRef>()
        appendSection(label, parseUnified(git(dir, "-c", "color.ui=never", "diff", "$base..$target")), items, files)
        Diff(files, items)
    }

    /** The mainline ref (main/master, local preferred) to diff [ref] against — never [ref] itself.
     *  Internal so [LogOps] can compute the same base for a branch's commit log. */
    internal fun mainlineFor(dir: File, ref: String): String? {
        val refShort = ref.substringAfter('/')
        return listOf("main", "master", "origin/main", "origin/master").firstOrNull { cand ->
            cand.substringAfter('/') != refShort &&
                git(dir, "rev-parse", "--verify", "-q", cand).isNotBlank()
        }
    }

    /** Append a section (header + its items) to the shared lists, fixing up file indices. */
    private fun appendSection(label: String, local: Local, items: MutableList<Item>, files: MutableList<FileRef>) {
        if (local.files.isEmpty()) return
        val offset = items.size + 1   // +1 for the Section header we add first
        items.add(Section(label))
        items.addAll(local.items)
        local.files.forEach { f -> files.add(f.copy(index = f.index + offset, section = label)) }
    }

    private data class Local(val files: List<FileRef>, val items: List<Item>)

    private fun parseUnified(diffText: String): Local {
        val items = mutableListOf<Item>()
        val files = mutableListOf<FileRef>()
        var headIdx = -1
        var added = 0
        var removed = 0
        var oldNo = 0
        var newNo = 0
        val dels = ArrayDeque<Pair<Int, String>>()
        val adds = ArrayDeque<Pair<Int, String>>()

        fun flushPairs() {
            val n = maxOf(dels.size, adds.size)
            for (i in 0 until n) {
                val d = dels.getOrNull(i)
                val a = adds.getOrNull(i)
                if (d != null && a != null) {
                    val (ls, le, rs, re) = charSpans(d.second, a.second)
                    items.add(Row(Cell(d.first, d.second, Side.DEL, ls, le), Cell(a.first, a.second, Side.ADD, rs, re)))
                } else {
                    items.add(Row(d?.let { Cell(it.first, it.second, Side.DEL) }, a?.let { Cell(it.first, it.second, Side.ADD) }))
                }
            }
            dels.clear(); adds.clear()
        }
        fun flushCounts() {
            if (headIdx < 0) return
            (items[headIdx] as? FileHead)?.let { items[headIdx] = it.copy(added = added, removed = removed) }
            files[files.lastIndex] = files.last().copy(added = added, removed = removed)
        }

        diffText.lineSequence().forEach { line ->
            when {
                line.startsWith("diff --git ") -> {
                    flushPairs(); flushCounts()
                    val path = line.substringAfterLast(" b/", "").ifBlank { line.removePrefix("diff --git ") }
                    added = 0; removed = 0; oldNo = 0; newNo = 0; headIdx = items.size
                    items.add(FileHead(path, 0, 0))
                    files.add(FileRef(path, 0, 0, headIdx, ""))
                }
                line.startsWith("index ") || line.startsWith("new file") || line.startsWith("deleted file") ||
                    line.startsWith("similarity ") || line.startsWith("rename ") || line.startsWith("copy ") ||
                    line.startsWith("--- ") || line.startsWith("+++ ") || line.startsWith("old mode") ||
                    line.startsWith("new mode") -> { /* header meta — skip */ }
                line.startsWith("Binary files") -> { flushPairs(); items.add(Row(Cell(null, "(binary file)", Side.CONTEXT), Cell(null, "", Side.CONTEXT))) }
                line.startsWith("@@") -> {
                    flushPairs()
                    parseHunkStarts(line)?.let { (o, n) -> oldNo = o; newNo = n }
                    items.add(Hunk(line))
                }
                line.startsWith("+") -> { adds.add(newNo to line.substring(1)); newNo++; added++ }
                line.startsWith("-") -> { dels.add(oldNo to line.substring(1)); oldNo++; removed++ }
                else -> {
                    flushPairs()
                    val text = if (line.startsWith(" ")) line.substring(1) else line
                    items.add(Row(Cell(oldNo, text, Side.CONTEXT), Cell(newNo, text, Side.CONTEXT)))
                    oldNo++; newNo++
                }
            }
        }
        flushPairs(); flushCounts()
        return Local(files, items)
    }

    private fun untrackedDiff(dir: File): Local {
        val items = mutableListOf<Item>()
        val files = mutableListOf<FileRef>()
        git(dir, "ls-files", "--others", "--exclude-standard").lineSequence()
            .filter { it.isNotBlank() }
            .forEach { rel ->
                val f = File(dir, rel)
                val idx = items.size
                val content = runCatching { if (f.length() in 1..MAX_UNTRACKED_BYTES) f.readText() else null }.getOrNull()
                val lines = content?.split("\n") ?: emptyList()
                items.add(FileHead(rel, lines.size, 0))
                files.add(FileRef(rel, lines.size, 0, idx, ""))
                items.add(Hunk("@@ new file @@"))
                if (content == null) items.add(Row(null, Cell(null, "(empty, binary, or too large to preview)", Side.CONTEXT)))
                else lines.forEachIndexed { i, t -> items.add(Row(null, Cell(i + 1, t, Side.ADD))) }
            }
        return Local(files, items)
    }

    /** Common-prefix/suffix char span of the change: (leftStart, leftEnd, rightStart, rightEnd). */
    private fun charSpans(a: String, b: String): IntArray {
        var p = 0
        val maxP = min(a.length, b.length)
        while (p < maxP && a[p] == b[p]) p++
        var s = 0
        while (s < min(a.length - p, b.length - p) && a[a.length - 1 - s] == b[b.length - 1 - s]) s++
        return intArrayOf(p, a.length - s, p, b.length - s)
    }

    private fun parseHunkStarts(line: String): Pair<Int, Int>? {
        val m = Regex("""@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""").find(line) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
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
