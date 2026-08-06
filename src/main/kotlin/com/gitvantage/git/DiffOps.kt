// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git

import com.gitvantage.git.model.Diff
import com.gitvantage.git.model.DiffCell
import com.gitvantage.git.model.DiffFileHead
import com.gitvantage.git.model.DiffFileRef
import com.gitvantage.git.model.DiffHunk
import com.gitvantage.git.model.DiffItem
import com.gitvantage.git.model.DiffRow
import com.gitvantage.git.model.DiffSection
import com.gitvantage.git.model.DiffSide
import com.gitvantage.model.Stash
import java.io.File
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Parses a repo's changes into a side-by-side diff model for [DiffView], split into
 * **Staged / Modified / Untracked** sections. Each hunk's removed/added lines are paired
 * into left/right [DiffRow]s with line numbers; paired modifications get a character-level
 * highlight (common prefix/suffix stripped) so the actual edit stands out. Loads either
 * the working tree (`load`) or a stash (`loadStash`).
 */
object DiffOps {

    private const val MAX_UNTRACKED_BYTES = 400_000L

    suspend fun load(repoPath: String): Diff = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        if (!Git.isRepo(dir)) return@withContext Diff(emptyList(), emptyList())
        val items = mutableListOf<DiffItem>()
        val files = mutableListOf<DiffFileRef>()
        appendSection("Staged", parseUnified(Git.read(dir, "-c", "color.ui=never", "diff", "--cached")), items, files)
        appendSection("Modified", parseUnified(Git.read(dir, "-c", "color.ui=never", "diff")), items, files)
        appendSection("Untracked", untrackedDiff(dir), items, files)
        Diff(files, items)
    }

    suspend fun loadStash(repoPath: String, ref: String): Diff = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        if (!Git.isRepo(dir)) return@withContext Diff(emptyList(), emptyList())
        val items = mutableListOf<DiffItem>()
        val files = mutableListOf<DiffFileRef>()
        appendSection("Stash", parseUnified(Git.read(dir, "-c", "color.ui=never", "stash", "show", "-p", ref)), items, files)
        Diff(files, items)
    }

    /** Diff of what a branch [ref] introduced since it forked from mainline:
     *  `git diff <mainline>...<ref>` (base = merge-base, so only the branch's own changes show). */
    suspend fun loadBranchDiff(repoPath: String, ref: String): Diff = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        if (!Git.isRepo(dir)) return@withContext Diff(emptyList(), emptyList())
        val base = mainlineFor(dir, ref) ?: return@withContext Diff(emptyList(), emptyList())
        val items = mutableListOf<DiffItem>()
        val files = mutableListOf<DiffFileRef>()
        appendSection("$ref ← $base", parseUnified(Git.read(dir, "-c", "color.ui=never", "diff", "$base...$ref")), items, files)
        Diff(files, items)
    }

    /** Diff of a commit range `[base]..[target]` in [repoPath] — used to show the file changes a
     *  submodule pointer move would/did advance across. */
    suspend fun loadRangeDiff(repoPath: String, base: String, target: String, label: String): Diff = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        if (!Git.isRepo(dir)) return@withContext Diff(emptyList(), emptyList())
        val items = mutableListOf<DiffItem>()
        val files = mutableListOf<DiffFileRef>()
        appendSection(label, parseUnified(Git.read(dir, "-c", "color.ui=never", "diff", "$base..$target")), items, files)
        Diff(files, items)
    }

    /** The mainline ref (main/master, local preferred) to diff [ref] against — never [ref] itself.
     *  Internal so [LogOps] can compute the same base for a branch's commit log. */
    internal fun mainlineFor(dir: File, ref: String): String? {
        val refShort = ref.substringAfter('/')
        return listOf("main", "master", "origin/main", "origin/master").firstOrNull { cand ->
            cand.substringAfter('/') != refShort &&
                Git.read(dir, "rev-parse", "--verify", "-q", cand).isNotBlank()
        }
    }

    /** Append a section (header + its items) to the shared lists, fixing up file indices. */
    private fun appendSection(label: String, local: Local, items: MutableList<DiffItem>, files: MutableList<DiffFileRef>) {
        if (local.files.isEmpty()) return
        val offset = items.size + 1   // +1 for the DiffSection header we add first
        items.add(DiffSection(label))
        items.addAll(local.items)
        local.files.forEach { f -> files.add(f.copy(index = f.index + offset, section = label)) }
    }

    private data class Local(val files: List<DiffFileRef>, val items: List<DiffItem>)

    private fun parseUnified(diffText: String): Local {
        val items = mutableListOf<DiffItem>()
        val files = mutableListOf<DiffFileRef>()
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
                    items.add(DiffRow(DiffCell(d.first, d.second, DiffSide.DEL, ls, le), DiffCell(a.first, a.second, DiffSide.ADD, rs, re)))
                } else {
                    items.add(DiffRow(d?.let { DiffCell(it.first, it.second, DiffSide.DEL) }, a?.let { DiffCell(it.first, it.second, DiffSide.ADD) }))
                }
            }
            dels.clear(); adds.clear()
        }
        fun flushCounts() {
            if (headIdx < 0) return
            (items[headIdx] as? DiffFileHead)?.let { items[headIdx] = it.copy(added = added, removed = removed) }
            files[files.lastIndex] = files.last().copy(added = added, removed = removed)
        }

        diffText.lineSequence().forEach { line ->
            when {
                line.startsWith("diff --git ") -> {
                    flushPairs(); flushCounts()
                    val path = line.substringAfterLast(" b/", "").ifBlank { line.removePrefix("diff --git ") }
                    added = 0; removed = 0; oldNo = 0; newNo = 0; headIdx = items.size
                    items.add(DiffFileHead(path, 0, 0))
                    files.add(DiffFileRef(path, 0, 0, headIdx, ""))
                }
                line.startsWith("index ") || line.startsWith("new file") || line.startsWith("deleted file") ||
                    line.startsWith("similarity ") || line.startsWith("rename ") || line.startsWith("copy ") ||
                    line.startsWith("--- ") || line.startsWith("+++ ") || line.startsWith("old mode") ||
                    line.startsWith("new mode") -> { /* header meta — skip */ }
                line.startsWith("Binary files") -> { flushPairs(); items.add(DiffRow(DiffCell(null, "(binary file)", DiffSide.CONTEXT), DiffCell(null, "", DiffSide.CONTEXT))) }
                line.startsWith("@@") -> {
                    flushPairs()
                    parseHunkStarts(line)?.let { (o, n) -> oldNo = o; newNo = n }
                    items.add(DiffHunk(line))
                }
                line.startsWith("+") -> { adds.add(newNo to line.substring(1)); newNo++; added++ }
                line.startsWith("-") -> { dels.add(oldNo to line.substring(1)); oldNo++; removed++ }
                else -> {
                    flushPairs()
                    val text = if (line.startsWith(" ")) line.substring(1) else line
                    items.add(DiffRow(DiffCell(oldNo, text, DiffSide.CONTEXT), DiffCell(newNo, text, DiffSide.CONTEXT)))
                    oldNo++; newNo++
                }
            }
        }
        flushPairs(); flushCounts()
        return Local(files, items)
    }

    private fun untrackedDiff(dir: File): Local {
        val items = mutableListOf<DiffItem>()
        val files = mutableListOf<DiffFileRef>()
        Git.read(dir, "ls-files", "--others", "--exclude-standard").lineSequence()
            .filter { it.isNotBlank() }
            .forEach { rel ->
                val f = File(dir, rel)
                val idx = items.size
                val content = runCatching { if (f.length() in 1..MAX_UNTRACKED_BYTES) f.readText() else null }.getOrNull()
                val lines = content?.split("\n") ?: emptyList()
                items.add(DiffFileHead(rel, lines.size, 0))
                files.add(DiffFileRef(rel, lines.size, 0, idx, ""))
                items.add(DiffHunk("@@ new file @@"))
                if (content == null) items.add(DiffRow(null, DiffCell(null, "(empty, binary, or too large to preview)", DiffSide.CONTEXT)))
                else lines.forEachIndexed { i, t -> items.add(DiffRow(null, DiffCell(i + 1, t, DiffSide.ADD))) }
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
}
