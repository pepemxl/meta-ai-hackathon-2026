package com.ppcoach.tracker.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * The play area the coach marks on a still camera (a tripod or a panorama):
 * the pitch's corners, and more points where a panorama bends its lines, in
 * order around it; [margin] widens it outward as a fraction of the frame
 * height (the same distance across and along). The worker takes it as the
 * pitch of every frame: people whose feet are outside it (stands, benches,
 * the running track) are dropped, and a wide video is cut into tiles over it
 * (worker/ppcoachtracker_worker/pitch.py from_play_area). [tMs] is the frame
 * it was marked on.
 */
data class PlayArea(
    val points: List<Pair<Double, Double>>,
    val margin: Double = DEFAULT_MARGIN,
    val tMs: Long = 0,
) {
    val isValid: Boolean get() = points.size in MIN_POINTS..MAX_POINTS && points.all { (x, y) -> x in 0.0..1.0 && y in 0.0..1.0 } && margin in 0.0..MAX_MARGIN

    fun moved(i: Int, x: Double, y: Double): PlayArea =
        copy(points = points.mapIndexed { k, p -> if (k == i) x.coerceIn(0.0, 1.0) to y.coerceIn(0.0, 1.0) else p })

    /** A new point halfway along the side that starts at point [i] (to follow a bent line). */
    fun splitSide(i: Int): PlayArea {
        if (points.size >= MAX_POINTS || i !in points.indices) return this
        val a = points[i]
        val b = points[(i + 1) % points.size]
        return copy(points = points.subList(0, i + 1) + ((a.first + b.first) / 2 to (a.second + b.second) / 2) + points.subList(i + 1, points.size))
    }

    /** Without point [i], while at least [MIN_POINTS] remain. */
    fun without(i: Int): PlayArea = if (points.size > MIN_POINTS && i in points.indices) copy(points = points.filterIndexed { k, _ -> k != i }) else this

    /** In analysis.play_area of POST /clips. */
    fun apiJson(): JSONObject = JSONObject().put("points", JSONArray().apply {
        points.forEach { (x, y) -> put(JSONObject().put("x", PitchMask.round4(x)).put("y", PitchMask.round4(y))) }
    }).put("margin", PitchMask.round4(margin))

    fun toJson(): JSONObject = apiJson().put("tMs", tMs)

    /**
     * Inside the area widened by its margin, on a frame [aspect] = width / height
     * (as the worker decides it: x is scaled by the aspect before measuring).
     */
    fun contains(x: Double, y: Double, aspect: Double): Boolean {
        val pts = points.map { (px, py) -> px * aspect to py }
        if (PitchMask.pointInPolygon(x * aspect, y, pts)) return true
        return pts.indices.any { i -> segmentDistance(x * aspect, y, pts[i], pts[(i + 1) % pts.size]) <= margin }
    }

    companion object {
        const val MIN_POINTS = 4
        const val MAX_POINTS = 16
        const val DEFAULT_MARGIN = 0.03
        const val MAX_MARGIN = 0.15 // the API takes up to 0.25; more than this is not the pitch any more

        /** Where marking starts: a rectangle a little inside the frame, to drag onto the corners. */
        fun start(tMs: Long): PlayArea = PlayArea(listOf(0.1 to 0.15, 0.9 to 0.15, 0.9 to 0.85, 0.1 to 0.85), DEFAULT_MARGIN, tMs)

        fun fromJson(o: JSONObject?): PlayArea? {
            val arr = o?.optJSONArray("points") ?: return null
            val pts = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { it.optDouble("x") to it.optDouble("y") }
            val margin = o.optDouble("margin", DEFAULT_MARGIN).let { if (it.isNaN()) DEFAULT_MARGIN else it }
            return PlayArea(pts, margin, o.optLong("tMs", 0).coerceAtLeast(0)).takeIf { it.isValid }
        }

        private fun segmentDistance(x: Double, y: Double, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
            val dx = b.first - a.first
            val dy = b.second - a.second
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((x - a.first) * dx + (y - a.second) * dy) / len2).coerceIn(0.0, 1.0)
            return kotlin.math.hypot(x - a.first - t * dx, y - a.second - t * dy)
        }
    }
}
