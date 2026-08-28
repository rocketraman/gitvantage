// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.smoke

import dev.nucleusframework.darkmodedetector.NoopDarkModeDetector
import dev.nucleusframework.darkmodedetector.getPlatformDarkModeDetector
import dev.nucleusframework.fswatcher.FsWatchers
import dev.nucleusframework.notification.common.NotificationManager
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.AddressResolvingException
import org.freedesktop.dbus.exceptions.InvalidBusAddressException
import org.freedesktop.dbus.interfaces.Properties
import kotlin.time.Duration.Companion.milliseconds

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

/**
 * Runs every check and returns a process exit code: 0 if nothing failed, 1 otherwise.
 *
 * Invoked two ways. `GitVantage --smoke-test` runs it inside the shipped native binary, which is
 * the point of the whole exercise; the smokeTestSubsystems Gradle task runs it on the development
 * classpath for fast local feedback. Only the first says anything about a release.
 */
fun runSmokeChecks(): Int {
    println("Smoke-testing the application's subsystems…")

    checkFsWatcher()
    checkFsWatcherThroughSymlink()
    checkXdgPortal()
    checkThemeDetector()
    checkXdgFileChooserProxy()
    checkMacOsFileChooserBridge()
    checkNotifications()

    val failed = results.filter { it.first == Status.FAIL }
    println()
    println(
        "smoke test: ${results.count { it.first == Status.PASS }} passed, " +
            "${failed.size} failed, ${results.count { it.first == Status.SKIP }} skipped",
    )
    if (failed.isEmpty()) return 0

    println()
    println("The app is broken even though it built cleanly. Each failure below works in a")
    println("development build, which means the closed-world analysis dropped something reachable")
    println("only reflectively, via a dynamic proxy, through a ServiceLoader, or from native code.")
    println("Register it in the GraalVM reachability metadata rather than working around it here.")
    return 1
}

fun main() {
    exitProcess(runSmokeChecks())
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
    delay(300.milliseconds)
    val registration = runCatching { watcher.watch(dir, true, "smoke") }.getOrNull()
    if (registration == null) {
        collector.cancel()
        record(Status.FAIL, name, "watch() returned null / threw")
        return@runBlocking
    }
    delay(300.milliseconds)
    repeat(3) { i ->
        Files.writeString(dir.resolve("smoke$i.txt"), "smoke $i")
        delay(200.milliseconds)
    }
    // Native events arrive asynchronously; give them room before declaring failure. The old 2s
    // budget was tuned on inotify, which is effectively immediate. macOS FSEvents coalesces and
    // can sit on a change for seconds, so a slow delivery there is indistinguishable from none —
    // hence the wider window. It only costs wall-clock on a run that is already failing.
    repeat(100) { if (events == 0) delay(100.milliseconds) }
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
    delay(300.milliseconds)
    // Resolve exactly as AppState.watchRootFor does — the whole point is that this is what makes a
    // symlinked repo work. Watching `link` unresolved is what silently fails on macOS.
    val registration = runCatching { watcher.watch(link.toRealPath(), true, "smoke-link") }.getOrNull()
    if (registration == null) {
        collector.cancel()
        record(Status.FAIL, name, "watch() returned null / threw")
        runCatching { base.toFile().deleteRecursively() }
        return@runBlocking
    }
    delay(300.milliseconds)
    // Write *through the symlink*, the way a user's tooling would.
    repeat(3) { i ->
        Files.writeString(link.resolve("smoke$i.txt"), "smoke $i")
        delay(200.milliseconds)
    }
    repeat(100) { if (events == 0) delay(100.milliseconds) }
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
    // that did resolve is a real defect and must stay a FAIL: dbus-java's SASL EXTERNAL handshake
    // reads the caller's uid through com.sun.security.auth.module.UnixSystem, and if that is not
    // reachable every D-Bus connection dies during authentication — the portal included.
    //
    // That used to mean a missing jdk.security.auth in the jlink'd runtime, which is why the module
    // was named in nativeDistributions. There is no jlink runtime now; in a native image the same
    // failure comes from UnixSystem's JNI entry points not being registered, and it presents
    // identically here.
    val connection = runCatching {
        DBusConnectionBuilder.forSessionBus().build()
    }.getOrElse { e ->
        when (e) {
            is AddressResolvingException -> record(Status.SKIP, name, "no session D-Bus on this machine ($e)")
            is InvalidBusAddressException -> record(Status.SKIP, name, "session D-Bus address is unusable ($e)")
            else -> record(Status.FAIL, name, "session D-Bus resolved but would not connect: $e")
        }
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

/**
 * The OS dark-mode detector's native library.
 *
 * The desktop's light/dark preference is read through a JNI library Nucleus bundles and extracts
 * at runtime (`libnucleus_linux_theme.so` and its macOS/Windows siblings), with the change signal
 * arriving back through a callback the image has to keep reachable from native code. Nothing about
 * a failure here is visible where a user would see it: the app simply wears light forever on a
 * dark desktop — the exact bug that shipped as issue #9, back when this went through Skiko.
 *
 * The honest limit: a PASS proves the library loaded and answered, not that the answer is right. A
 * session with no portal answers "light" perfectly successfully.
 */
private fun checkThemeDetector() {
    val name = "OS dark-mode detector (JNI)"
    val detector = runCatching { getPlatformDarkModeDetector() }.getOrElse {
        record(Status.FAIL, name, "detector would not initialize: $it")
        return
    }
    if (detector === NoopDarkModeDetector) {
        record(Status.SKIP, name, "no detector for this platform")
        return
    }
    runCatching { detector.isDark() }.fold(
        onSuccess = { record(Status.PASS, name, "reports the desktop prefers ${if (it) "dark" else "light"}") },
        onFailure = { record(Status.FAIL, name, "native library loaded but would not answer: $it") },
    )
}

/** Desktop notifications also go over D-Bus; unavailable on a headless runner is fine. */

/**
 * FileKit's own portal interface, as distinct from dbus-java.
 *
 * [checkXdgPortal] opens its *own* D-Bus connection and proxies `Properties`, so it proves
 * dbus-java's transport and proxy machinery work — and nothing about FileKit. If FileKit's XDG
 * classes are missing, or its interface has no dynamic-proxy registration in the native image, the
 * "Add repo" chooser silently degrades to the Swing dialog: no exception, no dangling service
 * registration, nothing any static gate can see. That silent fallback is a bug that actually
 * shipped, which is why it gets its own check.
 *
 * Resolved by name rather than referenced statically, so this file does not drag FileKit's XDG
 * implementation into the image by merely mentioning it. Note the honest limit: a successful proxy
 * proves the class ships and its proxy registration is present, not that FileKit's own call path
 * into it survived. Driving the chooser end to end is the only thing that would prove that.
 */
private fun checkXdgFileChooserProxy() {
    val name = "FileKit XDG file chooser interface"
    if (!System.getProperty("os.name").lowercase().contains("linux")) {
        record(Status.SKIP, name, "not Linux")
        return
    }
    val iface = runCatching {
        Class.forName("io.github.vinceglb.filekit.dialogs.platform.xdg.FileChooserDbusInterface")
    }.getOrNull()
    if (iface == null) {
        record(
            Status.FAIL, name,
            "io.github.vinceglb.filekit.dialogs.platform.xdg.FileChooserDbusInterface is not in the " +
                "image — the file chooser will silently fall back to the Swing dialog",
        )
        return
    }
    // Creating the proxy is the part that fails in a native image without a proxy registration;
    // it needs no session bus and opens no dialog.
    val proxy = runCatching {
        java.lang.reflect.Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, _, _ -> null }
    }
    proxy.fold(
        onSuccess = { record(Status.PASS, name, "interface present and proxyable") },
        onFailure = {
            record(
                Status.FAIL, name,
                "present but cannot be proxied ($it) — register it under reflection/proxy in the " +
                    "GraalVM reachability metadata",
            )
        },
    )
}

/**
 * FileKit's macOS file chooser, as far as it can be driven without opening a dialog.
 *
 * This is the macOS counterpart of [checkXdgFileChooserProxy], and it exists because there wasn't
 * one: every chooser check above returns SKIP off Linux, so on the platform where the app is a
 * GraalVM native image talking to AppKit through JNA, nothing was covered at all. "Add repo does
 * nothing and prints no error" was then reported against 1.1.1 on macOS, which is the exact shape
 * of failure this file was written for — a subsystem that reports success and does nothing.
 *
 * Three things are checked, in the order they'd break:
 *
 *  1. **The picker class ships.** `PlatformFilePicker` selects `MacOSFilePicker` by platform, so if
 *     the closed-world analysis dropped it there is no chooser at all.
 *  2. **JNA can load Foundation.** `Foundation`'s initialiser calls `Native.load("Foundation", …)`,
 *     which needs JNA's own native dispatch library to have made it into the image.
 *  3. **The ObjC callback round-trip works.** This is the one worth having. FileKit reaches
 *     `NSOpenPanel` by registering an ObjC class at runtime whose method is a JNA `Callback`, then
 *     dispatching to it with `performSelectorOnMainThread:`. A callback the image doesn't know
 *     about is invisible to static analysis, and its failure mode is silence: the selector
 *     dispatches, nothing runs, the panel is never created, and the picker returns null — which is
 *     also exactly what FileKit returns when a user cancels. So the check is not "did it throw" but
 *     "did our runnable actually run".
 *
 * Everything is reached by name. Referencing `MacOSFilePicker` or `Foundation` statically would
 * drag FileKit's macOS implementation into the image on every platform purely because this file
 * mentions it, and would defeat the point of asking whether it is there.
 *
 * The honest limit, same as the Linux check: a working callback proves the bridge is intact, not
 * that FileKit's own call path through `NSOpenPanel` returns a directory. Only opening a real
 * dialog would prove that, and that needs a person to click something.
 *
 * **Why the by-name access needs metadata of its own.** `Class.forName` in a native image answers
 * "is this class registered for reflection?", not "is this class in the image?" — and those come
 * apart badly here. The app reaches `Foundation` through ordinary static references from
 * `MacOSFilePicker`, which need no metadata, so the chooser can work perfectly while every lookup
 * in this check fails. The v1.2.0 release proved it: both macOS runners failed step 2 with
 * `ClassNotFoundException` on `Foundation`, on a bridge there is no evidence was broken.
 *
 * The Linux image is the control, and it is unambiguous: `LinuxFilePicker` and `XdgFilePickerPortal`
 * are just as unresolvable by name, both names are embedded in the binary, and a directory pick
 * runs straight through them. Unresolvable by name says nothing about whether the code works.
 *
 * So the classes this check names are registered in
 * `src/main/resources/META-INF/native-image/com.gitvantage/gitvantage/reachability-metadata.json`.
 * Read that as an enabler rather than a fix: it is what lets steps 2 and 3 run at all. Step 1 is
 * consequently tautological — we are the ones who registered the class it looks for — and only
 * steps 2 and 3 still assert anything about the shipped artifact, because a JNA load and a callback
 * round-trip have to genuinely happen. Reaching them statically instead is not an option: these
 * types are `internal` to FileKit, so Kotlin cannot name them from this module.
 *
 * The file registers the whole macOS closure, one entry per class, rather than leaning on
 * `allDeclaredMethods` on the two entry points to drag the rest along — registering a method makes
 * it reflectively callable, it does not make its body an analysis root. Registering
 * `MacOSFilePicker` alone still left `MacOSFilePickerMode$Directories` — the mode
 * `openDirectoryPicker` passes — unresolvable.
 */
private fun checkMacOsFileChooserBridge() {
    val name = "FileKit macOS file chooser bridge"
    if (!System.getProperty("os.name").lowercase().contains("mac")) {
        record(Status.SKIP, name, "not macOS")
        return
    }

    val pkg = "io.github.vinceglb.filekit.dialogs.platform"
    if (runCatching { Class.forName("$pkg.mac.MacOSFilePicker") }.isFailure) {
        record(
            Status.FAIL, name,
            "$pkg.mac.MacOSFilePicker is not reflectively reachable — its reachability metadata " +
                "registration is missing or stale. That blinds this check; on its own it does not " +
                "mean the chooser is broken, because the app reaches the picker statically",
        )
        return
    }

    // Loading the class runs its initialiser, which is the Native.load("Foundation", …) call.
    val foundation = runCatching {
        val cls = Class.forName("$pkg.mac.foundation.Foundation")
        cls.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null) to cls
    }.getOrElse {
        // Distinguish the two failures sharply: they call for opposite responses. A missing
        // registration is a hole in our metadata and proves nothing about the artifact; an
        // initialiser failure is the real defect this step exists to catch.
        record(
            Status.FAIL, name,
            if (it is ClassNotFoundException) {
                "$pkg.mac.foundation.Foundation is not reflectively reachable ($it) — its " +
                    "reachability metadata registration is missing or stale, so this check cannot " +
                    "see the bridge. That is a hole in the check, not evidence the chooser is broken"
            } else {
                "JNA could not load the Foundation framework ($it) — every native dialog on this " +
                    "platform will fail before it opens"
            },
        )
        return
    }
    val (instance, cls) = foundation

    // `[NSThread isMainThread]`, which doubles as proof that objc_msgSend dispatches at all.
    //
    // It also decides whether step 3 is safe to run: `performSelectorOnMainThread:waitUntilDone:`
    // executes inline when it is already *on* the main thread, but blocks on the main run loop when
    // it isn't — and --smoke-test opens no window, so there is no run loop to service it. Off the
    // main thread this check would hang rather than fail, so it skips instead. In practice it never
    // does: runSmokeChecks is called straight from main().
    val onMainThread = runCatching {
        val invoke = cls.getMethod("invoke", String::class.java, String::class.java, Array<Any>::class.java)
        (invoke.invoke(instance, "NSThread", "isMainThread", emptyArray<Any>()) as Number).toLong() != 0L
    }.getOrElse {
        record(Status.FAIL, name, "objc_msgSend dispatch failed ($it) — the ObjC bridge is not usable")
        return
    }
    if (!onMainThread) {
        record(Status.SKIP, name, "not on the main thread; the callback round-trip would block rather than fail")
        return
    }

    var ran = false
    val dispatched = runCatching {
        cls.getMethod("executeOnMainThread", Boolean::class.java, Boolean::class.java, Runnable::class.java)
            .invoke(instance, false, true, Runnable { ran = true })
    }
    dispatched.fold(
        onSuccess = {
            if (ran) {
                record(Status.PASS, name, "ObjC bridge live and the main-thread callback round-tripped")
            } else {
                record(
                    Status.FAIL, name,
                    "the main-thread dispatch returned but the callback never ran — the JNA " +
                        "Callback backing FileKit's runtime-registered ObjC class is missing from " +
                        "the image, so the file chooser will never open and will report a cancel",
                )
            }
        },
        onFailure = {
            record(Status.FAIL, name, "main-thread dispatch threw ($it) — the file chooser cannot open")
        },
    )
}

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
