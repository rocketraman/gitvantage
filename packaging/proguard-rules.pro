# ProGuard keep rules for the release build.
#
# The theme here is code that is only ever reached *indirectly* — via ServiceLoader,
# reflection, or dynamic proxies. ProGuard's shrinker can't see those references, so it
# deletes the classes and the app silently degrades instead of failing loudly.
#
# verifyReleaseBytecode (see build.gradle.kts) checks both of those failure modes on every
# release build: dangling META-INF/services registrations, and bytecode that won't verify.

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
