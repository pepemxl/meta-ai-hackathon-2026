package com.ppcoach.tracker.data

import org.json.JSONObject

/**
 * A pitch the coach saved once to reuse it: its name, 11 or 7 and, when the
 * coach measured it, its length and width in meters. Without them the worker
 * uses the typical size (PitchTemplate.dimensions) and marks the meters as
 * approximate. Lives in library.json, on the phone only.
 */
data class SavedPitch(
    val id: Int,
    val name: String,
    val size: Int = 11,
    val lengthM: Double? = null,
    val widthM: Double? = null,
    val createdAt: Long = 0,
    /** Last time it was picked for a clip: the next clip starts on the most recent. */
    val lastUsedAt: Long = 0,
) {
    val measured: Boolean get() = lengthM != null && widthM != null
    val dimensions: Pair<Double, Double> get() = PitchTemplate.dimensions(size, lengthM, widthM)

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("size", size)
        .put("lengthM", lengthM ?: JSONObject.NULL).put("widthM", widthM ?: JSONObject.NULL)
        .put("createdAt", createdAt).put("lastUsedAt", lastUsedAt)

    companion object {
        /** The ranges POST /clips/:id/calibration accepts (length_m, width_m). */
        val LENGTH_RANGE = 20.0..130.0
        val WIDTH_RANGE = 15.0..100.0
        const val MAX_NAME = 60

        fun fromJson(o: JSONObject): SavedPitch? {
            val id = o.optInt("id", 0).takeIf { it > 0 } ?: return null
            val length = meters(o, "lengthM")?.takeIf { it in LENGTH_RANGE }
            val width = meters(o, "widthM")?.takeIf { it in WIDTH_RANGE }
            // Both or neither: one measure alone does not make a pitch.
            val both = length != null && width != null && length > width
            return SavedPitch(
                id = id,
                name = o.optString("name", "").take(MAX_NAME),
                size = o.optInt("size", 11).takeIf { it == 11 || it == 7 } ?: 11,
                lengthM = if (both) length else null,
                widthM = if (both) width else null,
                createdAt = o.optLong("createdAt", 0),
                lastUsedAt = o.optLong("lastUsedAt", 0),
            )
        }

        private fun meters(o: JSONObject, key: String): Double? =
            if (o.isNull(key)) null else o.optDouble(key).takeIf { !it.isNaN() }

        /** A measure typed by the coach ("60", "60.5" or "60,5"); null when empty or not a number. */
        fun parseMeters(text: String): Double? =
            text.trim().replace(',', '.').takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it.isFinite() }

        /**
         * What is wrong with the typed measures, or null when they can be saved:
         * both empty (typical size) or both in range, the length longer.
         */
        fun measuresError(length: String, width: String): MeasuresError? {
            val l = parseMeters(length)
            val w = parseMeters(width)
            if (length.isBlank() && width.isBlank()) return null
            if (l == null || w == null) return MeasuresError.BOTH
            if (l !in LENGTH_RANGE || w !in WIDTH_RANGE) return MeasuresError.RANGE
            if (l <= w) return MeasuresError.LONGER
            return null
        }
    }

    enum class MeasuresError { BOTH, RANGE, LONGER }
}
