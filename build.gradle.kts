import dev.nucleusframework.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import java.io.File

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlinSerialization)
    // Rewrites `assert(x == y)` failures into a diagram of every sub-expression and its value, so a
    // failing test names what was actually wrong without the test having to spell out a message.
    alias(libs.plugins.powerAssert)
    alias(libs.plugins.compose)
    // TestBalloon: coroutine-native test framework. The operations under test are all `suspend`,
    // and its tests are suspend functions — no runBlocking wrapper around every case.
    // The version is pinned to the Kotlin it was built against; it must track the kotlin() versions.
    alias(libs.plugins.testBalloon)
    // Konture: architecture rules as ordinary tests. The rules themselves live in :konture-test —
    // see the comment in its build file for why they cannot live here — but the plugin is applied
    // to the root project too, because the root is what generates the project layout every rule
    // reads and shares it with that module.
    alias(libs.plugins.konture)
    alias(libs.plugins.nucleus)
    alias(libs.plugins.detekt)
}

repositories {
    mavenCentral()
    google()
}

/**
 * The version stamped into the installers, derived from the release tag so the packages can never
 * disagree with the tag they were built from.
 *
 * Order matters. In CI the tag is read from GITHUB_REF_NAME, because actions/checkout does a
 * shallow clone *without tags* — `git describe` would find nothing there. Locally we fall back to
 * the most recent tag (`--abbrev=0`, so the result stays a plain x.y.z that the packagers accept,
 * rather than a `v0.0.1-3-gabc123` describe string). `-PappVersion=` overrides everything, and an
 * untagged checkout still builds, as a plain 0.0.0 — deliberately numeric rather than something
 * like "0.0.0-dev", because .msi/.rpm versions have to stay numeric to be packageable at all.
 */
// Only ever accept something the packagers will actually take: digits and dots. Anything else —
// a stray non-release tag like "foo", a describe string, a branch name — is ignored rather than
// failing the build at configuration time with "Illegal version for 'Deb'".
fun releaseVersionOrNull(raw: String?): String? =
    raw?.trim()?.removePrefix("v")?.takeIf { it.matches(Regex("""\d+(\.\d+)*""")) }

/**
 * The most recent release tag, or null when the checkout has none to describe.
 *
 * A tagless checkout is the ordinary case here, not an error — CI clones shallow and without tags,
 * and a fresh clone before the first release has nothing either. Hence `isIgnoreExitValue` and an
 * explicit exit-code check rather than letting the exec throw and catching it: under the
 * configuration cache a value source that throws is recorded as a cache problem, which fails the
 * build no matter who catches the exception afterwards.
 */
fun latestReleaseTagOrNull(): String? {
    // --match keeps non-release tags out of the answer; --abbrev=0 keeps it a plain x.y.z rather
    // than a v1.2.3-4-gabc123 describe string.
    val describe = providers.exec {
        commandLine("git", "describe", "--tags", "--abbrev=0", "--match", "v[0-9]*")
        isIgnoreExitValue = true
    }
    return describe.standardOutput.asText.get().takeIf { describe.result.get().exitValue == 0 }
}

val appVersion: String =
    releaseVersionOrNull(findProperty("appVersion") as String?)
        ?: releaseVersionOrNull(System.getenv("GITHUB_REF_NAME"))
        ?: releaseVersionOrNull(latestReleaseTagOrNull())
        ?: "0.0.0"

version = appVersion
dependencies {
    implementation(compose.desktop.currentOs)
    // The entry-point module — provides nucleusApplication and DecoratedWindow
    implementation(libs.nucleus.application)
    // Enable the Tao backend (Rust-native windowing)
    implementation(libs.nucleus.decorated.window.tao)
    // Registry persistence (repo list + tags/notes) as JSON
    implementation(libs.kotlinx.serialization.json)
    // Cross-platform native directory picker (XDG portal on Linux, native on macOS/Windows).
    // Brings PlatformFile via its filekit-core dependency.
    //
    // Its macOS bridge needs reachability metadata we ship ourselves — see
    // src/main/resources/META-INF/native-image/com.gitvantage/gitvantage/reachability-metadata.json.
    implementation(libs.fileKit.dialogs)
    // OS desktop notifications (reminders + alerts) and a filesystem watcher (live rescans).
    implementation(libs.nucleus.notification.common)
    implementation(libs.nucleus.fsWatcher)
    // Human-readable durations ("about 5 weeks") for the notification outlook / age labels.
    implementation(libs.humanReadable)
    // An SLF4J binding. Several dependencies (dbus-java in particular) log through SLF4J; with no
    // provider on the classpath they print "No SLF4J providers were found" and then silently
    // discard everything — which is how a D-Bus failure stayed invisible until the file chooser
    // visibly fell back to Swing. The app itself doesn't log, so this is deliberately the smallest
    // possible provider: slf4j-simple writes WARN and above to stderr with no configuration and no
    // plugin system. (Version tracks slf4j-api, which arrives transitively.)
    implementation(libs.slf4j.simple)
    // The --smoke-test checks in com.gitvantage.smoke talk to D-Bus directly to prove the portal
    // works in the packaged image. dbus-java is already on the runtime classpath — it arrives
    // transitively via filekit — but transitive dependencies aren't on the *compile* classpath.
    //
    // compileOnly, deliberately: declaring it as an implementation dependency would pin a version
    // alongside the one filekit resolves, and the two could then disagree at runtime. This way the
    // runtime graph stays filekit's to decide, and we only borrow the types to compile against.
    compileOnly(libs.dbusJava.core)

    testImplementation(libs.testBalloon.framework.core)

    detektPlugins(libs.detekt.rules.ktlint)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(file("detekt.yml"))
}

// Only `assert` is instrumented: the tests use it exclusively, so there is no second assertion
// vocabulary to keep in sync, and the diagram is the whole reason to prefer it over a message.
@OptIn(ExperimentalKotlinGradlePluginApi::class)
powerAssert {
    functions = listOf("kotlin.assert")
}

/**
 * The git environment the tests run in, written out rather than inherited.
 *
 * The tests drive the real `git` binary against throwaway repositories, so whatever is in the
 * developer's ~/.gitconfig — `commit.gpgsign`, a commit template, hooks, a custom
 * `init.defaultBranch` — would otherwise leak in and fail on their machine while passing in CI, or
 * the reverse. Pointing GIT_CONFIG_GLOBAL at a file we generate gives both isolation *and* control.
 *
 * `protocol.file.allow` has to live here rather than in each test repository: git has refused
 * file:// transport for submodule clones since 2.38 (CVE-2022-39253), and the commands that trip
 * over it — `submodule update --init` inside [SubmoduleOps] — are the code under test, so there is
 * no call site to pass `-c` at. Every "remote" in the tests is a local path, which is exactly the
 * shape that restriction targets, so the tests opt back in for their own throwaway repositories.
 */
val testGitConfig = tasks.register("testGitConfig") {
    description = "Generate the isolated git config the tests run against."
    val target = layout.buildDirectory.file("test-gitconfig")
    outputs.file(target)
    doLast {
        target.get().asFile.apply { parentFile.mkdirs() }.writeText(
            """
            [user]
                name = GitVantage Test
                email = test@example.invalid
            [init]
                defaultBranch = main
            [commit]
                gpgsign = false
            [tag]
                gpgsign = false
            [protocol "file"]
                allow = always
            [advice]
                detachedHead = false
            """.trimIndent() + "\n",
        )
    }
}

tasks.test {
    dependsOn(testGitConfig)
    environment("GIT_CONFIG_GLOBAL", layout.buildDirectory.file("test-gitconfig").get().asFile.path)
    // No generated equivalent: /etc/gitconfig is simply switched off. Git reads a missing config
    // file as an empty one, so a path that is never created is the portable way to say "none".
    environment("GIT_CONFIG_SYSTEM", layout.buildDirectory.file("test-git-no-system-config").get().asFile.path)
    // A test must never block on a credential prompt: fail the command instead.
    environment("GIT_TERMINAL_PROMPT", "0")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = false
    }
}
/**
 * Short switches for the diagnostics, so `./gradlew run` doesn't need the variable names.
 *
 * The app reads environment variables, because that is what a *packaged* binary can be given: a user
 * reporting a slow dashboard can be told to export one and restart. Those work here too —
 * `GITVANTAGE_PERF=1 ./gradlew run` does reach the forked JVM, daemon or not; I checked, having
 * assumed otherwise. These properties are purely a convenience over remembering the spellings:
 *
 *   ./gradlew run -Pperf            counters, printed every 30s and at exit
 *   ./gradlew run -Pstall=150       lower the UI-stall threshold from its 500ms default
 *   ./gradlew run -PdebugStall=4000 block the UI thread on purpose, to prove the watchdog works
 *
 * Only a property that was actually passed sets anything, so an exported variable still wins.
 */
tasks.withType<JavaExec>().configureEach {
    fun forward(property: String, variable: String, whenPresent: String? = null) {
        val value = providers.gradleProperty(property).orNull?.takeIf { it.isNotBlank() } ?: whenPresent
        if (findProperty(property) != null && value != null) environment(variable, value)
    }
    forward("perf", "GITVANTAGE_PERF", whenPresent = "1")
    forward("stall", "GITVANTAGE_STALL_MS")
    forward("debugStall", "GITVANTAGE_DEBUG_STALL_MS")
}

nucleus.application {
    mainClass = "com.gitvantage.ui.MainKt"
    // Build release installers from a GraalVM native image (fast startup, no bundled JVM).
    // The Nucleus plugin auto-provisions GraalVM via its toolchain, contributes the AWT/Skiko
    // reachability substitutions, and the package* tasks then wrap the native binary.
    graalvm {
        isEnabled.set(true)
        imageName.set("GitVantage")
        toolchain {
            // Pin the toolchain: the compiler that builds a release should not change under us.
            //
            // Left unset, the plugin follows its default channel, and the *plugin's* default is
            // what floats — NucleusFramework's own setup action already defaults to 25i2 while the
            // plugin currently resolves 25i1, so a plugin upgrade alone could silently change the
            // compiler behind a release. This is the version the verified 1.0.0 native build used
            // (asset graalvm-community-jdk-25i1-25.0.3, tag graal-25.1.3, "GraalVM CE 25.1.3+9.1").
            //
            // "25i1" still selects the newest patch within the innovation line, and resolving it
            // needs the graalvm-ce-builds releases API (see GITHUB_TOKEN in release.yml). A
            // patch-style version such as "25.0.2" would be fully deterministic *and* resolve
            // offline — the provisioner builds that URL directly, no API call — but it is a
            // different release line than the one we verified, so it is not a free swap.
            version.set("25i1")
        }
    }
    nativeDistributions {
        packageName = "GitVantage"
        packageVersion = appVersion
        targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
        // Package metadata. These aren't cosmetic: the Linux packager (electron-builder's fpm
        // target) hard-fails the .deb build without a homepage, an author e-mail and a deb
        // maintainer, so leaving them unset breaks the release build rather than just producing
        // a vague package.
        description = "A git status dashboard for people who juggle a lot of repositories."
        vendor = "Raman Gupta"
        copyright = "© 2026 Raman Gupta"
        homepage = "https://github.com/rocketraman/gitvantage"
        licenseFile.set(File(projectDir, "LICENSE"))
        // Per-platform icons so the installed package (Deb/Rpm launcher, Windows Start-menu
        // shortcut, macOS dock/Finder) gets a real icon. Each format must be built on its own
        // OS (jpackage limitation), but the icon is wired up for when it is.
        linux {
            iconFile.set(File(projectDir, "packaging/icons/icon.png"))   // .deb + .rpm
            packageName = "gitvantage"                                   // lowercase: Debian policy
            debMaintainer = "raman@inner.tech"
            appCategory = "Development"
            menuGroup = "Development"
            rpmLicenseType = "GPL-3.0-or-later"
            // Must match the Wayland app_id the window sets, or the desktop shell won't match the
            // window to its .desktop entry (icon falls back to a generic one).
            startupWMClass = "GitVantage"
        }
        windows {
            iconFile.set(File(projectDir, "packaging/icons/icon.ico"))
            menuGroup = "GitVantage"
            // Stable UUID: Windows uses it to recognise upgrades of the same app rather than
            // installing a second copy side by side. Must not change between releases.
            upgradeUuid = "6f3a1c94-5d8e-4a17-9b62-2c0f8d47ae35"
        }
        macOS {
            iconFile.set(File(projectDir, "packaging/icons/icon.icns"))
            bundleID = "com.gitvantage"
        }
    }
}


// --- GraalVM native packaging ------------------------------------------------------------------
//
// `graalvm { isEnabled }` registers a *second, parallel* task graph rather than redirecting the
// jpackage one, and the plugin binds that graph to the **default** build type (empty classifier).
// So the tasks are packageGraalvmDmg/Deb/Rpm/Msi — not packageGraalvmRelease* — and, unlike the
// jpackage side, there is no ...DistributionForCurrentOS aggregate to invoke.
//
// v1.0.0 shipped through exactly that gap. The release workflow called
// packageReleaseDistributionForCurrentOS, which is the *jpackage* aggregate, so every installer on
// every platform contained a jlink'd JVM app image and native-image never ran at all — the macOS
// job finished in under three minutes against a 90-minute timeout, and nothing failed.
//
// On macOS that isn't merely "slower to start". Nucleus's Tao backend requires its event loop on
// process thread 0: a native image provides that automatically, while a plain JVM launch needs
// -XstartOnFirstThread, which the generated jpackage launcher does not pass. The shipped 1.0.0 DMG
// died at startup with an NPE inside Compose's resource loader, called on a JNI-attached thread
// whose context class loader is null. Linux and Windows have no thread-0 requirement, which is why
// that particular defect was invisible there.
//
// Linux is not actually safe on the jpackage path either — it just fails differently, and we only
// learned how in Aug 2026. jpackage's Linux launcher pipes a serialized JvmlLauncherData blob from
// a forked child to the parent and reads it back with a single unlooped read(). Once the expanded
// classpath makes that blob large enough, the read comes up short, the tail of the blob is left
// uninitialized, and the launcher SIGSEGVs in setenv() before the JVM starts — silently, with no
// output at all. It is a scheduling race rather than a size threshold, so it is not something a
// build-time check can rule out. Fixed upstream in JDK mainline as JDK-8380085 but not backported
// to 25u, which is what we package with. See NucleusFramework/Nucleus#454.
//
// So the gate below matters on all three platforms, not just macOS: there is no OS on which
// silently falling back to jpackage produces a working release.
//
// Every format task is registered on every OS and carries `enabled = isCompatibleWithCurrentOS`,
// so depending on all four is correct: the three that don't apply are skipped, and this single
// aggregate works unchanged across the whole release matrix.
tasks.register("packageGraalvmDistributionForCurrentOS") {
    group = "nucleus"
    description = "Package the GraalVM native image for the current OS."
    dependsOn("packageGraalvmDmg", "packageGraalvmDeb", "packageGraalvmRpm", "packageGraalvmMsi")
}

/**
 * Where `packageGraalvmNative` leaves the app bundle the installer packagers then wrap.
 *
 * The plugin computes this internally and exposes no accessor, so the three gates below have to
 * name it. Keep it in one place: 2.4.0 moved it, and having the literal in three task bodies meant
 * the move produced three identical failures to chase instead of one.
 *
 * The 2.1.9 layout was `compose/tmp/main/graalvm/output` — a staging directory under `tmp`. 2.4.0
 * derives it from `nativeDistributions.outputBaseDir` instead (default `build/compose/binaries`),
 * so the bundle is now a sibling of the `graalvm-deb` / `graalvm-rpm` / `graalvm-dmg` / `graalvm-msi`
 * directories the packagers write, rather than an intermediate. Only the location changed; it is
 * still produced by `packageGraalvmNative` and still consumed by the packagers, so gating on it
 * between the two is as sound as before.
 */
val graalvmAppDir = layout.buildDirectory.dir("compose/binaries/main/graalvm-app")

/**
 * Release gate: the bundle handed to the installer packagers must actually be a native image.
 *
 * The two pipelines produce bundles of the same shape in the same place, differing mainly in
 * whether a jlink'd JDK is embedded — which is precisely why shipping the wrong one went unnoticed
 * through a tagged release. A native image bundles no Java runtime, so the presence of a `runtime`
 * directory means the jpackage image is being packaged and the native build silently did not
 * happen.
 *
 * The remaining assertions cover the ways the bundle can be structurally wrong while still looking
 * plausible: a launcher stub instead of the compiled image, or a missing native library that only
 * surfaces when the app tries to draw. Everything checked here is a property of the artifact that
 * ships, not of an intermediate — that distinction is what the pipeline mix-up cost us.
 */
val verifyGraalvmNativeImage = tasks.register("verifyGraalvmNativeImage") {
    group = "verification"
    description = "Verify the packaged bundle is a well-formed GraalVM native image."
    dependsOn("packageGraalvmNative")
    outputs.upToDateWhen { false }   // cheap, and must never be skipped on a release build

    // The macOS bundle root is `GitVantage.app`, the Linux and Windows ones a plain directory, so
    // address the shared parent rather than the leaf.
    val outputDir = graalvmAppDir
    val imageName = "GitVantage"

    doLast {
        val dir = outputDir.get().asFile
        check(dir.isDirectory) {
            "No GraalVM packaging output at $dir - did packageGraalvmNative run?"
        }
        val problems = mutableListOf<String>()

        // 1. No bundled JVM. This is the invariant that distinguishes the two pipelines: a jlink'd
        //    runtime means we are packaging the jpackage app image and native-image never ran.
        dir.walkTopDown().filter { it.isDirectory && it.name == "runtime" }.toList().let { runtimes ->
            if (runtimes.isNotEmpty()) {
                problems += "embeds a Java runtime (jpackage app image, not a native image): " +
                    runtimes.joinToString { it.relativeTo(dir).path }
            }
        }

        // 2. The launcher is the compiled image, not jpackage's stub. Size is the cheap, portable
        //    discriminator: the native image is hundreds of MB, jpackage's launcher ~200 KB.
        val minImageBytes = 20L * 1024 * 1024
        val binary = dir.walkTopDown()
            .filter { it.isFile && (it.name == imageName || it.name == "$imageName.exe") }
            .maxByOrNull { it.length() }
        when {
            binary == null -> problems += "contains no $imageName executable"
            binary.length() < minImageBytes ->
                problems += "the $imageName executable is only ${binary.length() / 1024} KB — a native " +
                    "image is far larger, so this looks like a launcher stub"
        }

        // 3. The native libraries the app dlopen's at startup. Skiko is the renderer: without it
        //    the process starts and then dies on the first frame, which no static check would see.
        //
        //    Match on the shared-library extension rather than a "lib" prefix: that prefix is a
        //    Unix convention, and Windows ships `skiko-windows-x64.dll` / `awt.dll` with no prefix
        //    at all — requiring it failed the Windows leg on a bundle that was perfectly fine.
        //    Substring rather than suffix so versioned sonames (libfoo.so.1) still match.
        val libraryExtensions = listOf(".so", ".dylib", ".dll")
        listOf("skiko" to "renderer", "awt" to "AWT").forEach { (token, what) ->
            val found = dir.walkTopDown().any { f ->
                f.isFile && f.name.contains(token) && libraryExtensions.any { it in f.name }
            }
            if (!found) {
                problems += "ships no $what native library (nothing matching *$token*.{so,dylib,dll})"
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "The bundle about to be packaged is not a usable native image:\n  " +
                    problems.joinToString("\n  ") +
                    "\n\nBundle: $dir",
            )
        }
        logger.lifecycle(
            "verifyGraalvmNativeImage: native image OK " +
                "(${binary!!.name}, ${binary.length() / 1024 / 1024} MB, no bundled JVM)",
        )
    }
}

/**
 * Release gate: the packaged native binary must actually start.
 *
 * The static checks above describe the artifact's shape; this one runs it. That distinction is
 * exactly what shipped 1.0.0 broken — the bundle was well-formed, and the app still died during
 * composition before drawing a frame. A native image also changes what can fail at startup
 * (reflection and resource reachability are decided at build time), so "it linked" is not evidence
 * that it runs.
 *
 * Linux only: the runner has xvfb, whereas the macOS and Windows runners have no dependable
 * headless display. This is a real gap — the 1.0.0 defect was macOS-only — so it is a backstop for
 * the whole class, not proof for every platform.
 */
val smokeTestNativeImage = tasks.register("smokeTestNativeImage") {
    group = "verification"
    description = "Launch the packaged native binary and verify it stays up."
    dependsOn(verifyGraalvmNativeImage)
    outputs.upToDateWhen { false }

    val outputDir = graalvmAppDir
    val imageName = "GitVantage"
    // Hoisted out of doLast: reaching for `providers` from inside the action would capture the
    // build script object itself, which the configuration cache cannot serialize.
    val providers = project.providers
    val isLinux = System.getProperty("os.name").lowercase().contains("linux")
    val hasXvfb = isLinux &&
        System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, "xvfb-run").canExecute() }
    // Hard-fail on CI, skip locally. On the release runner setup-nucleus installs xvfb, so its
    // absence there means this gate has silently stopped running — the failure mode that let a
    // broken artifact ship. On a developer machine it is just a missing optional tool.
    val isCi = !System.getenv("CI").isNullOrBlank()
    onlyIf { isLinux }

    doLast {
        if (!hasXvfb) {
            check(!isCi) {
                "xvfb-run not found, but this is CI. The Linux release runner installs it via " +
                    "setup-nucleus (packaging-tools), so a missing xvfb means the packaged binary " +
                    "is no longer being launched at all."
            }
            logger.lifecycle("smokeTestNativeImage: skipped — xvfb-run not installed (install it to run this check)")
            return@doLast
        }
        val binary = outputDir.get().asFile.walkTopDown()
            .filter { it.isFile && it.name == imageName }
            .maxByOrNull { it.length() }
        checkNotNull(binary) { "No $imageName executable under ${outputDir.get().asFile}" }

        // `timeout` reports 124 when it had to kill a still-running process — which is the success
        // condition here: a GUI app that is alive after the hold period started cleanly. Any other
        // exit code means it terminated on its own, i.e. it crashed or bailed out at startup.
        val holdSeconds = 15
        val result = providers.exec {
            commandLine("xvfb-run", "-a", "timeout", "-s", "TERM", "$holdSeconds", binary.absolutePath)
            isIgnoreExitValue = true
        }
        val exit = result.result.get().exitValue
        val log = result.standardOutput.asText.get() + result.standardError.asText.get()

        if (exit != 124) {
            throw GradleException(
                "The packaged native binary exited on its own (code $exit) instead of staying up for " +
                    "${holdSeconds}s — it failed at startup:\n\n" + log.trim().ifEmpty { "(no output)" },
            )
        }
        logger.lifecycle("smokeTestNativeImage: ${binary.name} stayed up for ${holdSeconds}s under xvfb")
    }
}

/**
 * Release gate: the packaged native binary's subsystems must actually work.
 *
 * Runs `GitVantage --smoke-test`, which executes the checks in com.gitvantage.smoke *inside the
 * shipped image* and exits non-zero if any fail. This is the check that carries the retired
 * ProGuard -keep rules forward: the filesystem watcher's native callback, D-Bus (transport found
 * via ServiceLoader, remote objects via dynamic proxy), the XDG portal, FileKit's own chooser
 * interface and desktop notifications are all reached indirectly, so GraalVM's closed-world
 * analysis can drop them — and every historical instance degraded silently rather than failing.
 *
 * Runs on every platform, unlike smokeTestNativeImage: no window is opened, so no display is
 * needed. Individual checks skip themselves where they do not apply (the portal is Linux-only) or
 * where the environment lacks a session bus, so a bare runner reports SKIP rather than failing.
 */
val smokeTestNativeImageSubsystems = tasks.register("smokeTestNativeImageSubsystems") {
    group = "verification"
    description = "Run the packaged native binary's own subsystem checks (--smoke-test)."
    dependsOn(verifyGraalvmNativeImage)
    outputs.upToDateWhen { false }

    val outputDir = graalvmAppDir
    val imageName = "GitVantage"
    // See smokeTestNativeImage: `providers` must be captured here, not inside doLast.
    val providers = project.providers

    doLast {
        val dir = outputDir.get().asFile
        val binary = dir.walkTopDown()
            .filter { it.isFile && (it.name == imageName || it.name == "$imageName.exe") }
            .maxByOrNull { it.length() }
        checkNotNull(binary) { "No $imageName executable under $dir" }

        val result = providers.exec {
            commandLine(binary.absolutePath, "--smoke-test")
            isIgnoreExitValue = true
        }
        val exit = result.result.get().exitValue
        val log = (result.standardOutput.asText.get() + result.standardError.asText.get()).trim()

        if (exit != 0) {
            throw GradleException(
                "The packaged native binary's subsystem checks failed (exit $exit):\n\n" +
                    log.ifEmpty { "(no output)" },
            )
        }
        logger.lifecycle(log.ifEmpty { "smokeTestNativeImageSubsystems: no output" })
    }
}

// Gate the packagers, not just the native build: the checks run between producing the bundle and
// wrapping it in an installer, so a broken bundle can't reach a .dmg/.deb/.rpm/.msi.
tasks.matching { it.name.startsWith("packageGraalvm") && it.name != "packageGraalvmNative" }
    .configureEach {
        dependsOn(verifyGraalvmNativeImage, smokeTestNativeImage, smokeTestNativeImageSubsystems)
    }


// --- Subsystem checks on the development classpath ----------------------------------------------
//
// The same checks the shipped binary runs via --smoke-test, executed on the development classpath
// and wired into `check`. This is for fast local feedback: it needs no native image, which takes
// minutes to build.
//
// It is deliberately NOT part of the packaging path. On a development classpath nothing has been
// shrunk, so it cannot see the failures it was written for — and a check that inspects the
// development classpath while claiming to cover the shipped artifact is how 1.0.0 got out.
// smokeTestNativeImageSubsystems is the one that gates a release.
val smokeTestSubsystems = tasks.register<JavaExec>("smokeTestSubsystems") {
    group = "verification"
    description = "Run the subsystem checks on the development classpath (see --smoke-test for the real gate)."
    dependsOn("classes")
    outputs.upToDateWhen { false }

    mainClass.set("com.gitvantage.smoke.SmokeChecksKt")
    classpath = files(sourceSets["main"].output, configurations.runtimeClasspath)
}

tasks.named("check") { dependsOn(smokeTestSubsystems) }

