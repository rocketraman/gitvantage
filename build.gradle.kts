import dev.nucleusframework.desktop.application.dsl.TargetFormat
import java.io.File
// Imported explicitly: `import java.io.File` above shadows the `java` package inside a Kotlin DSL
// script, so fully-qualified java.net.* / java.util.* references don't resolve here.
import java.net.URLClassLoader
import java.util.jar.JarFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.SimpleVerifier

// ASM, for the verifyReleaseBytecode task below. On the buildscript classpath so the task can be
// written inline here rather than needing a buildSrc module.
buildscript {
    repositories { mavenCentral() }
    dependencies {
        classpath("org.ow2.asm:asm-tree:9.8")
        classpath("org.ow2.asm:asm-analysis:9.8")
    }
}

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.compose") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    id("org.jetbrains.compose") version "1.11.1"
    id("dev.nucleusframework") version "2.1.9"
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

val appVersion: String =
    releaseVersionOrNull(findProperty("appVersion") as String?)
        ?: releaseVersionOrNull(System.getenv("GITHUB_REF_NAME"))
        ?: runCatching {
            providers.exec {
                // --match keeps non-release tags out of the answer; --abbrev=0 keeps it a plain
                // x.y.z rather than a v1.2.3-4-gabc123 describe string.
                commandLine("git", "describe", "--tags", "--abbrev=0", "--match", "v[0-9]*")
            }.standardOutput.asText.get()
        }.getOrNull().let { releaseVersionOrNull(it) }
        ?: "0.0.0"

version = appVersion
dependencies {
    implementation(compose.desktop.currentOs)
    // The entry-point module — provides nucleusApplication and DecoratedWindow
    implementation("dev.nucleusframework:nucleus.nucleus-application:2.1.9")
    // Enable the Tao backend (Rust-native windowing)
    implementation("dev.nucleusframework:nucleus.decorated-window-tao:2.1.9")
    // Registry persistence (repo list + tags/notes) as JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // Cross-platform native directory picker (XDG portal on Linux, native on macOS/Windows).
    // Brings PlatformFile via its filekit-core dependency.
    implementation("io.github.vinceglb:filekit-dialogs:0.14.2")
    // OS desktop notifications (reminders + alerts) and a filesystem watcher (live rescans).
    implementation("dev.nucleusframework:nucleus.notification-common:2.1.9")
    implementation("dev.nucleusframework:nucleus.fs-watcher:2.1.9")
    // Human-readable durations ("about 5 weeks") for the notification outlook / age labels.
    implementation("nl.jacobras:Human-Readable:2.0.0-alpha02")
    // An SLF4J binding. Several dependencies (dbus-java in particular) log through SLF4J; with no
    // provider on the classpath they print "No SLF4J providers were found" and then silently
    // discard everything — which is how a D-Bus failure stayed invisible until the file chooser
    // visibly fell back to Swing. The app itself doesn't log, so this is deliberately the smallest
    // possible provider: slf4j-simple writes WARN and above to stderr with no configuration and no
    // plugin system. (Version tracks slf4j-api, which arrives transitively.)
    implementation("org.slf4j:slf4j-simple:2.0.17")
}
nucleus.application {
    mainClass = "com.gitvantage.MainKt"
    // Build release installers from a GraalVM native image (fast startup, no bundled JVM).
    // The Nucleus plugin auto-provisions GraalVM via its toolchain, contributes the AWT/Skiko
    // reachability substitutions, and the package* tasks then wrap the native binary.
    graalvm {
        isEnabled.set(true)
        imageName.set("GitVantage")
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

/**
 * Release gate: verify every class in the packaged app actually passes bytecode verification.
 *
 * This exists because a ProGuard-optimizer bug once shipped: it miscompiled
 * kotlinx-coroutines' trySendBlocking so the JVM threw VerifyError the first time the filesystem
 * watcher sent to a channel. Nothing caught it — `./gradlew run` and `./gradlew build` never run
 * ProGuard, so the defect existed *only* in the installers, the one artifact no check executed.
 *
 * Verification is done statically with ASM (no class initialisers run, no display needed, works
 * identically on every CI runner), against the exact jars inside the packaged image.
 */
val verifyReleaseBytecode = tasks.register("verifyReleaseBytecode") {
    group = "verification"
    description = "Verify the packaged release image for corrupted (unverifiable) bytecode."
    dependsOn("createReleaseDistributable")

    val appDir = layout.buildDirectory.dir("compose/binaries/main-release/app")
    outputs.upToDateWhen { false }   // cheap, and must never be skipped on a release build

    doLast {
        // Only the application's own jars. The image also contains the JDK's internal jrt-fs.jar
        // under lib/runtime, which is not ours to verify and confuses jdeps.
        val jars = appDir.get().asFile.walkTopDown()
            .filter { it.extension == "jar" && "${File.separator}runtime${File.separator}" !in it.path }
            .sorted().toList()
        check(jars.isNotEmpty()) { "No jars under ${appDir.get().asFile} — was the release image built?" }

        // Resolve types against the app's own jars (platform loader as parent for java.*), so
        // verification sees what the shipped app sees.
        val loader = URLClassLoader(
            jars.map { it.toURI().toURL() }.toTypedArray(),
            ClassLoader.getPlatformClassLoader(),
        )

        var scanned = 0
        val corrupt = mutableListOf<String>()
        val unresolved = sortedSetOf<String>()
        // For the ServiceLoader check below: every class the image ships, and every service
        // registration in it.
        val present = mutableSetOf<String>()
        val services = mutableListOf<Triple<String, String, List<String>>>()

        jars.forEach { jarFile ->
            JarFile(jarFile).use { jar ->
                jar.entries().asSequence().forEach { e ->
                    when {
                        e.name.endsWith(".class") -> present += e.name.removeSuffix(".class").replace('/', '.')
                        e.name.startsWith("META-INF/services/") && !e.isDirectory -> {
                            val impls = jar.getInputStream(e).bufferedReader().readLines()
                                .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
                            services += Triple(jarFile.name, e.name.substringAfterLast('/'), impls)
                        }
                    }
                }
            }
        }

        jars.forEach { jarFile ->
            JarFile(jarFile).use { jar ->
                jar.entries().asSequence().filter { it.name.endsWith(".class") }.forEach { entry ->
                    scanned++
                    val node = ClassNode()
                    jar.getInputStream(entry).use { ClassReader(it).accept(node, ClassReader.SKIP_DEBUG) }
                    node.methods.forEach { method ->
                        try {
                            val verifier = SimpleVerifier()
                            verifier.setClassLoader(loader)
                            Analyzer(verifier).analyze(node.name, method)
                        } catch (e: Throwable) {
                            // Classify by exception *type*, not message text: a NoClassDefFoundError's
                            // message is only the internal class name, so matching on strings
                            // misfiled every unresolvable type as corruption.
                            //
                            // Unresolvable type  -> the verifier couldn't load a class that isn't
                            //   shipped (optional integrations like log4j's LMAX Disruptor async
                            //   appenders, compile-only annotations, dev-only tooling). Dead code in
                            //   this image; not a defect.
                            // Anything else      -> the bytecode itself doesn't verify.
                            val chain = generateSequence(e) { it.cause }
                            val missing = chain.firstOrNull {
                                it is ClassNotFoundException || it is NoClassDefFoundError ||
                                    it is TypeNotPresentException
                            }
                            if (missing != null) {
                                unresolved += missing.message.orEmpty().trim()
                                    .substringAfterLast(' ').replace('/', '.')
                            } else {
                                val msg = e.message.orEmpty().replace('\n', ' ').trim()
                                corrupt += "${node.name}.${method.name}${method.desc}\n      $msg"
                            }
                        }
                    }
                }
            }
        }

        // ServiceLoader registrations whose implementation the shrinker deleted. The class is only
        // ever named in META-INF/services, so ProGuard can't see the reference and strips it; the
        // app then degrades silently at runtime rather than failing. This has bitten twice: the
        // D-Bus transport (native file chooser fell back to Swing) and the SLF4J binding (all
        // logging discarded, hiding the first failure).
        val danglingServices = services.mapNotNull { (jar, service, impls) ->
            impls.filterNot { it in present }.takeIf { it.isNotEmpty() }?.let { missing ->
                "$service -> ${missing.joinToString()}\n      registered by $jar"
            }
        }

        logger.lifecycle(
            "verifyReleaseBytecode: scanned $scanned classes in ${jars.size} jars, " +
                "${services.size} service registrations",
        )
        if (unresolved.isNotEmpty()) {
            logger.info("  unresolved (not shipped, ignored): ${unresolved.joinToString()}")
        }
        // JDK modules the app needs vs. what the jlink'd runtime actually bundles.
        //
        // The runtime image is minimal — the plugin includes only the modules it inferred — so a
        // module it missed fails at runtime with NoClassDefFoundError and nothing before that
        // notices. jdeps computes the requirement from the bytecode, which is the authoritative
        // answer; comparing against the image's own module list is what catches the gap.
        // (jdk.security.auth went out in a release exactly this way: dbus-java's SASL handshake
        // calls com.sun.security.auth.module.UnixSystem, so every D-Bus connection died and the
        // native file chooser fell back to Swing.)
        // Hard-fail rather than skip when the file isn't where we expect. This previously resolved a
        // Linux-only path guarded by `if (isFile)`, so on Windows and macOS the check quietly did
        // nothing — the one gate that catches a missing module was a no-op on two of three platforms.
        val runtimeRelease = File(releaseRuntimeDir, "release")
        check(runtimeRelease.isFile) {
            "jlink'd runtime release file not found at $runtimeRelease - did the app image layout change?"
        }
        val bundledModules = runtimeRelease.readLines()
            .firstOrNull { it.startsWith("MODULES=") }
            .orEmpty().substringAfter('=').trim('"').split(' ').filter { it.isNotBlank() }.toSet()
        val requiredModules = providers.exec {
            commandLine(
                File(System.getProperty("java.home"), "bin/jdeps").absolutePath,
                "--print-module-deps", "--ignore-missing-deps", "--multi-release", "21",
                *jars.map { it.absolutePath }.toTypedArray(),
            )
        }.standardOutput.asText.get()
            // jdeps prints the comma-separated module list on its own line, but may emit
            // "Warning: ..." lines first — take the payload line, not the diagnostics.
            .lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotBlank() && !it.startsWith("Warning:") && ' ' !in it }
            .orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()

        val missingModules = requiredModules - bundledModules
        logger.lifecycle(
            "verifyReleaseBytecode: runtime bundles ${bundledModules.size} JDK modules, " +
                "jdeps requires ${requiredModules.size}",
        )
        if (missingModules.isNotEmpty()) {
            throw GradleException(
                "The bundled runtime image is missing JDK modules the app requires — anything " +
                    "touching them throws NoClassDefFoundError at runtime:\n  " +
                    missingModules.sorted().joinToString("\n  ") +
                    "\n\nAdd them to nativeDistributions { modules(...) } in build.gradle.kts.",
            )
        }
        if (danglingServices.isNotEmpty()) {
            throw GradleException(
                "ServiceLoader registrations point at classes that were stripped from the packaged " +
                    "release — whatever they provide will silently not work:\n  " +
                    danglingServices.joinToString("\n  ") +
                    "\n\nAdd a -keep rule for them in packaging/proguard-rules.pro.",
            )
        }
        if (corrupt.isNotEmpty()) {
            throw GradleException(
                "Corrupted bytecode in the packaged release — these methods fail verification and " +
                    "will throw VerifyError at runtime:\n  " + corrupt.joinToString("\n  ") +
                    "\n\nThis usually means a ProGuard optimisation miscompiled a dependency; see " +
                    "buildTypes.release.proguard in build.gradle.kts.",
            )
        }
    }
}

// Packaging always drags the verifier along, so a release can't be produced without it running.
listOf("packageReleaseDistributionForCurrentOS", "packageReleaseDeb", "packageReleaseRpm").forEach { name ->
    tasks.matching { it.name == name }.configureEach { finalizedBy(verifyReleaseBytecode) }
}


// --- Release app-image layout ----------------------------------------------------------------
//
// jpackage's app image is laid out differently on each OS, and both the directory holding the
// launcher's <name>.cfg plus the application jars, and the one holding the jlink'd runtime, move
// with it:
//
//            image root       app dir       runtime dir
//   Linux    GitVantage/      lib/app/      lib/runtime/
//   Windows  GitVantage/      app/          runtime/
//   macOS    GitVantage.app/  Contents/app/ Contents/runtime/Contents/Home/
//
// Only macOS suffixes the image root, and only macOS nests the runtime inside a second JDK bundle
// (hence the Contents/Home tail — the `release` file lives at the *bottom* of that path).
// Verified against jdk.jpackage.internal.ApplicationLayoutUtils in the JDK 25 sources; the Compose
// plugin adds the .app suffix in AbstractJPackageTask.
//
// The pathing jar, the bytecode verifier and the smoke test all address the image. Hard-coding the
// Linux shape in each of them produced a release that built only on Linux and failed on Windows and
// both macOS runners — and, worse, a module check that silently passed off-Linux by looking for a
// file that could not be there. Everything goes through these so the assumption lives in one place.
//
// Note this is the *launcher* name — jpackage's --name, i.e. the top-level packageName. The
// linux { packageName = "gitvantage" } override renames the .deb/.rpm package, not the app image.
val appImageName = "GitVantage"

val isMacOsHost = System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("darwin") }
val isWindowsHost = System.getProperty("os.name").lowercase().contains("win")

val releaseImageDir: File = layout.buildDirectory
    .dir("compose/binaries/main-release/app/" + if (isMacOsHost) "$appImageName.app" else appImageName)
    .get().asFile

/** Directory inside the release image holding `$appImageName.cfg` and the application jars. */
val releaseAppDir: File = File(
    releaseImageDir,
    when {
        isMacOsHost -> "Contents/app"
        isWindowsHost -> "app"
        else -> "lib/app"
    },
)

/**
 * JDK *home* of the jlink'd runtime inside the release image — where its `release` file lives.
 *
 * On macOS this is not the same directory as the runtime *bundle* root (`Contents/runtime`), which
 * is what signing and the embedded provisioning profile want; the home is nested another
 * `Contents/Home` down. They coincide on Linux and Windows, so anything that needs the bundle root
 * must not reuse this or it will be silently wrong on exactly one platform.
 */
val releaseRuntimeDir: File = File(
    releaseImageDir,
    when {
        isMacOsHost -> "Contents/runtime/Contents/Home"
        isWindowsHost -> "runtime"
        else -> "lib/runtime"
    },
)



// --- Smoke test of the packaged image ------------------------------------------------------
// Compiled against the normal dependencies, but executed with the *packaged* jars on its
// classpath, so it runs the ProGuard-minified code that actually ships. See
// src/smokeTest/kotlin — and verifyReleaseBytecode above for the static half of the gate.
sourceSets.create("smokeTest")

// Compile against the full runtime classpath, not just the declared dependencies: the smoke test
// pokes at integrations that arrive transitively (dbus-java comes in via filekit), and those are
// runtime-only for us, so they're absent from a normal compile classpath.
sourceSets.named("smokeTest") {
    compileClasspath += configurations.runtimeClasspath.get()
}

val smokeTestReleaseImage = tasks.register<JavaExec>("smokeTestReleaseImage") {
    group = "verification"
    description = "Run the packaged release image's subsystems (fs-watcher, portal, notifications)."
    dependsOn("createReleaseDistributable", "smokeTestClasses")
    mustRunAfter(verifyReleaseBytecode)
    outputs.upToDateWhen { false }

    mainClass.set("com.gitvantage.smoke.SmokeTestKt")
    // Deliberately NOT the smokeTest runtime classpath: only the compiled checks plus the jars
    // from inside the release image. Adding the development dependencies would resolve classes
    // ProGuard removed and the test would pass against code that isn't what ships.
    val appJars = releaseAppDir
    classpath = files(
        sourceSets["smokeTest"].output,
        provider { appJars.listFiles()?.filter { it.extension == "jar" } ?: emptyList<File>() },
    )
}

listOf("packageReleaseDistributionForCurrentOS", "packageReleaseDeb", "packageReleaseRpm").forEach { name ->
    tasks.matching { it.name == name }.configureEach { finalizedBy(smokeTestReleaseImage) }
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
// the same defect was invisible there.
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
 * Release gate: the bundle handed to the installer packagers must actually be a native image.
 *
 * The two pipelines produce bundles of the same shape in the same place, differing mainly in
 * whether a jlink'd JDK is embedded — which is precisely why shipping the wrong one went unnoticed
 * through a tagged release. A native image bundles no Java runtime, so the presence of a `runtime`
 * directory means the jpackage image is being packaged and the native build silently did not
 * happen.
 *
 * Deliberately narrow: it asserts the one invariant that distinguishes the pipelines, rather than
 * enumerating expected bundle contents, so it cannot misfire as the plugin's layout evolves.
 */
val verifyGraalvmNativeImage = tasks.register("verifyGraalvmNativeImage") {
    group = "verification"
    description = "Verify the packaged bundle is a GraalVM native image, not a bundled-JVM app image."
    dependsOn("packageGraalvmNative")
    outputs.upToDateWhen { false }   // cheap, and must never be skipped on a release build

    // appTmpDir for the default build type; the macOS bundle root is `GitVantage.app`, the Linux
    // and Windows ones a plain directory, so address the shared parent rather than the leaf.
    val outputDir = layout.buildDirectory.dir("compose/tmp/main/graalvm/output")

    doLast {
        val dir = outputDir.get().asFile
        check(dir.isDirectory) {
            "No GraalVM packaging output at $dir - did packageGraalvmNative run?"
        }
        val embeddedRuntimes = dir.walkTopDown().filter { it.isDirectory && it.name == "runtime" }.toList()
        if (embeddedRuntimes.isNotEmpty()) {
            throw GradleException(
                "The bundle about to be packaged embeds a Java runtime, so it is the jpackage app " +
                    "image rather than a GraalVM native image:\n  " +
                    embeddedRuntimes.joinToString("\n  ") { it.relativeTo(dir).path } +
                    "\n\nThe installers would carry a bundled JVM, and on macOS the Tao event loop " +
                    "would not run on thread 0 (see the notes above packageGraalvmDistributionForCurrentOS).",
            )
        }
        logger.lifecycle("verifyGraalvmNativeImage: no embedded Java runtime under $dir")
    }
}

// Gate the packagers, not just the native build: the check runs between producing the bundle and
// wrapping it in an installer, so a wrong bundle can't reach a .dmg/.deb/.rpm/.msi.
tasks.matching { it.name.startsWith("packageGraalvm") && it.name != "packageGraalvmNative" }
    .configureEach { dependsOn(verifyGraalvmNativeImage) }
