// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

/**
 * Turns a flat list of file paths into an indented tree, collapsing chains of
 * single-child directories into one node (IntelliJ "compact middle packages") so deep
 * package paths like `src/main/kotlin/com/example` render as a single row rather than a
 * ladder of near-empty folders.
 */
object PathTree {

    /** [fullPath] null → directory node; non-null → file leaf. */
    data class Node(val depth: Int, val label: String, val fullPath: String?)

    private class T {
        val children = LinkedHashMap<String, T>()
        var fullPath: String? = null
        var isFile = false
    }

    fun flatten(paths: List<String>): List<Node> {
        val root = T()
        paths.sorted().forEach { p ->
            var cur = root
            val segs = p.split("/").filter { it.isNotEmpty() }
            segs.forEachIndexed { i, seg ->
                val child = cur.children.getOrPut(seg) { T() }
                if (i == segs.lastIndex) { child.isFile = true; child.fullPath = p }
                cur = child
            }
        }

        val out = mutableListOf<Node>()
        // Directories first, then files; each alphabetical.
        fun sorted(t: T) = t.children.entries.sortedWith(compareBy({ it.value.isFile }, { it.key.lowercase() }))
        fun walk(name: String, t: T, depth: Int) {
            if (t.isFile) { out.add(Node(depth, name, t.fullPath)); return }
            var label = name
            var node = t
            while (node.children.size == 1) {   // collapse single-subdirectory chains
                val (k, v) = node.children.entries.first()
                if (v.isFile) break
                label = "$label/$k"; node = v
            }
            out.add(Node(depth, label, null))
            sorted(node).forEach { (k, v) -> walk(k, v, depth + 1) }
        }
        sorted(root).forEach { (k, v) -> walk(k, v, 0) }
        return out
    }
}
