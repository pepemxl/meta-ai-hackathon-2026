package com.ppcoach.tracker.data

import org.json.JSONArray
import org.json.JSONObject

/** A finger stroke in normalized image coordinates (0..1). */
typealias Stroke = List<Pair<Double, Double>>

/**
 * A line the coach painted with a finger over a line of the pitch, when it is
 * faint, erased or the light is poor. [name] is the template line
 * (PitchTemplate.namedLines: touch_near, halfway, box_left…) or null when the
 * coach only says "this is a line": named lines also fix the pitch; every
 * stroke shows SAM what a line looks like.
 */
data class PaintedLine(val name: String?, val points: Stroke)

object Strokes {
    /** The API takes 2..64 points per stroke. */
    const val MAX_POINTS = 64

    /** A stroke shortened to at most [max] points, evenly spaced along its length (ends kept). */
    fun simplify(points: Stroke, max: Int = MAX_POINTS): Stroke {
        if (points.size <= max) return points
        val cum = DoubleArray(points.size)
        for (i in 1 until points.size) cum[i] = cum[i - 1] + kotlin.math.hypot(points[i].first - points[i - 1].first, points[i].second - points[i - 1].second)
        val total = cum.last()
        if (total <= 0) return listOf(points.first(), points.last())
        val out = mutableListOf<Pair<Double, Double>>()
        var j = 0
        for (k in 0 until max) {
            val target = total * k / (max - 1)
            while (j < points.size - 2 && cum[j + 1] < target) j++
            val span = (cum[j + 1] - cum[j]).takeIf { it > 0 } ?: 1.0
            val f = ((target - cum[j]) / span).coerceIn(0.0, 1.0)
            out += (points[j].first + (points[j + 1].first - points[j].first) * f) to (points[j].second + (points[j + 1].second - points[j].second) * f)
        }
        return out
    }

    /** Long enough to say something (a tap is not a stroke). */
    fun isStroke(points: Stroke, minLength: Double = 0.03): Boolean =
        points.size >= 2 && points.zipWithNext().sumOf { (a, b) -> kotlin.math.hypot(b.first - a.first, b.second - a.second) } >= minLength

    fun toJson(points: Stroke): JSONArray = JSONArray().apply {
        points.forEach { put(JSONObject().put("x", round(it.first)).put("y", round(it.second))) }
    }

    fun fromJson(arr: JSONArray?): Stroke {
        arr ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .map { it.optDouble("x") to it.optDouble("y") }
            .filter { it.first in 0.0..1.0 && it.second in 0.0..1.0 }
    }

    private fun round(v: Double) = Math.round(v.coerceIn(0.0, 1.0) * 100_000) / 100_000.0
}

/**
 * A reference picture of the pitch: a photo the coach took (better from where
 * the video is recorded, in daylight) or a frame of a video, with strokes over
 * its lines. It belongs to the match (the same pitch for every video) and goes
 * with each clip sent; SAM uses the strokes as examples of a line.
 */
data class PitchReference(
    val id: Int,
    val source: String,
    val tMs: Long = -1,
    val clipId: Int = 0,
    val strokes: List<Stroke> = emptyList(),
    val createdAt: Long = 0,
) {
    val fileName: String get() = "reference_%03d.jpg".format(id)

    fun toJson(): JSONObject = JSONObject().put("id", id).put("source", source).put("tMs", tMs).put("clipId", clipId).put("createdAt", createdAt)
        .put("strokes", JSONArray().apply { strokes.forEach { put(Strokes.toJson(it)) } })

    companion object {
        const val PHOTO = "photo"
        const val FRAME = "frame"
        /** The API keeps up to 6 per clip. */
        const val MAX = 6

        fun fromJson(o: JSONObject): PitchReference? {
            val id = o.optInt("id", 0).takeIf { it > 0 } ?: return null
            val arr = o.optJSONArray("strokes") ?: JSONArray()
            return PitchReference(
                id = id,
                source = o.optString("source").takeIf { it == PHOTO || it == FRAME } ?: PHOTO,
                tMs = o.optLong("tMs", -1),
                clipId = o.optInt("clipId", 0),
                strokes = (0 until arr.length()).map { Strokes.fromJson(arr.optJSONArray(it)) }.filter { it.size >= 2 },
                createdAt = o.optLong("createdAt", 0),
            )
        }
    }
}

/** The body of POST /clips/:id/calibration: the coach's frame (points and lines) and the match's reference pictures. */
object CalibrationRequest {
    /** Null when there is nothing to send. References are numbered 1..6 in order (PUT /clips/:id/references/:n). */
    fun build(touches: PitchTouches?, references: List<PitchReference>, size: Int = touches?.size ?: 11): JSONObject? {
        val frame = touches?.takeIf { it.points.isNotEmpty() || it.lines.isNotEmpty() }
        val refs = references.take(PitchReference.MAX)
        if (frame == null && refs.isEmpty()) return null
        val body = JSONObject().put("pitch", JSONObject().put("size", size))
        body.put("frames", JSONArray().apply { frame?.let { put(it.frameJson()) } })
        if (refs.isNotEmpty()) body.put("references", JSONArray().apply {
            refs.forEachIndexed { i, r ->
                put(JSONObject().put("n", i + 1).put("source", r.source).apply { if (r.tMs >= 0) put("t_ms", r.tMs) }
                    .put("strokes", JSONArray().apply { r.strokes.forEach { put(JSONObject().put("points", Strokes.toJson(Strokes.simplify(it)))) } }))
            }
        })
        return body
    }
}
