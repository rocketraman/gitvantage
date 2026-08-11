// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.app

import de.infix.testBalloon.framework.core.testSuite

/**
 * The zoom arithmetic in [UiScale], which is pure precisely so it can be checked without a display
 * attached — and without touching the real registry.json, which is what [UiScale.zoomTo] writes to
 * and why nothing here calls it.
 */
val UiScaleZoom by testSuite {

    test("the default zoom leaves the reported density exactly alone") {
        // Identity, not "close enough". [UiScale.Provide] always provides, so at 100% it hands
        // downstream a Density equal to the one it was given — which is what makes the default
        // cost nothing: an equal value invalidates no reader. A rounding error here would silently
        // re-lay-out the whole window on every recomposition of the provider.
        assert(UiScale.effectiveDensity(2.0f, 100) == 2.0f)
        assert(UiScale.effectiveDensity(1.0f, 100) == 1.0f)
        assert(UiScale.effectiveDensity(1.5f, 100) == 1.5f)
    }

    test("zooming scales the density the display reported") {
        assert(UiScale.effectiveDensity(1.0f, 150) == 1.5f)
        assert(UiScale.effectiveDensity(2.0f, 50) == 1.0f)
        assert(UiScale.effectiveDensity(2.0f, 200) == 4.0f)
    }

    test("every offered step is one the app will actually apply") {
        // A step the clamp would rewrite is a picker row that lies about what it does.
        UiScale.STEPS.forEach { step ->
            assert(UiScale.clampPercent(step) == step)
        }
    }

    test("a zoom outside the usable range is clamped rather than honoured") {
        // registry.json is meant to be hand-editable, so these are reachable without the picker.
        assert(UiScale.clampPercent(0) == UiScale.MIN_PERCENT)
        assert(UiScale.clampPercent(-100) == UiScale.MIN_PERCENT)
        assert(UiScale.clampPercent(10_000) == UiScale.MAX_PERCENT)
    }

    test("a clamped zoom is applied at its clamped value, not its stored one") {
        assert(UiScale.effectiveDensity(1.0f, 0) == UiScale.MIN_PERCENT / 100f)
        assert(UiScale.effectiveDensity(1.0f, 10_000) == UiScale.MAX_PERCENT / 100f)
    }
}
