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
import org.freedesktop.dbus.exceptions.AddressResolvingException
import org.freedesktop.dbus.exceptions.InvalidBusAddressException
import org.freedesktop.dbus.interfaces.Properties
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.system.exitProcess

/**
 * Functional checks for the subsystems that fail silently.
 *
 * Runs on the development classpath (see the smokeTestSubsystems task, wired into `check`). It was
 * originally a release gate executed against the jars inside the jpackage image, to prove ProGuard
 * had not stripped them; releases are now GraalVM native images built without ProGuard, so it no
 * longer says anything about the shipped artifact — native-image does its own reachability
 * analysis, and covering that would mean running these checks inside the image itself.
 *
 * It still earns its place: static analysis provably cannot catch this class of failure, and
 * nothing else exercises these paths. Every instance that reached a release was silent — a
 * subsystem reported success and then simply did nothing, because the shrinker had removed classes
 * only reachable from native code or a ServiceLoader:
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
    println("Smoke-testing the application's subsystems…")

    checkFsWatcher()
    checkFsWatcherThroughSymlink()
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

    // Watch the *canonical* path. macOS hands out temp dirs under /var/folders, where /var is a
    // symlink to /private/var, and this check exists to prove the native callback path delivers
    // events in the packaged image — not to test how the watcher handles symlinks. Watching the
    // unresolved path conflated the two: on macOS it silently delivered nothing, which reads here
    // as a stripped callback. Keep that a separate concern, and a separate bug report.
    val dir = createTempDirectory("gitvantage-smoke").toRealPath()
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
    // Native events arrive asynchronously; give them room before declaring failure. The old 2s
    // budget was tuned on inotify, which is effectively immediate. macOS FSEvents coalesces and
    // can sit on a change for seconds, so a slow delivery there is indistinguishable from none —
    // hence the wider window. It only costs wall-clock on a run that is already failing.
    repeat(100) { if (events == 0) delay(100) }
    collector.cancel()
    runCatching { registration.close() }
    runCatching { dir.toFile().deleteRecursively() }

    if (events > 0) {
        record(Status.PASS, name, "$events event(s) delivered")
    } else {
        record(
            Status.FAIL, name,
            "registered successfully but delivered NO events after 10s for $dir " +
                "— the native callback path is stripped",
        )
    }
}

/**
 * A repo added via a symlinked path must still deliver events.
 *
 * The watcher matches incoming events against the root it was given, by path prefix. macOS reports
 * events with canonical paths, so a symlinked root never matches its own events: registration
 * succeeds and nothing is ever delivered. It is silent, and it is easy to hit — /tmp and /var are
 * both symlinks on macOS. AppState.watchRootFor resolves the path before watching; this is the
 * check that the technique actually holds on the platform that needs it.
 *
 * Linux and Windows echo back the path they were handed, so they pass either way — which is exactly
 * why this went unnoticed until the release build could produce a macOS image.
 */
private fun checkFsWatcherThroughSymlink() = runBlocking {
    val name = "filesystem watcher via symlinked path"
    if (!runCatching { FsWatchers.isSupported() }.getOrDefault(false)) {
        record(Status.SKIP, name, "not supported on this platform")
        return@runBlocking
    }
    val base = createTempDirectory("gitvantage-smoke-link").toRealPath()
    val target = Files.createDirectory(base.resolve("target"))
    val link = runCatching { Files.createSymbolicLink(base.resolve("link"), target) }.getOrNull()
    if (link == null) {
        record(Status.SKIP, name, "cannot create symlinks on this machine")
        runCatching { base.toFile().deleteRecursively() }
        return@runBlocking
    }

    val watcher = runCatching { FsWatchers.create() }.getOrNull()
    if (watcher == null) {
        record(Status.FAIL, name, "FsWatchers.create() returned null / threw")
        runCatching { base.toFile().deleteRecursively() }
        return@runBlocking
    }
    var events = 0
    val collector = launch(Dispatchers.IO) {
        runCatching { watcher.events.collect { events++ } }
    }
    delay(300)
    // Resolve exactly as AppState.watchRootFor does — the whole point is that this is what makes a
    // symlinked repo work. Watching `link` unresolved is what silently fails on macOS.
    val registration = runCatching { watcher.watch(link.toRealPath(), true, "smoke-link") }.getOrNull()
    if (registration == null) {
        collector.cancel()
        record(Status.FAIL, name, "watch() returned null / threw")
        runCatching { base.toFile().deleteRecursively() }
        return@runBlocking
    }
    delay(300)
    // Write *through the symlink*, the way a user's tooling would.
    repeat(3) { i ->
        Files.writeString(link.resolve("smoke$i.txt"), "smoke $i")
        delay(200)
    }
    repeat(100) { if (events == 0) delay(100) }
    collector.cancel()
    runCatching { registration.close() }
    runCatching { base.toFile().deleteRecursively() }

    if (events > 0) {
        record(Status.PASS, name, "$events event(s) delivered")
    } else {
        record(
            Status.FAIL, name,
            "a repo added by a symlinked path registers but delivers NO events — real-time " +
                "refresh is silently dead for it",
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
    // Whether a session bus exists is dbus-java's call, not ours. It resolves the address from the
    // DBUS_SESSION_BUS_ADDRESS property/env and then from $HOME/.dbus/session-bus/<machine-id>-<DISPLAY>,
    // and never consults $XDG_RUNTIME_DIR. Probing for $XDG_RUNTIME_DIR/bus therefore reported a bus on
    // CI runners that have a systemd user session but no bus address, which turned an environmental
    // skip into a hard failure. So don't predict the outcome — connect, and read it off the failure.
    //
    // Only an unresolvable/malformed *address* means "no bus here". A failure to connect to an address
    // that did resolve is a real defect and must stay a FAIL: that is exactly how a missing
    // jdk.security.auth presents, since dbus-java's SASL EXTERNAL handshake needs it to read the
    // caller's uid, and losing it kills every D-Bus connection — the portal included.
    val connection = try {
        DBusConnectionBuilder.forSessionBus().build()
    } catch (e: AddressResolvingException) {
        record(Status.SKIP, name, "no session D-Bus on this machine ($e)")
        return
    } catch (e: InvalidBusAddressException) {
        record(Status.SKIP, name, "session D-Bus address is unusable ($e)")
        return
    } catch (e: Exception) {
        record(Status.FAIL, name, "session D-Bus resolved but would not connect: $e")
        return
    }
    val result = runCatching {
        connection.use {
            val portal = it.getRemoteObject(
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
