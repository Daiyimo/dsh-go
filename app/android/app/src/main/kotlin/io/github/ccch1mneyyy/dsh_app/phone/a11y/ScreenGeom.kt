package io.github.ccch1mneyyy.dsh_app.phone.a11y

import kotlin.math.roundToInt

/**
 * The single coordinate contract between the model and the phone: a 0-1000 normalized space,
 * (0,0) = top-left, (1000,1000) = bottom-right.
 *
 * Why normalized and not pixels: the field notes this module ports record a 1200x2670 device where
 * a pixel point (996,215) was fed back as a normalized point, the tap landed at (1195,574) — 199 px
 * right and 359 px down — and nothing raised an error; the model, seeing the same "no change", kept
 * tapping the same place. Unit mix-ups are the dominant failure mode of coordinate tapping, so the
 * one conversion point lives here, is an exact inverse pair, and clamps instead of drifting.
 *
 * The span is `dim - 1`, not `dim`, so 0 and 1000 land exactly on the edges and
 * [pxToNorm] inverts [normToPx] for every value in 0..1000.
 */
object ScreenGeom {
    const val NORM = 1000

    fun normToPx(v: Int, dim: Int): Int {
        val limit = (dim - 1).coerceAtLeast(0)
        return ((v.toDouble() * limit / NORM).roundToInt()).coerceIn(0, limit)
    }

    fun pxToNorm(px: Int, dim: Int): Int {
        val limit = (dim - 1).coerceAtLeast(0)
        // A one-pixel axis has no interior: its only pixel is the far edge, so px 0 maps to NORM
        // and the NORM -> dim-1 -> NORM round trip holds for every dim, including 1.
        if (limit == 0) return NORM
        return ((px.toDouble() * NORM / limit).roundToInt()).coerceIn(0, NORM)
    }

    fun inRange(v: Int): Boolean = v in 0..NORM

    /**
     * Whether a [shotW]×[shotH] frame can be a picture of the [boxW]×[boxH] display at all: the same
     * shape within [tolerance] (12% by default, which absorbs a screenshot the ROM scaled and a
     * cutout a point or two off).
     *
     * Why this exists: a frame captured while the screen turns over arrives in the *other*
     * orientation. The scale derived from it ([ImageGeometry]) is still a positive number, so every
     * normalized point maps somewhere plausible and nothing raises an error — the tap just lands on
     * a different control. Games are where this bites, because they turn the screen on launch.
     */
    fun fits(boxW: Int, boxH: Int, shotW: Int, shotH: Int, tolerance: Double = 0.12): Boolean {
        if (boxW <= 0 || boxH <= 0 || shotW <= 0 || shotH <= 0) return false
        val picture = shotW.toDouble() / shotH
        val screen = boxW.toDouble() / boxH
        return kotlin.math.abs(picture - screen) <= screen * tolerance
    }
}
