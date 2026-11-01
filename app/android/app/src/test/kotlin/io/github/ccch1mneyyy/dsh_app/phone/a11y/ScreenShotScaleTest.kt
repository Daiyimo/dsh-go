package io.github.ccch1mneyyy.dsh_app.phone.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The failure this pins down: a full 1200x2670 screen went out at 584x1300, a mini-program submenu
 * label ("特勤处") came back as "no such text", and a zoomed read of that same label came back at
 * native size — a 260x90 crop with nothing enlarged — so it was no more readable than before.
 */
class ScreenShotScaleTest {
    @Test fun aWholeScreenIsShrunkToTheLongSideCap() {
        val factor = ScreenShooter.scaleFactor(1200, 2670, zoomed = false)
        assertEquals(1_300f / 2_670f, factor, 0.0001f)
        assertEquals(1300, (2670 * factor).toInt())
        // A screen already inside the cap is not touched, and not enlarged either.
        assertEquals(1f, ScreenShooter.scaleFactor(1080, 600, zoomed = false), 0f)
    }

    @Test fun aCropOfSmallPrintIsEnlargedUntilItCanBeRead() {
        // 260x90 is what a small menu label looks like: three times bigger is the cap.
        assertEquals(3f, ScreenShooter.scaleFactor(260, 90, zoomed = true), 0.0001f)
        // 400x120: enlarged by 900/400 = 2.25x, still under the cap.
        assertEquals(2.25f, ScreenShooter.scaleFactor(400, 120, zoomed = true), 0.0001f)
        // A big crop keeps its own pixels.
        assertEquals(1f, ScreenShooter.scaleFactor(1100, 900, zoomed = true), 0f)
        // Past the cap it is shrunk like any other shot.
        assertTrue(ScreenShooter.scaleFactor(2400, 1080, zoomed = true) < 1f)
    }

    @Test fun aDegenerateAxisDoesNotDivideByZero() {
        assertTrue(ScreenShooter.scaleFactor(0, 0, zoomed = true) > 0f)
    }
}
