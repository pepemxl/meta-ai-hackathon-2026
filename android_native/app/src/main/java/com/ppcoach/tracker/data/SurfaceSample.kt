package com.ppcoach.tracker.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * A sample of the pitch's grass or dirt that the coach took by tapping it on a
 * frame: the HSV ranges (as OpenCV: hue 0..180, saturation and value 0..255)
 * the worker then looks for instead of its fixed ones (analysis.surface_sample
 * of POST /clips). Each tap adds a patch; the ranges cover all of them.
 */
data class SurfaceSample(
    val hue: IntRange,
    val sat: IntRange,
    val value: IntRange,
    /** How many taps it covers. */
    val taps: Int = 1,
) {
    /** Whether a pixel is of the sampled surface. */
    fun matches(r: Int, g: Int, b: Int): Boolean {
        val (h, s, v) = hsv(r, g, b)
        return h.toInt() in hue && s.toInt() in sat && v.toInt() in value
    }

    /** This sample and another tap's, together. */
    fun plus(o: SurfaceSample) = SurfaceSample(
        minOf(hue.first, o.hue.first)..maxOf(hue.last, o.hue.last),
        minOf(sat.first, o.sat.first)..maxOf(sat.last, o.sat.last),
        minOf(value.first, o.value.first)..maxOf(value.last, o.value.last),
        taps + o.taps,
    )

    fun toJson(): JSONObject = JSONObject().put("hue", range(hue)).put("sat", range(sat)).put("val", range(value)).put("taps", taps)

    /** As POST /clips takes it (analysis.surface_sample). */
    fun apiJson(): JSONObject = JSONObject().put("hue", range(hue)).put("sat", range(sat)).put("val", range(value))

    companion object {
        const val MAX_TAPS = 8
        // Around the 5–95 % of a patch, so the rest of the pitch (shade, sun) still fits.
        private const val HUE_PAD = 4
        private const val SAT_PAD = 20
        private const val VAL_PAD = 30
        /** A patch with fewer pixels than this is not a sample. */
        const val MIN_PIXELS = 16
        /**
         * A patch whose hues or saturations spread wider than this took something
         * else (a jersey, a line): not a sample. Measured on grass and dirt taps:
         * hue 13 and saturation 34 at most; with a player in them, 167 and 135.
         */
        const val MAX_HUE_SPREAD = 24
        const val MAX_SAT_SPREAD = 60

        /** The sample of a patch of pixels (0xRRGGBB), or null when it is too small or mixes other colors. */
        fun fromPixels(rgb: IntArray): SurfaceSample? {
            if (rgb.size < MIN_PIXELS) return null
            val hs = IntArray(rgb.size)
            val ss = IntArray(rgb.size)
            val vs = IntArray(rgb.size)
            rgb.forEachIndexed { i, p ->
                val (h, s, v) = hsv((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
                hs[i] = h.toInt()
                ss[i] = s.toInt()
                vs[i] = v.toInt()
            }
            fun middle(a: IntArray): IntRange {
                a.sort()
                return a[(a.size * 0.05).toInt()]..a[((a.size - 1) * 0.95).toInt()]
            }
            fun pad(r: IntRange, pad: Int, max: Int) = (r.first - pad).coerceAtLeast(0)..(r.last + pad).coerceAtMost(max)
            val hue = middle(hs)
            val sat = middle(ss)
            if (hue.last - hue.first > MAX_HUE_SPREAD || sat.last - sat.first > MAX_SAT_SPREAD) return null
            return SurfaceSample(pad(hue, HUE_PAD, 180), pad(sat, SAT_PAD, 255), pad(middle(vs), VAL_PAD, 255))
        }

        fun fromJson(o: JSONObject?): SurfaceSample? {
            o ?: return null
            val hue = read(o.optJSONArray("hue"), 180) ?: return null
            val sat = read(o.optJSONArray("sat"), 255) ?: return null
            val value = read(o.optJSONArray("val"), 255) ?: return null
            return SurfaceSample(hue, sat, value, o.optInt("taps", 1).coerceIn(1, MAX_TAPS))
        }

        private fun read(a: JSONArray?, max: Int): IntRange? {
            if (a == null || a.length() != 2) return null
            val lo = a.optInt(0, -1)
            val hi = a.optInt(1, -1)
            return if (lo in 0..max && hi in lo..max) lo..hi else null
        }

        private fun range(r: IntRange) = JSONArray().put(r.first).put(r.last)

        /** HSV as OpenCV computes it for 8-bit images (as PitchMask.isGrass): hue 0..180. */
        fun hsv(r: Int, g: Int, b: Int): Triple<Double, Double, Double> {
            val rd = r.toDouble()
            val gd = g.toDouble()
            val bd = b.toDouble()
            val v = maxOf(maxOf(rd, gd), bd)
            val d = v - minOf(minOf(rd, gd), bd)
            val s = if (v > 0) d / v * 255.0 else 0.0
            var h = if (d > 0) {
                when (v) {
                    rd -> 60.0 * (gd - bd) / d
                    gd -> 120.0 + 60.0 * (bd - rd) / d
                    else -> 240.0 + 60.0 * (rd - gd) / d
                }
            } else 0.0
            if (h < 0) h += 360.0
            return Triple(h / 2.0, s, v)
        }
    }
}
