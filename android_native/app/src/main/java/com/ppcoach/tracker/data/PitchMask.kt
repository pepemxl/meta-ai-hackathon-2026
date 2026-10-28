package com.ppcoach.tracker.data

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Which part of a frame is the pitch: the same as worker/ppcoachtracker_worker/pitch.py,
 * with the same grid of points, the same thresholds and the same arithmetic,
 * so the app and the worker agree (a shared fixture checks it).
 *
 * 160×90 grid in HSV (hue 0..180 as OpenCV); grass is hue 35–85, saturation
 * > 60, value > 40. A 5×5 closing, the largest blob, its convex hull
 * simplified to at most 12 points; less than 70 % grass inside is not a pitch.
 */
object PitchMask {
    const val METHOD = "hsv_v1"
    const val GRID_W = 160
    const val GRID_H = 90
    private const val HUE_MIN = 35.0
    private const val HUE_MAX = 85.0
    private const val MIN_SAT = 60.0
    private const val MIN_VAL = 40.0
    private const val CLOSE = 5
    const val MAX_VERTICES = 12
    private const val MIN_GRASS_IN_POLYGON = 0.70
    private const val MIN_BLOB = 0.02

    data class Result(val fraction: Double, val polygon: List<Double>) {
        val hasPitch: Boolean get() = polygon.size >= 6
    }

    /** Pixel column of each grid point: the center of each cell, truncated. */
    fun gridX(width: Int) = IntArray(GRID_W) { i -> ((i + 0.5) * width / GRID_W).toInt().coerceIn(0, width - 1) }
    fun gridY(height: Int) = IntArray(GRID_H) { j -> ((j + 0.5) * height / GRID_H).toInt().coerceIn(0, height - 1) }

    /** HSV as OpenCV computes it for 8-bit images. */
    fun isGrass(r: Int, g: Int, b: Int): Boolean {
        val rd = r.toDouble()
        val gd = g.toDouble()
        val bd = b.toDouble()
        val v = maxOf(maxOf(rd, gd), bd)
        val mn = minOf(minOf(rd, gd), bd)
        val d = v - mn
        val s = if (v > 0) d / v * 255.0 else 0.0
        var h = if (d > 0) {
            when (v) {
                rd -> 60.0 * (gd - bd) / d
                gd -> 120.0 + 60.0 * (bd - rd) / d
                else -> 240.0 + 60.0 * (rd - gd) / d
            }
        } else 0.0
        if (h < 0) h += 360.0
        h /= 2.0
        return h >= HUE_MIN && h <= HUE_MAX && s > MIN_SAT && v > MIN_VAL
    }

    /** The pitch of one frame from its grid: rgb[y * GRID_W + x] = 0xRRGGBB. */
    fun fromGrid(rgb: IntArray): Result {
        require(rgb.size == GRID_W * GRID_H)
        val grass = BooleanArray(rgb.size) { i -> isGrass((rgb[i] shr 16) and 0xFF, (rgb[i] shr 8) and 0xFF, rgb[i] and 0xFF) }
        val fraction = round4(grass.count { it }.toDouble() / grass.size)
        val ends = blobRowEnds(close(grass)) ?: return Result(fraction, emptyList())
        val hull = simplify(convexHull(ends.map { (x, y) -> x.toDouble() / (GRID_W - 1) to y.toDouble() / (GRID_H - 1) }))
        if (hull.size < 3) return Result(fraction, emptyList())
        var total = 0
        var inside = 0
        for (y in 0 until GRID_H) for (x in 0 until GRID_W) {
            if (pointInPolygon(x.toDouble() / (GRID_W - 1), y.toDouble() / (GRID_H - 1), hull)) {
                total++
                if (grass[y * GRID_W + x]) inside++
            }
        }
        if (total == 0 || inside.toDouble() / total < MIN_GRASS_IN_POLYGON) return Result(fraction, emptyList())
        return Result(fraction, hull.flatMap { listOf(round4(it.first), round4(it.second)) })
    }

    /** Like Python's round(x, 4): the exact binary value, half to even. */
    fun round4(v: Double): Double = BigDecimal(v).setScale(4, RoundingMode.HALF_EVEN).toDouble()

    /** Dilation (any) then erosion (all), CLOSE×CLOSE, ignoring what is outside the grid. */
    fun close(mask: BooleanArray): BooleanArray = morph(morph(mask, dilate = true), dilate = false)

    private fun morph(mask: BooleanArray, dilate: Boolean): BooleanArray {
        val r = CLOSE / 2
        val out = BooleanArray(mask.size)
        for (y in 0 until GRID_H) for (x in 0 until GRID_W) {
            var acc = !dilate
            loop@ for (yy in maxOf(0, y - r)..minOf(GRID_H - 1, y + r)) for (xx in maxOf(0, x - r)..minOf(GRID_W - 1, x + r)) {
                val v = mask[yy * GRID_W + xx]
                if (dilate && v) { acc = true; break@loop }
                if (!dilate && !v) { acc = false; break@loop }
            }
            out[y * GRID_W + x] = acc
        }
        return out
    }

    /** Leftmost and rightmost cell of each row of the largest 8-connected blob (ties: the first found by rows), or null. */
    fun blobRowEnds(mask: BooleanArray): List<Pair<Int, Int>>? {
        val label = IntArray(mask.size)
        var next = 0
        var best = 0
        var bestSize = 0
        val queue = IntArray(mask.size)
        for (start in mask.indices) {
            if (!mask[start] || label[start] != 0) continue
            next++
            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = next
            while (head < tail) {
                val c = queue[head++]
                val cx = c % GRID_W
                val cy = c / GRID_W
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = cx + dx
                    val ny = cy + dy
                    if (nx in 0 until GRID_W && ny in 0 until GRID_H) {
                        val n = ny * GRID_W + nx
                        if (mask[n] && label[n] == 0) {
                            label[n] = next
                            queue[tail++] = n
                        }
                    }
                }
            }
            if (tail > bestSize) {
                bestSize = tail
                best = next
            }
        }
        if (best == 0 || bestSize < MIN_BLOB * GRID_W * GRID_H) return null
        val ends = mutableListOf<Pair<Int, Int>>()
        for (y in 0 until GRID_H) {
            var first = -1
            var last = -1
            for (x in 0 until GRID_W) if (label[y * GRID_W + x] == best) {
                if (first < 0) first = x
                last = x
            }
            if (first >= 0) {
                ends += first to y
                ends += last to y
            }
        }
        return ends
    }

    /** Andrew's monotone chain, without collinear points (as pitch.convex_hull). */
    fun convexHull(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val pts = points.distinct().sortedWith(compareBy<Pair<Double, Double>> { it.first }.thenBy { it.second })
        if (pts.size < 3) return pts
        fun cross(o: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>) =
            (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)
        val lower = mutableListOf<Pair<Double, Double>>()
        for (p in pts) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeAt(lower.lastIndex)
            lower += p
        }
        val upper = mutableListOf<Pair<Double, Double>>()
        for (p in pts.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeAt(upper.lastIndex)
            upper += p
        }
        return lower.dropLast(1) + upper.dropLast(1)
    }

    /** Removes the vertex that loses the least area until at most [max] (ties: the first). */
    fun simplify(poly: List<Pair<Double, Double>>, max: Int = MAX_VERTICES): List<Pair<Double, Double>> {
        val p = poly.toMutableList()
        while (p.size > max) {
            val n = p.size
            var bestI = 0
            var bestA = Double.MAX_VALUE
            for (i in 0 until n) {
                val a = p[(i - 1 + n) % n]
                val b = p[i]
                val c = p[(i + 1) % n]
                val area = kotlin.math.abs((b.first - a.first) * (c.second - a.second) - (b.second - a.second) * (c.first - a.first)) / 2
                if (area < bestA) {
                    bestI = i
                    bestA = area
                }
            }
            p.removeAt(bestI)
        }
        return p
    }

    fun pointInPolygon(x: Double, y: Double, pts: List<Pair<Double, Double>>): Boolean {
        var inside = false
        for (i in pts.indices) {
            val (x1, y1) = pts[i]
            val (x2, y2) = pts[(i + 1) % pts.size]
            if ((y1 > y) != (y2 > y) && x < (x2 - x1) * (y - y1) / (y2 - y1) + x1) inside = !inside
        }
        return inside
    }

    /** The `pitch` section of a chunk YAML: rows (chunk frame, fraction, polygon). */
    fun yamlSection(rows: List<Triple<Int, Double, List<Double>>>): String = buildString {
        append("pitch:\n  method: \"$METHOD\"\n  grid: [$GRID_W, $GRID_H]\n  hue: [35, 85]\n")
        append("  with_pitch: ${rows.count { it.third.size >= 6 }}\n")
        append("  # [chunk frame, grass fraction, pitch polygon x1, y1, x2, y2, ... (0..1)]\n  frames:\n")
        for ((f, frac, poly) in rows) {
            append("    - [").append(f).append(", ").append(String.format(java.util.Locale.US, "%.4f", frac)).append(", [")
            append(poly.joinToString(", ") { String.format(java.util.Locale.US, "%.4f", it) })
            append("]]\n")
        }
    }
}
