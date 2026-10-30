package com.ppcoach.tracker.data

import org.json.JSONArray
import org.json.JSONObject

/** Whether the worker computes the pitch (the coach's answer): lines, no_lines or off. "" = not asked yet. */
object PitchMode {
    const val LINES = "lines"
    const val NO_LINES = "no_lines"
    const val OFF = "off"
    val ALL = listOf(LINES, NO_LINES, OFF)
    fun valid(m: String?) = m in ALL
}

/**
 * The pitch in meters with every point and line named: the SAME names as
 * worker/ppcoachtracker_worker/calibration/template.py (a test compares them).
 * Origin at the center, x along the length, y across (y > 0 the far touchline).
 */
object PitchTemplate {
    data class Markings(val boxDepth: Double, val boxWidth: Double, val goalAreaDepth: Double, val goalAreaWidth: Double, val spot: Double, val circle: Double, val goal: Double)

    private val MARKINGS = mapOf(
        11 to Markings(16.5, 40.32, 5.5, 18.32, 11.0, 9.15, 7.32),
        7 to Markings(9.0, 24.0, 3.0, 12.0, 8.0, 6.0, 6.0),
    )
    private val DEFAULT_SIZE = mapOf(11 to (105.0 to 68.0), 7 to (60.0 to 40.0))

    fun markings(size: Int): Markings = MARKINGS[size] ?: throw IllegalArgumentException("size must be 11 or 7")
    fun dimensions(size: Int, length: Double? = null, width: Double? = null): Pair<Double, Double> {
        val (l, w) = DEFAULT_SIZE[size] ?: throw IllegalArgumentException("size must be 11 or 7")
        return (length ?: l) to (width ?: w)
    }

    fun points(size: Int, length: Double? = null, width: Double? = null): Map<String, Pair<Double, Double>> {
        val m = markings(size)
        val (l, w) = dimensions(size, length, width)
        val hx = l / 2
        val hy = w / 2
        val pts = linkedMapOf(
            "corner_far_left" to (-hx to hy), "corner_far_right" to (hx to hy),
            "corner_near_left" to (-hx to -hy), "corner_near_right" to (hx to -hy),
            "halfway_far" to (0.0 to hy), "halfway_near" to (0.0 to -hy), "center" to (0.0 to 0.0),
            "circle_far" to (0.0 to m.circle), "circle_near" to (0.0 to -m.circle),
            "circle_left" to (-m.circle to 0.0), "circle_right" to (m.circle to 0.0),
            "spot_left" to (-hx + m.spot to 0.0), "spot_right" to (hx - m.spot to 0.0),
        )
        for ((side, sx) in listOf("left" to -1.0, "right" to 1.0)) {
            val gx = sx * hx
            pts["post_far_$side"] = gx to m.goal / 2
            pts["post_near_$side"] = gx to -m.goal / 2
            for ((prefix, depth, bw) in listOf(Triple("box", m.boxDepth, m.boxWidth), Triple("goal_area", m.goalAreaDepth, m.goalAreaWidth))) {
                val inner = gx - sx * depth
                pts["${prefix}_far_${side}_goal"] = gx to bw / 2
                pts["${prefix}_far_$side"] = inner to bw / 2
                pts["${prefix}_near_${side}_goal"] = gx to -bw / 2
                pts["${prefix}_near_$side"] = inner to -bw / 2
            }
        }
        return pts
    }

    /** Segments to draw (the center circle apart: [circleRadius]). */
    fun lines(size: Int, length: Double? = null, width: Double? = null): List<Pair<Pair<Double, Double>, Pair<Double, Double>>> {
        val p = points(size, length, width)
        val (l, w) = dimensions(size, length, width)
        val out = mutableListOf(
            (-l / 2 to w / 2) to (l / 2 to w / 2), (-l / 2 to -w / 2) to (l / 2 to -w / 2),
            (-l / 2 to -w / 2) to (-l / 2 to w / 2), (l / 2 to -w / 2) to (l / 2 to w / 2),
            (0.0 to -w / 2) to (0.0 to w / 2),
        )
        for (side in listOf("left", "right")) {
            for (prefix in listOf("box", "goal_area")) {
                out += p.getValue("${prefix}_far_${side}_goal") to p.getValue("${prefix}_far_$side")
                out += p.getValue("${prefix}_far_$side") to p.getValue("${prefix}_near_$side")
                out += p.getValue("${prefix}_near_$side") to p.getValue("${prefix}_near_${side}_goal")
            }
        }
        return out
    }

    fun circleRadius(size: Int) = markings(size).circle

    /**
     * The straight lines with a name (the SAME names as template.py's lines, a
     * test compares them): what the coach says a painted line is.
     */
    fun namedLines(size: Int, length: Double? = null, width: Double? = null): Map<String, Pair<Pair<Double, Double>, Pair<Double, Double>>> {
        val m = markings(size)
        val (l, w) = dimensions(size, length, width)
        val hx = l / 2
        val hy = w / 2
        val out = linkedMapOf(
            "touch_far" to ((-hx to hy) to (hx to hy)), "touch_near" to ((-hx to -hy) to (hx to -hy)),
            "goal_left" to ((-hx to -hy) to (-hx to hy)), "goal_right" to ((hx to -hy) to (hx to hy)),
            "halfway" to ((0.0 to -hy) to (0.0 to hy)),
        )
        for ((side, sx) in listOf("left" to -1.0, "right" to 1.0)) {
            val gx = sx * hx
            for ((prefix, depth, bw) in listOf(Triple("box", m.boxDepth, m.boxWidth), Triple("goal_area", m.goalAreaDepth, m.goalAreaWidth))) {
                val inner = gx - sx * depth
                out["${prefix}_$side"] = (inner to -bw / 2) to (inner to bw / 2)
                out["${prefix}_${side}_far"] = (gx to bw / 2) to (inner to bw / 2)
                out["${prefix}_${side}_near"] = (gx to -bw / 2) to (inner to -bw / 2)
            }
        }
        return out
    }
}

data class Touch(val name: String, val x: Double, val y: Double)

/**
 * The coach's marks on one frame; [size] 11 or 7: points, and lines painted
 * over a line of the pitch (named, or null = "this is a line" for SAM).
 * [pitchId] is the saved pitch they were made on (0 = none) and [lengthM],
 * [widthM] its meters when measured (null = the typical size).
 */
data class PitchTouches(
    val size: Int = 11,
    val tMs: Long = 0,
    val points: List<Touch> = emptyList(),
    val lines: List<PaintedLine> = emptyList(),
    val pitchId: Int = 0,
    val lengthM: Double? = null,
    val widthM: Double? = null,
) {
    enum class State { NEED_MORE, NEED_FIFTH, READY }

    val dimensions: Pair<Double, Double> get() = PitchTemplate.dimensions(size, lengthM, widthM)

    /**
     * The same frame on pitch [p] (null = unsaved, of [size]): its size and
     * meters. The marks stay when the size is the same (the names do not change).
     */
    fun on(p: SavedPitch?, size: Int = p?.size ?: this.size): PitchTouches {
        val keep = size == this.size
        return PitchTouches(size, tMs, if (keep) points else emptyList(), if (keep) lines else emptyList(), p?.id ?: 0, p?.lengthM, p?.widthM)
    }

    /** Points and named lines: what fixes the pitch. */
    val marks: Int get() = points.size + lines.count { it.name != null }

    /** With exactly 4 any names fit (the homography passes through them): a fifth is needed. */
    val state: State get() = when {
        marks < 4 -> State.NEED_MORE
        marks == 4 -> State.NEED_FIFTH
        else -> State.READY
    }

    val isEmpty: Boolean get() = points.isEmpty() && lines.isEmpty()

    fun with(t: Touch): PitchTouches = copy(points = points.filterNot { it.name == t.name } + t)

    /** A named line replaces the one with its name; an unnamed one is added. */
    fun with(l: PaintedLine): PitchTouches = copy(lines = lines.filterNot { l.name != null && it.name == l.name } + l)

    /** This frame in POST /clips/:id/calibration. */
    fun frameJson(): JSONObject = JSONObject().put("t_ms", tMs).put("points", JSONArray().apply {
        points.forEach { put(JSONObject().put("name", it.name).put("x", it.x).put("y", it.y)) }
    }).apply {
        if (lines.isNotEmpty()) put("lines", JSONArray().apply {
            lines.forEach { put(JSONObject().put("name", it.name ?: JSONObject.NULL).put("points", Strokes.toJson(Strokes.simplify(it.points)))) }
        })
    }

    fun toJson(): JSONObject = JSONObject().put("size", size).put("tMs", tMs).put("pitchId", pitchId)
        .put("lengthM", lengthM ?: JSONObject.NULL).put("widthM", widthM ?: JSONObject.NULL).put("points", JSONArray().apply {
        points.forEach { put(JSONObject().put("name", it.name).put("x", it.x).put("y", it.y)) }
    }).put("lines", JSONArray().apply {
        lines.forEach { put(JSONObject().put("name", it.name ?: JSONObject.NULL).put("points", Strokes.toJson(it.points))) }
    })

    /** Preview homography (pitch meters → normalized image) with 4 or more marks, or null. */
    fun homography(): DoubleArray? {
        val tpl = PitchTemplate.points(size, lengthM, widthM)
        val named = PitchTemplate.namedLines(size, lengthM, widthM)
        val known = points.filter { it.name in tpl }
        val lns = lines.filter { it.name in named && it.points.size >= 2 }
        if (known.size + lns.size < 4) return null
        if (lns.isEmpty()) return Homography.fit(known.map { tpl.getValue(it.name) }, known.map { it.x to it.y })
        return Homography.fitPointsAndLines(known.map { tpl.getValue(it.name) }, known.map { it.x to it.y }, lns.map { named.getValue(it.name!!) to it.points })
    }

    companion object {
        fun fromJson(o: JSONObject?): PitchTouches? {
            o ?: return null
            val size = o.optInt("size", 11).takeIf { it == 11 || it == 7 } ?: 11
            val arr = o.optJSONArray("points") ?: JSONArray()
            val pts = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { Touch(it.optString("name"), it.optDouble("x"), it.optDouble("y")) }
                .filter { it.name.isNotEmpty() && it.x in 0.0..1.0 && it.y in 0.0..1.0 }
            val la = o.optJSONArray("lines") ?: JSONArray()
            val lines = (0 until la.length()).mapNotNull { la.optJSONObject(it) }
                .map { PaintedLine(if (it.isNull("name")) null else it.optString("name").ifEmpty { null }, Strokes.fromJson(it.optJSONArray("points"))) }
                .filter { it.points.size >= 2 }
            // The meters as a saved pitch reads them (both or neither, in range).
            val measures = SavedPitch.fromJson(JSONObject().put("id", 1).put("size", size).put("lengthM", o.opt("lengthM")).put("widthM", o.opt("widthM")))
            return PitchTouches(size, o.optLong("tMs", 0), pts, lines, o.optInt("pitchId", 0).coerceAtLeast(0), measures?.lengthM, measures?.widthM)
        }
    }
}

/** A small DLT for the preview (h33 = 1, normalized, least squares through the normal equations). */
object Homography {
    fun fit(src: List<Pair<Double, Double>>, dst: List<Pair<Double, Double>>): DoubleArray? {
        if (src.size < 4 || src.size != dst.size) return null
        val (ts, sn) = normalize(src)
        val (td, dn) = normalize(dst)
        val ata = Array(8) { DoubleArray(8) }
        val atb = DoubleArray(8)
        for (i in sn.indices) {
            val (x, y) = sn[i]
            val (u, v) = dn[i]
            val rows = listOf(
                doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y) to u,
                doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y) to v,
            )
            for ((r, b) in rows) for (a in 0 until 8) {
                atb[a] += r[a] * b
                for (c in 0 until 8) ata[a][c] += r[a] * r[c]
            }
        }
        val h = solve(ata, atb) ?: return null
        val hn = doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0)
        // Denormalize: H = Td⁻¹ · Hn · Ts
        val out = mul(mul(inverse(td) ?: return null, hn), ts)
        return out.map { it / out[8] }.toDoubleArray()
    }

    /**
     * Pitch → image from points and painted lines (pitch segment, image points
     * on it). Solved for the inverse (image → pitch), where a point on a line
     * is one linear row: lᵀ·G·u = 0; then inverted.
     */
    fun fitPointsAndLines(
        pitch: List<Pair<Double, Double>>, image: List<Pair<Double, Double>>,
        lines: List<Pair<Pair<Pair<Double, Double>, Pair<Double, Double>>, List<Pair<Double, Double>>>>,
    ): DoubleArray? {
        if (pitch.size + lines.size < 4 || pitch.size != image.size) return null
        val (ti, _) = normalize(image + lines.flatMap { it.second })
        val (tp, _) = normalize(pitch + lines.flatMap { listOf(it.first.first, it.first.second) })
        val ata = Array(8) { DoubleArray(8) }
        val atb = DoubleArray(8)
        fun add(r: DoubleArray, b: Double) {
            for (a in 0 until 8) {
                atb[a] += r[a] * b
                for (c in 0 until 8) ata[a][c] += r[a] * r[c]
            }
        }
        for (i in pitch.indices) {
            val (x, y) = map(ti, image[i])
            val (u, v) = map(tp, pitch[i])
            add(doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y), u)
            add(doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y), v)
        }
        for ((seg, pts) in lines) {
            val (ax, ay) = map(tp, seg.first)
            val (bx, by) = map(tp, seg.second)
            // The pitch line through both ends: (a, b, c) = (ax, ay, 1) × (bx, by, 1), unit normal.
            var la = ay - by
            var lb = bx - ax
            var lc = ax * by - ay * bx
            val n = kotlin.math.hypot(la, lb).takeIf { it > 0 } ?: continue
            la /= n; lb /= n; lc /= n
            // Each stroke weighs like two rows, however many points it has.
            val wgt = kotlin.math.sqrt(2.0 / pts.size)
            for (p in pts) {
                val (x, y) = map(ti, p)
                add(doubleArrayOf(la * x, la * y, la, lb * x, lb * y, lb, lc * x, lc * y).map { it * wgt }.toDoubleArray(), -lc * wgt)
            }
        }
        val g = solve(ata, atb) ?: return null
        val gn = doubleArrayOf(g[0], g[1], g[2], g[3], g[4], g[5], g[6], g[7], 1.0)
        // G = Tp⁻¹ · Gn · Ti (image → pitch); H = G⁻¹.
        val gm = mul(mul(inverse(tp) ?: return null, gn), ti)
        val h = inverse(gm) ?: return null
        return h.map { it / h[8] }.toDoubleArray()
    }

    private fun map(t: DoubleArray, p: Pair<Double, Double>): Pair<Double, Double> = (t[0] * p.first + t[2]) to (t[4] * p.second + t[5])

    fun apply(h: DoubleArray, x: Double, y: Double): Pair<Double, Double>? {
        val w = h[6] * x + h[7] * y + h[8]
        if (kotlin.math.abs(w) < 1e-12) return null
        return (h[0] * x + h[1] * y + h[2]) / w to (h[3] * x + h[4] * y + h[5]) / w
    }

    private fun normalize(p: List<Pair<Double, Double>>): Pair<DoubleArray, List<Pair<Double, Double>>> {
        val cx = p.sumOf { it.first } / p.size
        val cy = p.sumOf { it.second } / p.size
        val d = p.sumOf { kotlin.math.hypot(it.first - cx, it.second - cy) } / p.size
        val s = if (d > 0) kotlin.math.sqrt(2.0) / d else 1.0
        val t = doubleArrayOf(s, 0.0, -s * cx, 0.0, s, -s * cy, 0.0, 0.0, 1.0)
        return t to p.map { (it.first - cx) * s to (it.second - cy) * s }
    }

    private fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i -> (0 until 3).sumOf { k -> a[i / 3 * 3 + k] * b[k * 3 + i % 3] } }

    private fun inverse(m: DoubleArray): DoubleArray? {
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
        if (kotlin.math.abs(det) < 1e-15) return null
        return doubleArrayOf(
            (m[4] * m[8] - m[5] * m[7]) / det, (m[2] * m[7] - m[1] * m[8]) / det, (m[1] * m[5] - m[2] * m[4]) / det,
            (m[5] * m[6] - m[3] * m[8]) / det, (m[0] * m[8] - m[2] * m[6]) / det, (m[2] * m[3] - m[0] * m[5]) / det,
            (m[3] * m[7] - m[4] * m[6]) / det, (m[1] * m[6] - m[0] * m[7]) / det, (m[0] * m[4] - m[1] * m[3]) / det,
        )
    }

    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { i -> a[i].copyOf() + b[i] }
        for (col in 0 until n) {
            val piv = (col until n).maxByOrNull { kotlin.math.abs(m[it][col]) } ?: return null
            if (kotlin.math.abs(m[piv][col]) < 1e-12) return null
            val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
            for (r in 0 until n) if (r != col) {
                val f = m[r][col] / m[col][col]
                for (c in col..n) m[r][c] -= f * m[col][c]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }
}
