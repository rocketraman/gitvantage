// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git.model

/** One recorded invocation of `git`, as the console shows it. */
data class GitCommand(
    val seq: Long,
    val repo: String,
    val command: String,   // "git push -u origin HEAD"
    val exitCode: Int,
    val durationMs: Long,
    val output: String,    // combined stdout+stderr, may contain ANSI SGR escapes
)
