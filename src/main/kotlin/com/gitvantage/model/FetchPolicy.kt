// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.model

/**
 * How often each repo's `git fetch` is allowed to run in the background.
 *
 * Every registered repo used to be fetched every five minutes. That is ~12 network round trips
 * per repo per hour whether or not anything changed — for a dashboard of twenty repos, several
 * thousand requests a day to discover, almost always, nothing. The rates here are graded by how
 * recently a repo showed any sign of life, which cuts that by roughly two orders of magnitude
 * while keeping the repos you are actually working in nearly as current as before.
 *
 * The repo whose detail pane is open is not covered by any of this: it is fetched on open and
 * every five minutes while it stays open, because it is the one the user is looking at.
 *
 * Pure and clock-injected so the tiers can be tested without waiting hours for them.
 */
object FetchPolicy {

    /** Background fetch intervals, in epoch millis, by how stale the repo's last activity is. */
    private const val HOUR = 3_600_000L
    private const val DAY = 24L * HOUR

    /**
     * How long to wait between background fetches of a repo whose last activity was [activityMs]
     * epoch millis ago, or null to not auto-fetch it at all.
     *
     * Null is not "never": the detail pane and the Refresh button both fetch regardless, and are
     * the way a dormant repo gets looked at again. It only means the app stops spending traffic on
     * a repo nobody has touched in a month unless asked to.
     */
    fun intervalMs(activityMs: Long?, now: Long): Long? {
        // Never any activity recorded (a fresh clone, or a repo with no commits) — treat as active
        // rather than dormant. Guessing "dormant" here would leave a just-added repo unfetched.
        val age = if (activityMs == null) 0L else now - activityMs
        return when {
            age < 3 * DAY -> HOUR
            age < 7 * DAY -> 2 * HOUR
            age < 30 * DAY -> DAY
            else -> null
        }
    }

    /** Whether a repo last fetched at [lastFetchedMs] is due, given its [activityMs]. */
    fun isDue(activityMs: Long?, lastFetchedMs: Long?, now: Long): Boolean {
        val interval = intervalMs(activityMs, now) ?: return false
        // Never fetched: due immediately. This is also what makes a newly added repo fetch once
        // without a special case for it.
        val last = lastFetchedMs ?: return true
        return now - last >= interval
    }

    /**
     * When a repo last showed activity, as epoch millis — the newest of anything that means "this
     * repo is live": a local commit, uncommitted work in the tree, or a fetch that brought new
     * upstream commits in.
     *
     * The last of those is the one that keeps the tiers honest. Local commit dates measure *your*
     * activity, but what the interval is really predicting is whether the *upstream* has moved, and
     * those come apart precisely where it hurts: a repo you last touched six weeks ago that your
     * team pushes to daily would be tiered dormant, silencing the "you are 200 behind" that is the
     * most useful thing the dashboard could say about it. Folding in the last upstream advance
     * makes the tier self-correcting — one fetch that finds something promotes the repo back.
     *
     * [lastCommitEpochSeconds] is git's committer date and is in *seconds* (`%ct`); the other two
     * are millis. They are not interchangeable and the conversion is why this takes them apart.
     */
    fun activityMs(
        lastCommitEpochSeconds: Long?,
        dirtySinceMs: Long?,
        lastUpstreamAdvanceMs: Long?,
    ): Long? = listOfNotNull(
        lastCommitEpochSeconds?.times(1000),
        dirtySinceMs,
        lastUpstreamAdvanceMs,
    ).maxOrNull()
}
