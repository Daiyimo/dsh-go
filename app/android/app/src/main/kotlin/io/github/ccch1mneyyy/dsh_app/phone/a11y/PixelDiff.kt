package io.github.ccch1mneyyy.dsh_app.phone.a11y

import kotlin.math.abs

/**
 * "Did the screen change?" for post-tap verification: mean absolute grayscale difference of two
 * thumbnails, normalized to 0..1. Small (36x80) and resolution-independent, so it runs on the
 * phone after every point tap: a page that scrolled or opened scores high, a page that ignored the
 * tap scores near 0.
 *
 * Pure Kotlin on purpose (bit math instead of android.graphics.Color) so the threshold behaviour
 * is unit-testable on the JVM; the Android side only supplies the pixel sampler.
 */
object PixelDiff {
    const val THUMB_W = 36
    const val THUMB_H = 80

    /** Samples [width]x[height] ARGB pixels down to a grayscale thumbnail (0..255 per cell). */
    fun thumb(width: Int, height: Int, sample: (x: Int, y: Int) -> Int): IntArray {
        val out = IntArray(THUMB_W * THUMB_H)
        if (width <= 0 || height <= 0) return out
        for (y in 0 until THUMB_H) {
            val sy = y * (height - 1) / (THUMB_H - 1)
            for (x in 0 until THUMB_W) {
                val sx = x * (width - 1) / (THUMB_W - 1)
                val c = sample(sx, sy)
                out[y * THUMB_W + x] =
                    ((c shr 16) and 0xFF) * 299 / 1000 + ((c shr 8) and 0xFF) * 587 / 1000 + (c and 0xFF) * 114 / 1000
            }
        }
        return out
    }

    /** 0 = identical, 1 = black vs white everywhere. */
    fun diff(a: IntArray, b: IntArray): Float {
        if (a.isEmpty() || a.size != b.size) return 1f
        var sum = 0L
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum.toFloat() / (a.size * 255)
    }
}
