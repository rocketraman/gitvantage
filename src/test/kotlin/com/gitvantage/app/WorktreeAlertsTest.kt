// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.app

import com.gitvantage.model.Worktree
import com.gitvantage.model.WorktreeAlert
import com.gitvantage.model.WorktreeAlerts
import de.infix.testBalloon.framework.core.testSuite

/**
 * The inheritance a worktree's alerts resolve through, and what that decides.
 *
 * Three inputs — the worktree's own snooze, its per-alert override, and whether the parent's alerts
 * are running — collapsing to one boolean per alert, which then decides whether the worktree's
 * unlanded work pulls the parent row into Attention. Worth pinning because the precedence is the
 * part a reader has to take on trust: an override beats the parent, and a snooze beats the override,
 * so "Unlanded work: On" on a snoozed worktree stays quiet rather than shouting through the snooze.
 *
 * Pure, and deliberately checked without a palette attached: nothing here reads [Tokens], which is
 * what keeps it runnable with no display and no registry.
 */
val WorktreeAlertInheritance by testSuite {

    fun tree(dirty: Int = 0, unmerged: Int = 0) = Worktree(
        path = "/w/side", head = "abc1234", branch = "side", bare = false,
        locked = false, lockReason = null, prunable = false, prunableReason = null,
        isMain = false, dirtyCount = dirty, unmerged = unmerged,
    )

    fun view(
        alerts: WorktreeAlerts = WorktreeAlerts(),
        snoozed: Boolean = false,
        parentAlertsOn: Boolean = true,
        dirty: Int = 0,
        unmerged: Int = 0,
    ) = WorktreeView(
        wt = tree(dirty, unmerged), repoId = "/w", alerts = alerts,
        snoozed = snoozed, snoozedFor = if (snoozed) "2d" else null,
        parentAlertsOn = parentAlertsOn,
    )

    test("an unset alert follows the parent, both ways") {
        assert(view(parentAlertsOn = true).effective(WorktreeAlert.AGING))
        assert(!view(parentAlertsOn = false).effective(WorktreeAlert.AGING))
    }

    test("an override beats the parent in both directions") {
        // Off on a live parent — the case the feature exists for: one abandoned checkout silenced
        // without silencing the repo it belongs to.
        assert(!view(WorktreeAlerts(unlanded = false), parentAlertsOn = true).effective(WorktreeAlert.UNLANDED))
        // And On through a snoozed parent, which is the same control used the other way.
        assert(view(WorktreeAlerts(unlanded = true), parentAlertsOn = false).effective(WorktreeAlert.UNLANDED))
    }

    test("overrides are per alert — setting one leaves the others inheriting") {
        val v = view(WorktreeAlerts(unlanded = false), parentAlertsOn = true)
        assert(!v.effective(WorktreeAlert.UNLANDED))
        assert(v.effective(WorktreeAlert.AGING))
        assert(v.effective(WorktreeAlert.REMINDERS))
        assert(v.alerts.overridden == listOf(WorktreeAlert.UNLANDED))
    }

    test("the worktree's own snooze beats an explicit On") {
        val v = view(WorktreeAlerts(unlanded = true), snoozed = true)
        assert(!v.effective(WorktreeAlert.UNLANDED))
        // Still editable underneath, which is why the popover dims the pills rather than disabling
        // them: the override is intact and resumes with the snooze.
        assert(v.alerts[WorktreeAlert.UNLANDED] == true)
    }

    test("unlanded work rolls up to the parent only while its alert is on") {
        assert(view(dirty = 1).rollsUp)
        assert(view(unmerged = 2).rollsUp)
        assert(!view(WorktreeAlerts(unlanded = false), dirty = 1).rollsUp)
        assert(!view(snoozed = true, dirty = 1).rollsUp)
        // Nothing in it to roll up in the first place, however the alerts are set.
        assert(!view(WorktreeAlerts(unlanded = true)).rollsUp)
    }

    test("the gear indicator means overridden or self-snoozed, and nothing else") {
        assert(!view().overridden)
        assert(view(WorktreeAlerts(aging = true)).overridden)
        assert(view(snoozed = true).overridden)
        // A worktree holding work is not "customised" — that's the state, not a choice about it.
        assert(!view(dirty = 3).overridden)
    }

    test("an all-inherit entry is the default, so it is never stored") {
        assert(WorktreeAlerts().isDefault)
        assert(WorktreeAlerts(aging = null, unlanded = null, reminders = null).isDefault)
        // Clearing the last override collapses back to the default rather than leaving the worktree
        // permanently marked as customised.
        assert(WorktreeAlerts(aging = true).with(WorktreeAlert.AGING, null).isDefault)
        // A snooze alone is not the default: something is in force even with no override set.
        assert(!WorktreeAlerts(snoozeUntilEpoch = 1L).isDefault)
        assert(!WorktreeAlerts(unlanded = false).isDefault)
    }

    test("with() replaces one alert and leaves the snooze and the others alone") {
        val a = WorktreeAlerts(aging = true, snoozeUntilEpoch = 99L).with(WorktreeAlert.REMINDERS, false)
        assert(a.aging == true)
        assert(a.reminders == false)
        assert(a.unlanded == null)
        assert(a.snoozeUntilEpoch == 99L)
    }
}
