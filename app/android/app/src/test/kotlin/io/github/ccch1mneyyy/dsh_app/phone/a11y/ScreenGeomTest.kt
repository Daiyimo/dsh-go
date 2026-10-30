package io.github.ccch1mneyyy.dsh_app.phone.a11y

import org.junit.Assert.*
import org.junit.Test

/**
 * The 0-1000 contract the model is held to. The failure this pins down: on a 1200x2670 phone a
 * pixel point (996,215) read as a normalized point lands 199px right and 359px down and nothing
 * complains, so the model keeps tapping the same wrong spot and only sees diff=0.
 */
class ScreenGeomTest {
    @Test fun theTwoEndsAreTheTwoScreenEdges() {
        for (dim in listOf(2, 999, 1200, 1300, 2670)) {
            assertEquals(0, ScreenGeom.normToPx(0, dim))
            assertEquals(dim - 1, ScreenGeom.normToPx(ScreenGeom.NORM, dim))
            assertEquals(0, ScreenGeom.pxToNorm(0, dim))
            assertEquals(ScreenGeom.NORM, ScreenGeom.pxToNorm(dim - 1, dim))
        }
        // A one-pixel axis has no interior: every normalized value clamps onto its only pixel,
        // and that pixel is both edges at once, so pxToNorm reports the far one.
        assertEquals(0, ScreenGeom.normToPx(0, 1))
        assertEquals(0, ScreenGeom.normToPx(ScreenGeom.NORM, 1))
        assertEquals(ScreenGeom.NORM, ScreenGeom.pxToNorm(0, 1))
    }

    @Test fun outOfRangeValuesClampInsteadOfWrapping() {
        assertEquals(0, ScreenGeom.normToPx(-500, 1200))
        assertEquals(1199, ScreenGeom.normToPx(5000, 1200))
        assertEquals(0, ScreenGeom.pxToNorm(-5, 1200))
        assertEquals(ScreenGeom.NORM, ScreenGeom.pxToNorm(1200, 1200))
        assertFalse(ScreenGeom.inRange(-1))
        assertFalse(ScreenGeom.inRange(ScreenGeom.NORM + 1))
        assertTrue(ScreenGeom.inRange(0))
        assertTrue(ScreenGeom.inRange(ScreenGeom.NORM))
    }

    @Test fun theMappingIsMonotoneAndRoundTripsWithinOneUnit() {
        for (dim in listOf(1200, 2670, 1300)) {
            var previous = -1
            for (v in 0..ScreenGeom.NORM) {
                val px = ScreenGeom.normToPx(v, dim)
                assertTrue("dim=$dim v=$v px=$px", px >= previous)
                previous = px
                assertTrue("dim=$dim v=$v", kotlin.math.abs(ScreenGeom.pxToNorm(px, dim) - v) <= 1)
            }
        }
    }

    @Test fun aPixelPointMistakenForNormalizedStillLandsOnScreen() {
        // The historical mistake, kept as a regression: 996 as a fraction of 1200 is 0.83, but as a
        // normalized value it is 0.996 — near the right edge, not the middle. The tap still lands
        // on screen (nothing raises), which is exactly why the tool description must scream
        // "not pixels": the clamp hides the unit error instead of reporting it.
        assertEquals(1194, ScreenGeom.normToPx(996, 1200))
        assertEquals(574, ScreenGeom.normToPx(215, 2670))
    }
}
