// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git.model

/**
 * The side-by-side diff model. Names are prefixed because these are top-level types now: a bare
 * `Row` or `Cell` would collide with Compose's own composables at every UI call site.
 */
enum class DiffSide { CONTEXT, ADD, DEL }

/** [hlStart]/[hlEnd] mark the intra-line changed span (−1 = none). */
data class DiffCell(val no: Int?, val text: String, val side: DiffSide, val hlStart: Int = -1, val hlEnd: Int = -1)

sealed interface DiffItem
data class DiffSection(val label: String) : DiffItem
data class DiffFileHead(val path: String, val added: Int, val removed: Int) : DiffItem
data class DiffHunk(val text: String) : DiffItem
data class DiffRow(val left: DiffCell?, val right: DiffCell?) : DiffItem

data class DiffFileRef(val path: String, val added: Int, val removed: Int, val index: Int, val section: String)

data class Diff(val files: List<DiffFileRef>, val items: List<DiffItem>)
