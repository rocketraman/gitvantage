// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.model

import de.infix.testBalloon.framework.core.testSuite

/**
 * The background fetch tiers.
 *
 * Worth pinning precisely because the cost of getting them wrong is invisible: too eager and the
 * app quietly floods every upstream it knows about, too lazy and it silently reports month-old
 * ahead/behind counts as though they were current. Neither shows up as a failure anywhere else.
 */
val FetchScheduling by testSuite {

    val now = 1_800_000_000_000L // fixed clock; the policy takes it as a parameter for this reason
    val hour = 3_600_000L
    val day = 24 * hour
    fun daysAgo(n: Long) = now - n * day

    // --- tiers --------------------------------------------------------------------------------

    test("a repo touched today is fetched hourly") {
        assert(FetchPolicy.intervalMs(daysAgo(0), now) == hour)
        assert(FetchPolicy.intervalMs(daysAgo(2), now) == hour)
    }

    test("a repo touched within the week is fetched every two hours") {
        assert(FetchPolicy.intervalMs(daysAgo(4), now) == 2 * hour)
        assert(FetchPolicy.intervalMs(daysAgo(6), now) == 2 * hour)
    }

    test("a repo untouched for over a week is fetched daily") {
        assert(FetchPolicy.intervalMs(daysAgo(8), now) == day)
        assert(FetchPolicy.intervalMs(daysAgo(29), now) == day)
    }

    test("a repo untouched for over a month is not auto-fetched at all") {
        assert(FetchPolicy.intervalMs(daysAgo(31), now) == null)
        assert(FetchPolicy.intervalMs(daysAgo(400), now) == null)
    }

    test("a repo with no recorded activity is treated as active, not dormant") {
        // A fresh clone, or one with no commits. Guessing "dormant" would leave a repo the user
        // just added unfetched until they opened it.
        assert(FetchPolicy.intervalMs(null, now) == hour)
    }

    // --- due-ness -----------------------------------------------------------------------------

    test("a repo that has never been fetched is due immediately") {
        assert(FetchPolicy.isDue(daysAgo(1), lastFetchedMs = null, now = now))
    }

    test("a repo is due only once its own interval has elapsed") {
        val active = daysAgo(1) // hourly tier
        assert(!FetchPolicy.isDue(active, lastFetchedMs = now - 59 * 60_000, now = now))
        assert(FetchPolicy.isDue(active, lastFetchedMs = now - hour, now = now))
    }

    test("tiers are what decide due-ness, not a shared interval") {
        // The same 3-hour-old fetch: due for the daily tier's neighbours, not for the two-hourly.
        val threeHoursAgo = now - 3 * hour
        assert(FetchPolicy.isDue(daysAgo(1), threeHoursAgo, now)) // hourly → due
        assert(FetchPolicy.isDue(daysAgo(5), threeHoursAgo, now)) // 2-hourly → due
        assert(!FetchPolicy.isDue(daysAgo(10), threeHoursAgo, now)) // daily → not yet
    }

    test("a dormant repo is never due, however long ago it was fetched") {
        assert(!FetchPolicy.isDue(daysAgo(60), lastFetchedMs = daysAgo(59), now = now))
        assert(!FetchPolicy.isDue(daysAgo(60), lastFetchedMs = null, now = now))
    }

    // --- what counts as activity --------------------------------------------------------------

    test("activity is the newest of local commits, dirty tree and upstream movement") {
        val commitSecs = daysAgo(40) / 1000 // git's %ct is seconds, the others are millis
        assert(FetchPolicy.activityMs(commitSecs, null, null) == daysAgo(40))
        // Uncommitted work is activity that produces no commit date of its own.
        assert(FetchPolicy.activityMs(commitSecs, daysAgo(1), null) == daysAgo(1))
        assert(FetchPolicy.activityMs(commitSecs, null, daysAgo(2)) == daysAgo(2))
    }

    test("upstream movement rescues a repo the user has not touched in months") {
        // The case local-activity-only tiering gets wrong: untouched since spring, but the team
        // pushes daily. Tiered on the commit date alone this repo goes silent exactly when
        // "you are far behind" is the most useful thing the dashboard could say.
        val staleCommit = daysAgo(90) / 1000
        assert(FetchPolicy.intervalMs(FetchPolicy.activityMs(staleCommit, null, null), now) == null)

        val withUpstream = FetchPolicy.activityMs(staleCommit, null, daysAgo(1))
        assert(FetchPolicy.intervalMs(withUpstream, now) == hour)
    }

    test("seconds and millis are not interchangeable") {
        // Passing git's committer date through unconverted would put every repo in 1970 and
        // switch auto-fetch off for the entire dashboard.
        val commitSecs = daysAgo(1) / 1000
        assert(FetchPolicy.activityMs(commitSecs, null, null) == daysAgo(1))
    }
}
