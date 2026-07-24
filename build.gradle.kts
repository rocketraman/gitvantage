import dev.nucleusframework.desktop.application.dsl.TargetFormat
import java.io.File

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.compose") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    id("org.jetbrains.compose") version "1.11.1"
    id("dev.nucleusframework") version "2.1.5"
}
repositories {
    mavenCentral()
    google()
}
dependencies {
    implementation(compose.desktop.currentOs)
    // The entry-point module — provides nucleusApplication and DecoratedWindow
    implementation("dev.nucleusframework:nucleus.nucleus-application:2.1.5")
    // Enable the Tao backend (Rust-native windowing)
    implementation("dev.nucleusframework:nucleus.decorated-window-tao:2.1.5")
    // Registry persistence (repo list + tags/notes) as JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // Cross-platform native directory picker (XDG portal on Linux, native on macOS/Windows).
    // Brings PlatformFile via its filekit-core dependency.
    implementation("io.github.vinceglb:filekit-dialogs:0.14.2")
    // OS desktop notifications (reminders + alerts) and a filesystem watcher (live rescans).
    implementation("dev.nucleusframework:nucleus.notification-common:2.1.5")
    implementation("dev.nucleusframework:nucleus.fs-watcher:2.1.5")
    // Human-readable durations ("about 5 weeks") for the notification outlook / age labels.
    implementation("nl.jacobras:Human-Readable:2.0.0-alpha02")
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
        packageVersion = "1.0.0"
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
