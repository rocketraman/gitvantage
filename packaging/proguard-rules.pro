# ProGuard keep rules for the release build.
#
# The theme here is code that is only ever reached *indirectly* — via ServiceLoader,
# reflection, or dynamic proxies. ProGuard's shrinker can't see those references, so it
# deletes the classes and the app silently degrades instead of failing loudly.
#
# verifyReleaseBytecode (see build.gradle.kts) checks both of those failure modes on every
# release build: dangling META-INF/services registrations, and bytecode that won't verify.
#
# Every rule below is one the release gates cannot prove unnecessary, so each stays until it can
# be retired against evidence rather than a changelog.
#
# The filesystem-watcher and JNA rules were removed on the Nucleus 2.1.9 upgrade ("obfuscation
# safety with JNI bridges") on the grounds that the dev.nucleusframework.fswatcher classes now
# survive unaided and smokeTestReleaseImage would catch it if they didn't. That was sound but, at
# the time, checked on Linux and Windows only: the release build could not produce a macOS image at
# all (the app-image layout was hard-coded to the Linux shape, and the dry-run had no valid version
# to give jpackage on macOS), so the gate the removal leaned on had never run there.
#
# Both are fixed now, and the removal has been re-checked with macOS actually building: the watcher
# delivers 3 events on all five matrix jobs with these rules absent. So they stay gone — this time
# on evidence from every platform that ships.
#
# Worth knowing if the watcher ever looks broken on macOS again: it is not necessarily ProGuard. A
# watch on a symlinked path (/var/folders temp dirs, and anything a user symlinks) registers fine
# and then delivers nothing, which presents exactly like a stripped callback. That cost two round
# trips through this file before it was pinned down.
#
# Nucleus ships exactly one consumer rule of its own, for Tao's MainDispatcherFactory
# (META-INF/proguard/ inside nucleus.decorated-window-tao). It covers none of the below.

# --- D-Bus: ServiceLoader, dynamic proxies, and reflective signal construction -------------
# The transport is found only through META-INF/services, so nothing references
# NativeTransportProvider statically. The shrinker removed the whole package and left the
# service file dangling; every D-Bus call then failed with "No transports found to connect to
# DBus", which is what made the native XDG file chooser fall back to the ugly Swing one.
#
# The rest of dbus-java is reflective end to end: remote objects are java.lang.reflect.Proxy
# instances, arguments are marshalled by reflecting over interface methods, and incoming
# *signals* are rebuilt by locating a matching constructor. Stripping the signal constructors
# gives "Could not find suitable constructor for class ...DBus$NameAcquired" — and the portal
# returns the folder you picked via a Response signal, so the chooser would open and then never
# hand back a path. Keeping the whole package is the safe option for a library like this.
-keep class org.freedesktop.dbus.** { *; }

# FileKit's portal implementation declares the D-Bus interfaces it proxies.
#
# Keep this one even though the smoke test's portal check passes without it: that check opens its
# own D-Bus connection, so it verifies dbus-java, not FileKit. If these classes go, FileKit simply
# falls back to the Swing chooser — no exception, no dangling service file, nothing any gate can
# see. That silent fallback is the exact bug that shipped, so this rule stays until the chooser
# itself can be driven end to end.
-keep class io.github.vinceglb.filekit.dialogs.platform.xdg.** { *; }

# --- SLF4J binding: also ServiceLoader ------------------------------------------------------
# slf4j-simple is discovered via META-INF/services/org.slf4j.spi.SLF4JServiceProvider and is
# referenced from nowhere else. Without it the app prints "No SLF4J providers were found" and
# then discards every message — which is precisely how the D-Bus failure above stayed invisible.
-keep class org.slf4j.simple.** { *; }

# --- Enums ---------------------------------------------------------------------------------
# The synthetic values()/valueOf() members and the backing $VALUES array are what make a class
# an *enum* at runtime. Shrunk away, Class.enumConstantDirectory() fails and anything resolving
# an enum constant by name breaks.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- Attributes needed for the reflection above to work ------------------------------------
# Signature: generic types, which dbus-java reads to marshal collection/variant arguments.
# *Annotation*: dbus-java dispatches on its own annotations.
# InnerClasses/EnclosingMethod: keeps nested-class relationships resolvable by reflection.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

# Compile-only annotations, never present at runtime.
-dontwarn com.google.errorprone.annotations.**
