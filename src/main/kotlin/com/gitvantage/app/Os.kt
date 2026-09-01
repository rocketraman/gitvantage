// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.app

/**
 * Which desktop this copy is running on, resolved once.
 *
 * Only the launcher code in [Actions] and [Programs] branches on it, and only because "open this
 * in a terminal / a file manager / a browser" has no portable spelling — every platform answers it
 * with a different program, and two of the three cannot even report failure the same way.
 */
enum class Os {
    LINUX,
    MAC,
    WINDOWS,
    ;

    companion object {
        /**
         * [MAC] is tested before [WINDOWS] deliberately: the substring "win" is inside "darwin",
         * so the obvious `contains("win")` first would classify a Mac as Windows the day some JVM
         * spells `os.name` that way. Anything unrecognised falls to [LINUX], which is where the
         * freedesktop tools this app already relied on live.
         */
        val current: Os = from(System.getProperty("os.name").orEmpty())

        /** Split out from [current] so the ordering above can actually be tested — the property it
         *  reads is fixed for the life of the process. */
        internal fun from(osName: String): Os = osName.lowercase().let {
            when {
                it.contains("mac") || it.contains("darwin") -> MAC
                it.contains("win") -> WINDOWS
                else -> LINUX
            }
        }
    }
}
