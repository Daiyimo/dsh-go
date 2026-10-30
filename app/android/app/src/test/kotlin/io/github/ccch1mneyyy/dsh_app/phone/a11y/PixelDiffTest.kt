package io.github.ccch1mneyyy.dsh_app.phone.a11y

import org.junit.Assert.*
import org.junit.Test

class PixelDiffTest {
    private fun uniform(color: Int) = { _: Int, _: Int -> color }

    @Test fun identicalFramesScoreZero() {
        val a = PixelDiff.thumb(1200, 2670, uniform(0xFF102030.toInt()))
        val b = PixelDiff.thumb(1200, 2670, uniform(0xFF102030.toInt()))
        assertEquals(0f, PixelDiff.diff(a, b), 1e-6f)
    }

    @Test fun blackAgainstWhiteScoresOne() {
        val black = PixelDiff.thumb(1200, 2670, uniform(0xFF000000.toInt()))
        val white = PixelDiff.thumb(1200, 2670, uniform(0xFFFFFFFF.toInt()))
        // 254/255, not 255/255: the integer gray weights (299/587/114 per mille) quantize pure
        // white to 254. The driver's 0.05 threshold is nowhere near this hair under the ceiling.
        assertEquals(1f, PixelDiff.diff(black, white), 0.01f)
    }

    @Test fun theThumbnailIsResolutionIndependent() {
        val small = PixelDiff.thumb(640, 1440, uniform(0xFF808080.toInt()))
        val large = PixelDiff.thumb(1200, 2670, uniform(0xFF808080.toInt()))
        assertEquals(0f, PixelDiff.diff(small, large), 1e-6f)
    }

    @Test fun theThumbnailIsGrayscaleSoOnlyLuminanceMatters() {
        // Same luminance, different hue: the screenshot must not look "changed" because a tap
        // only shifted a colored band by a pixel.
        val grey = PixelDiff.thumb(100, 100, uniform(0xFF808080.toInt()))
        val alsoGrey = PixelDiff.thumb(100, 100, uniform(0xFF7F807F.toInt()))
        assertTrue(PixelDiff.diff(grey, alsoGrey) < 0.01f)
        // Alpha is not part of the signal.
        val opaque = PixelDiff.thumb(100, 100, uniform(0xFF102030.toInt()))
        val transparent = PixelDiff.thumb(100, 100, uniform(0x00102030))
        assertEquals(0f, PixelDiff.diff(opaque, transparent), 1e-6f)
    }

    @Test fun unusableFramesCountAsChangedSoTheTapIsNeverDeclaredVerified() {
        assertEquals(1f, PixelDiff.diff(IntArray(0), IntArray(0)), 0f)
        assertEquals(1f, PixelDiff.diff(IntArray(PixelDiff.THUMB_W * PixelDiff.THUMB_H), IntArray(10)), 0f)
        assertTrue(PixelDiff.diff(PixelDiff.thumb(100, 100, uniform(0xFF000000.toInt())), IntArray(0)) > 0.9f)
    }
}
