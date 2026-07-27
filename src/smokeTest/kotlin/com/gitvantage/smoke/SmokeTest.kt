// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.smoke

import dev.nucleusframework.fswatcher.FsWatchers
import dev.nucleusframework.notification.common.NotificationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.Properties
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.system.exitProcess

/**
 * Functional smoke test for the **packaged** application.
 *
 * Compiled against the normal dependencies but *run against the jars inside the release image*
 * (see the smokeTestReleaseImage task), so it exercises the ProGuard-minified code that actually
 * ships rather than the development classpath.
 *
 * This exists because static analysis provably cannot catch the failures that kept reaching
 * releases. Every one of them was silent — a subsystem reported success and then simply did
 * nothing, because ProGuard had removed classes only reachable from native code or a
 * ServiceLoader:
 *
 *  - the filesystem watcher returned a valid registration and never delivered an event;
 *  - the XDG portal failed to connect, so the native file chooser degraded to a Swing dialog.
 *
 * Neither has a dangling reference to find. The only way to know is to run them.
 *
 * Checks that need a session D-Bus are skipped (not failed) when one isn't present, so this is
 * usable on a bare CI runner; the watcher check needs nothing and always runs.
 */
private enum class Status { PASS, FAIL, SKIP }

private val results = mutableListOf<Triple<Status, String, String>>()

private fun record(status: Status, name: String, detail: String) {
    results += Triple(status, name, detail)
    println("  [${status.name.padEnd(4)}] $name — $detail")
}

fun main() {
    println("Smoke-testing the packaged release image…")

    checkFsWatcher()
    checkXdgPortal()
    checkNotifications()

    val failed = results.filter { it.first == Status.FAIL }
    println()
    println(
        "smoke test: ${results.count { it.first == Status.PASS }} passed, " +
            "${failed.size} failed, ${results.count { it.first == Status.SKIP }} skipped",
    )
    if (failed.isNotEmpty()) {
        println()
        println("The packaged app is broken even though it built cleanly. Each failure below works")
        println("in a development build — which means ProGuard removed something only reachable")
        println("reflectively or from native code. Add a -keep rule in packaging/proguard-rules.pro.")
        exitProcess(1)
    }
}

/**
 * The watcher is a native library that calls back into Java. Its callback interface and the
 * structs mapped onto native memory are invisible to the shrinker, so they were stripped and no
 * event could ever be delivered — while every API call still reported success.
 */
private fun checkFsWatcher() = runBlocking {
    val name = "filesystem watcher"
    if (!runCatching { FsWatchers.isSupported() }.getOrDefault(false)) {
        record(Status.SKIP, name, "not supported on this platform")
        return@runBlocking
    }
    val watcher = runCatching { FsWatchers.create() }.getOrNull()
    if (watcher == null) {
        record(Status.FAIL, name, "FsWatchers.create() returned null / threw")
        return@runBlocking
    }

    val dir = createTempDirectory("gitvantage-smoke")
    var events = 0
    val collector = launch(Dispatchers.IO) {
        runCatching { watcher.events.collect { events++ } }
    }
    delay(300)
    val registration = runCatching { watcher.watch(dir, true, "smoke") }.getOrNull()
    if (registration == null) {
        collector.cancel()
        record(Status.FAIL, name, "watch() returned null / threw")
        return@runBlocking
    }
    delay(300)
    repeat(3) { i ->
        Files.writeString(dir.resolve("smoke$i.txt"), "smoke $i")
        delay(200)
    }
    // Native events arrive asynchronously; give them room before declaring failure.
    repeat(20) { if (events == 0) delay(100) }
    collector.cancel()
    runCatching { registration.close() }
    runCatching { dir.toFile().deleteRecursively() }

    if (events > 0) {
        record(Status.PASS, name, "$events event(s) delivered")
    } else {
        record(
            Status.FAIL, name,
            "registered successfully but delivered NO events — the native callback path is stripped",
        )
    }
}

/**
 * FileKit probes the portal over D-Bus and silently falls back to a Swing dialog when the probe
 * fails, so a broken portal shows up only as an ugly file chooser.
 */
private fun checkXdgPortal() {
    val name = "XDG desktop portal (native file chooser)"
    if (!System.getProperty("os.name").lowercase().contains("linux")) {
        record(Status.SKIP, name, "not Linux")
        return
    }
    // XDG_RUNTIME_DIR is /run/user/<uid>, where the session bus socket lives.
    val hasSessionBus = !System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank() ||
        System.getenv("XDG_RUNTIME_DIR")?.let { java.io.File(it, "bus").exists() } == true
    if (!hasSessionBus) {
        record(Status.SKIP, name, "no session D-Bus on this machine")
        return
    }
    val result = runCatching {
        DBusConnectionBuilder.forSessionBus().build().use { connection ->
            val portal = connection.getRemoteObject(
                "org.freedesktop.portal.Desktop",
                "/org/freedesktop/portal/desktop",
                Properties::class.java,
            )
            portal.Get<Any>("org.freedesktop.portal.FileChooser", "version")
        }
    }
    result.fold(
        onSuccess = { record(Status.PASS, name, "FileChooser portal version $it") },
        onFailure = { record(Status.FAIL, name, "portal unreachable: $it") },
    )
}

/** Desktop notifications also go over D-Bus; unavailable on a headless runner is fine. */
private fun checkNotifications() {
    val name = "desktop notifications"
    val available = runCatching {
        NotificationManager.initialize()
        NotificationManager.isAvailable()
    }
    available.fold(
        onSuccess = {
            if (it) record(Status.PASS, name, "available")
            else record(Status.SKIP, name, "no notification service on this machine")
        },
        onFailure = { record(Status.FAIL, name, "initialisation threw: $it") },
    )
}
