package com.ppcoach.tracker.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What the worker produced for a clip (worker/README.md), as the app shows it. */
data class ClipAnalysis(
    val segmenter: String,
    val analyst: String,
    val samModel: String,
    val museModel: String,
    val framesSent: Int,
    val calls: Int,
    val summary: String,
    val home: String,
    val away: String,
    val homeColor: String,
    val awayColor: String,
    val durationMs: Long,
    val chunks: Int,
    val chunkSeconds: Int,
    val ballSeenRatio: Double,
    val possessionChanges: Int,
    val teams: Map<String, TeamStats>,
    val insights: List<Insight>,
    val notes: List<String>,
    val limitations: List<String>,
    val createdAt: String,
    /** Metrics in meters on the pitch, or null without a computed pitch. */
    val pitch: PitchStats? = null,
) {
    val isSimulator: Boolean get() = segmenter == "sam_simulator"

    data class TeamStats(
        val playersPerFrame: Double,
        val tracks: Int,
        val possession: Double?,
        val longestPossessionMs: Long,
        val pressersPerFrame: Double?,
        val spreadX: Double?,
        val spreadY: Double?,
        /** 8 rows × 12 columns, or null if absent, of another size or empty. */
        val heatmap: List<List<Double>>?,
    )

    data class PitchStats(
        val lengthM: Double,
        val widthM: Double,
        val approximate: Boolean,
        val seconds: Double,
        val framesWithHomography: Double,
        val teams: Map<String, PitchTeam>,
    ) {
        /** 11 or 7, from the length (the template the worker used). */
        val size: Int get() = if (lengthM > 80) 11 else 7
    }

    data class PitchTeam(
        val metersPerMinute: Double?,
        val speedP95: Double?, // m/s
        val lengthM: Double?,
        val widthM: Double?,
        val heatmap: List<List<Double>>?, // 8×12, the far touchline on top
    )

    data class Insight(
        val title: String,
        val description: String,
        val severity: Severity,
        val startMs: Long,
        val endMs: Long,
        val recommendation: String,
    )

    /** From most to least important: IMPORTANT, TO REVIEW, DETAIL, DATA. */
    enum class Severity(val key: String) {
        HIGH("high"), MEDIUM("medium"), LOW("low"), INFO("info");

        companion object {
            fun fromKey(k: String?) = entries.firstOrNull { it.key == k } ?: INFO
        }
    }

    companion object {
        const val HEAT_ROWS = 8
        const val HEAT_COLS = 12

        /**
         * Accepts the response of GET /clips/:id/analysis ({…, result: {…}}) or
         * the result alone. Missing fields stay empty. A body that is not an
         * analysis throws IllegalArgumentException.
         */
        fun parse(text: String): ClipAnalysis {
            val root = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw IllegalArgumentException("not JSON")
            }
            val r = root.optJSONObject("result") ?: root
            if (!r.has("teams") && !r.has("insights") && !r.has("summary")) throw IllegalArgumentException("not an analysis")
            val backends = r.optJSONObject("backends") ?: JSONObject()
            val usage = backends.optJSONObject("usage") ?: JSONObject()
            val match = r.optJSONObject("match") ?: JSONObject()
            val video = r.optJSONObject("video") ?: JSONObject()
            val teamsJson = r.optJSONObject("teams") ?: JSONObject()
            val teams = listOf("A", "B").mapNotNull { t -> teamsJson.optJSONObject(t)?.let { t to team(it) } }.toMap()
            val insights = (r.optJSONArray("insights") ?: JSONArray()).let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                    Insight(
                        title = it.optString("title"),
                        description = it.optString("description"),
                        severity = Severity.fromKey(it.optString("severity")),
                        startMs = it.optLong("t_start_ms"),
                        endMs = it.optLong("t_end_ms"),
                        recommendation = it.optString("recommendation"),
                    )
                }.sortedBy { it.severity.ordinal }
            }
            return ClipAnalysis(
                segmenter = root.optString("segmenter").ifEmpty { backends.optString("segmenter") },
                analyst = root.optString("analyst").ifEmpty { backends.optString("analyst") },
                samModel = backends.optString("sam_model"),
                museModel = backends.optString("muse_spark_model"),
                framesSent = usage.optInt("frames_sent"),
                calls = usage.optInt("calls"),
                summary = r.optString("summary"),
                home = match.optString("home"),
                away = match.optString("away"),
                homeColor = match.optString("home_color"),
                awayColor = match.optString("away_color"),
                durationMs = video.optLong("duration_ms"),
                chunks = video.optInt("chunks"),
                chunkSeconds = video.optInt("chunk_seconds"),
                ballSeenRatio = r.optJSONObject("ball")?.optDouble("seen_ratio", 0.0) ?: 0.0,
                possessionChanges = r.optInt("possession_changes"),
                teams = teams,
                insights = insights,
                notes = strings(r.optJSONArray("notes")),
                limitations = strings(r.optJSONArray("limitations")),
                createdAt = root.optString("created_at").ifEmpty { r.optString("generated_at") },
                pitch = r.optJSONObject("pitch")?.let(::pitch),
            )
        }

        private fun strings(arr: JSONArray?): List<String> = arr?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()

        private fun optDouble(o: JSONObject?, key: String): Double? = if (o == null || o.isNull(key) || !o.has(key)) null else o.optDouble(key).takeUnless { it.isNaN() }

        private fun team(o: JSONObject): TeamStats {
            val shape = o.optJSONObject("shape")
            return TeamStats(
                playersPerFrame = o.optDouble("players_per_frame", 0.0),
                tracks = o.optInt("tracks"),
                possession = optDouble(o, "possession"),
                longestPossessionMs = o.optLong("longest_possession_ms"),
                pressersPerFrame = optDouble(o, "pressers_per_frame"),
                spreadX = optDouble(shape, "spread_x"),
                spreadY = optDouble(shape, "spread_y"),
                heatmap = heatmap(o.optJSONObject("heatmap")),
            )
        }

        private fun pitch(o: JSONObject): PitchStats? {
            val length = o.optDouble("length_m", 0.0)
            val width = o.optDouble("width_m", 0.0)
            if (length <= 0 || width <= 0) return null
            val teams = o.optJSONObject("teams") ?: JSONObject()
            return PitchStats(
                lengthM = length,
                widthM = width,
                approximate = o.optBoolean("approximate", true),
                seconds = o.optDouble("seconds", 0.0),
                framesWithHomography = o.optDouble("frames_with_homography", 0.0),
                teams = listOf("A", "B").mapNotNull { t ->
                    teams.optJSONObject(t)?.let { m ->
                        val shape = m.optJSONObject("shape")
                        t to PitchTeam(optDouble(m, "meters_per_minute"), optDouble(m, "speed_p95"), optDouble(shape, "length"), optDouble(shape, "width"), heatmap(m.optJSONObject("heatmap")))
                    }
                }.toMap(),
            )
        }

        private fun heatmap(o: JSONObject?): List<List<Double>>? {
            val values = o?.optJSONArray("values") ?: return null
            if (values.length() != HEAT_ROWS) return null
            val rows = (0 until HEAT_ROWS).map { i ->
                val row = values.optJSONArray(i) ?: return null
                if (row.length() != HEAT_COLS) return null
                (0 until HEAT_COLS).map { row.optDouble(it, 0.0) }
            }
            return if (rows.all { r -> r.all { it == 0.0 } }) null else rows
        }

        /** The local copy: files/analyses/clip_<local id>_<server id>.json. */
        fun copyFile(filesDir: File, clip: Clip): File = File(File(filesDir, "analyses"), "clip_${clip.id}_${clip.remoteId}.json")

        fun deleteCopies(filesDir: File, clipId: Int) {
            File(filesDir, "analyses").listFiles { f -> f.name.startsWith("clip_${clipId}_") }?.forEach { it.delete() }
        }
    }
}
