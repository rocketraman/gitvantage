// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.smoke

import de.infix.testBalloon.framework.core.testSuite

/**
 * Pins the reflective contract the macOS chooser smoke check depends on.
 *
 * That check reaches FileKit's macOS bridge entirely by name, deliberately — referencing it
 * statically would pull the implementation into the image on every platform and defeat the point
 * of asking whether it is there. The cost of resolving by name is that a FileKit upgrade can
 * rename a method or change a signature and nothing complains at compile time: the check would
 * simply start reporting FAIL on macOS for a reason that has nothing to do with the image.
 *
 * These names resolve on any OS — the macOS classes ship in the same `filekit-dialogs-jvm` jar
 * everywhere — so this runs in ordinary CI on Linux and is exactly the part that can be verified
 * without a Mac. What it deliberately does not do is *initialise* `Foundation`: its initialiser
 * calls `Native.load("Foundation", …)`, which can only succeed on macOS. Whether that load works
 * is the smoke check's question, not this one's.
 */
val MacOsChooserBridgeContract by testSuite {

    val pkg = "io.github.vinceglb.filekit.dialogs.platform"

    /** `initialize = false`: resolve the class without running the JNA load in its initialiser. */
    fun load(name: String): Class<*> =
        Class.forName(name, false, Thread.currentThread().contextClassLoader)

    test("the macOS picker FileKit selects by platform is on the classpath") {
        val picker = load("$pkg.mac.MacOSFilePicker")

        assert(load("$pkg.PlatformFilePicker").isAssignableFrom(picker))
    }

    test("Foundation is a Kotlin object reachable through INSTANCE") {
        val foundation = load("$pkg.mac.foundation.Foundation")

        // Public members of an `internal` Kotlin object are not name-mangled — which is what lets
        // the check call them by their source names. If a FileKit release ever made these
        // `internal` themselves, the names would gain a module suffix and this would catch it.
        assert(foundation.getDeclaredField("INSTANCE").type == foundation)
    }

    test("the main-thread dispatch entry point has the signature the check calls") {
        val foundation = load("$pkg.mac.foundation.Foundation")

        val method = foundation.getMethod(
            "executeOnMainThread",
            Boolean::class.java,
            Boolean::class.java,
            Runnable::class.java,
        )

        assert(method.returnType == Void.TYPE)
    }

    test("the objc_msgSend entry point takes a class name, a selector and varargs") {
        val foundation = load("$pkg.mac.foundation.Foundation")

        val method = foundation.getMethod(
            "invoke",
            String::class.java,
            String::class.java,
            Array<Any>::class.java,
        )

        // The check reads the result as a Number to get the BOOL out of `[NSThread isMainThread]`.
        assert(Number::class.java.isAssignableFrom(method.returnType))
    }
}
