// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

import nl.jacobras.humanreadable.HumanReadable
import nl.jacobras.humanreadable.time.FormatStyle
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

/**
 * Derives the presentation-level snooze/reminder state on a [Repo] from the persisted
 * [RegistryEntry] metadata, given the current time. Kept separate from git scanning so
 * pure-metadata edits (snooze/remind) can update a repo in place without shelling out.
 */
object Meta {

    /** Far-future sentinel for "snooze until I resume" (no fixed end). */
    const val SNOOZE_FOREVER = Long.MAX_VALUE

    /** A repo/branch is "not worked on in a long time" once its last commit is older than this. */
    const val STALE_DAYS = 30L

    /** A branch counts as "very behind" its mainline past this many commits (for stale). */
    const val VERY_BEHIND = 40

    /** A repo counts as "Recently Added" for this long after it was first tracked. */
    const val RECENT_MS = 24L * 60 * 60 * 1000   // 24 hours

    /** Uncommitted changes that have sat this long flip the repo to the "Aging" attention signal. */
    const val AGING_MS = 24L * 60 * 60 * 1000   // 24 hours

    /** A repo counts as "Recently Modified" if its working tree changed within this window. */
    const val RECENT_MODIFIED_MS = 12L * 60 * 60 * 1000   // 12 hours

    /**
     * Prose duration style: full words with an explicit approximation word ("about 2 months").
     * Long is deliberate — the Short/Narrow styles mark approximation with "~" instead of the word,
     * and Narrow renders BOTH minutes and months as "m", which is ambiguous. Approximation matters
     * here: it's what keeps "flags as stale in about 1 week" reconcilable with "the last commit was
     * about 2 months ago" when the underlying values are rounded.
     */
    private val PROSE_STYLE = FormatStyle(date = FormatStyle.Date.Long, indicateApproximation = true)

    /** Human duration for prose: "3 days", "about 2 months". See [PROSE_STYLE]. */
    fun humanDuration(ms: Long): String = HumanReadable.duration(abs(ms).milliseconds, formatting = PROSE_STYLE)

    /** Human "time since" for prose: "3 days ago", "about 2 months ago". */
    fun humanAgo(ms: Long): String = "${humanDuration(ms)} ago"

    /** (isSnoozed, humanLabel) — label is a compact "for how long", or "indefinitely". */
    fun snoozeState(entry: RegistryEntry, now: Long): Pair<Boolean, String?> {
        val until = entry.snoozeUntilEpoch ?: return false to null
        if (until <= now) return false to null
        if (until == SNOOZE_FOREVER) return true to "until resumed"
        return true to compactDuration(until - now)
    }

    /** A [Reminder] if one is set (text + due), else null. Overdue when due has passed. */
    fun reminder(entry: RegistryEntry, now: Long): Reminder? {
        val due = entry.reminderDueEpoch ?: return null
        if (entry.reminderText.isBlank()) return null
        val overdue = due <= now
        val label = if (overdue) "overdue" else "in ${compactDuration(due - now)}"
        return Reminder(entry.reminderText.trim(), label, overdue)
    }

    /**
     * Parse a git remote URL into a browsable https base and whether it's a GitHub host.
     * Handles scp-style (`git@github.com:owner/repo.git`), `https://`, and `ssh://` forms.
     * Returns `(webBase, isGitHub)`; webBase is null when the URL can't be mapped to `host/path`.
     */
    fun webBase(remoteUrl: String?): Pair<String?, Boolean> {
        val u = remoteUrl?.trim().orEmpty()
        if (u.isEmpty()) return null to false
        val hostPath = when {
            u.startsWith("git@") -> u.removePrefix("git@").replaceFirst(":", "/")
            u.startsWith("ssh://") -> u.removePrefix("ssh://").substringAfterLast('@')
            u.startsWith("https://") -> u.removePrefix("https://").substringAfterLast('@')
            u.startsWith("http://") -> u.removePrefix("http://").substringAfterLast('@')
            else -> return null to false
        }
        val clean = hostPath.removeSuffix(".git").trimEnd('/')
        if ('/' !in clean) return null to false
        val host = clean.substringBefore('/').substringBefore(':')   // drop any :port
        val path = clean.substringAfter('/')
        val isGitHub = host.equals("github.com", true) || host.startsWith("github.", true)
        return "https://$host/$path" to isGitHub
    }

    /** Compact "2d", "3h", "5m", "just now" from a millisecond span. */
    fun compactDuration(ms: Long): String {
        val s = abs(ms) / 1000
        return when {
            s < 60 -> "under a minute"
            s < 3600 -> "${s / 60}m"
            s < 86_400 -> "${s / 3600}h"
            s < 86_400 * 7 -> "${s / 86_400}d"
            s < 86_400 * 30 -> "${s / (86_400 * 7)}w"
            else -> "${s / (86_400 * 30)}mo"
        }
    }

    /** Snooze duration presets for the menu: label to millis (null millis = until resumed). */
    val SNOOZE_PRESETS: List<Pair<String, Long?>> = listOf(
        "1 hour" to 3_600_000L,
        "4 hours" to 4 * 3_600_000L,
        "1 day" to 86_400_000L,
        "3 days" to 3 * 86_400_000L,
        "1 week" to 7 * 86_400_000L,
        "Until I resume" to null,
    )

    /** Reminder due presets: label to millis-from-now (null = no due date). */
    val REMINDER_PRESETS: List<Pair<String, Long?>> = listOf(
        "Today" to 6 * 3_600_000L,
        "Tomorrow" to 86_400_000L,
        "In 3 days" to 3 * 86_400_000L,
        "Next week" to 7 * 86_400_000L,
        "No date" to null,
    )
}
