// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.app

import dev.nucleusframework.notification.InterruptionLevel
import dev.nucleusframework.notification.common.NotificationBuilder
import dev.nucleusframework.notification.common.NotificationManager
import dev.nucleusframework.notification.common.notification
import dev.nucleusframework.notification.linux.Urgency
import dev.nucleusframework.notification.windows.ToastDuration
import dev.nucleusframework.notification.windows.ToastScenario

/**
 * Thin wrapper over Nucleus's cross-platform notifications. Safe to call regardless of
 * platform support — everything no-ops (and never throws) when notifications aren't
 * available, so callers don't have to guard.
 */
object Notify {
    private var available = false

    /**
     * How much of the user's attention a notification is entitled to. Callers choose one of these
     * instead of naming platform hints, so [hints] stays the only place that knows how our three
     * tiers land on a freedesktop daemon, Notification Center and a Windows toast.
     */
    enum class Kind {
        /** Routine repo news — worth a glance, not worth an interruption. */
        INFO,

        /** Something the user asked to be told about loudly (e.g. a repo they flagged important). */
        ALERT,

        /** A user-set reminder: actionable, and re-fires hourly until it's marked Done. */
        REMINDER,
    }

    fun init() {
        available = runCatching {
            NotificationManager.initialize()
            NotificationManager.isAvailable()
        }.getOrDefault(false)
    }

    /**
     * Fire a desktop notification. [onClick] runs on activation; [buttons] are (label, action)
     * pairs. [kind] sets the per-platform priority hints. No-op if unsupported.
     */
    fun show(
        title: String,
        message: String,
        kind: Kind = Kind.INFO,
        onClick: (() -> Unit)? = null,
        buttons: List<Pair<String, () -> Unit>> = emptyList(),
    ) {
        if (!available) return
        runCatching {
            notification(title = title, message = message, onActivated = onClick) {
                buttons.forEach { (label, action) -> button(label, action) }
                hints(kind)
            }.send()
        }
    }

    /**
     * The one mapping from [Kind] to platform hints, via Nucleus's per-platform DSL scopes. Each
     * scope only applies on its own OS, so all three are always declared and the unused two cost
     * nothing.
     *
     * macOS deliberately tops out at [InterruptionLevel.ACTIVE]: TIME_SENSITIVE would punch through
     * Focus modes, which is more than a "commit your work" nudge has earned (and needs an
     * entitlement we don't ship anyway).
     */
    private fun NotificationBuilder.hints(kind: Kind) = when (kind) {
        Kind.INFO -> {
            linux { urgency = Urgency.LOW }
            macos { interruptionLevel = InterruptionLevel.PASSIVE }
            windows { duration = ToastDuration.SHORT }
        }

        Kind.ALERT -> {
            linux { urgency = Urgency.NORMAL }
            macos { interruptionLevel = InterruptionLevel.ACTIVE }
            windows { duration = ToastDuration.LONG }
        }

        Kind.REMINDER -> {
            // transient = false is the daemon default, but say it out loud: a reminder that scrolled
            // past while the user was away still has to be findable in KDE's notification history.
            linux { urgency = Urgency.NORMAL; transient = false }
            macos { interruptionLevel = InterruptionLevel.ACTIVE }
            windows { scenario = ToastScenario.REMINDER }
        }
    }
}
