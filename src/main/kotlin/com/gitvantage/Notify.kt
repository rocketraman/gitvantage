// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

import dev.nucleusframework.notification.common.NotificationManager
import dev.nucleusframework.notification.common.notification

/**
 * Thin wrapper over Nucleus's cross-platform notifications. Safe to call regardless of
 * platform support — everything no-ops (and never throws) when notifications aren't
 * available, so callers don't have to guard.
 */
object Notify {
    private var available = false

    fun init() {
        available = runCatching {
            NotificationManager.initialize()
            NotificationManager.isAvailable()
        }.getOrDefault(false)
    }

    /**
     * Fire a desktop notification. [onClick] runs on activation; [buttons] are (label, action)
     * pairs — action notifications also persist in KDE's history (unlike transient ones).
     * No-op if unsupported.
     */
    fun show(
        title: String,
        message: String,
        onClick: (() -> Unit)? = null,
        buttons: List<Pair<String, () -> Unit>> = emptyList(),
    ) {
        if (!available) return
        runCatching {
            notification(title = title, message = message, onActivated = onClick) {
                buttons.forEach { (label, action) -> button(label, action) }
            }.send()
        }
    }
}
