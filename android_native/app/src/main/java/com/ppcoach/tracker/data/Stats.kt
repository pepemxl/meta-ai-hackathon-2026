package com.ppcoach.tracker.data

import org.json.JSONObject

/**
 * The squad and the stats of each player in each match (docs/11-stats-jugadores.md).
 * Everything lives in library.json, on the phone: the alias never leaves it,
 * and the analysis report identifies a player only by number.
 */
enum class Position(val key: String) {
    GK("GK"), DF("DF"), MF("MF"), FW("FW");

    companion object {
        fun fromKey(key: String?): Position = entries.firstOrNull { it.key == key } ?: MF
    }
}

/** The keys are the contract with the report and with library.json: they never change. */
enum class Stat(val key: String, val fromAnalysis: Boolean, val max: Int = 999) {
    MINUTES("minutes", true, 130),
    GOALS("goals", false),
    ASSISTS("assists", false),
    SHOTS("shots", true),
    SHOTS_ON_TARGET("shots_on_target", true),
    PASSES_COMPLETED("passes_completed", true),
    PASSES_FAILED("passes_failed", true),
    KEY_PASSES("key_passes", true),
    DRIBBLES_COMPLETED("dribbles_completed", true),
    TACKLES("tackles", true),
    INTERCEPTIONS("interceptions", true),
    CLEARANCES("clearances", true),
    BLOCKS("blocks", true),
    DUELS("duels", true),
    FOULS("fouls", false),
    YELLOW_CARDS("yellow_cards", false, 2),
    RED_CARDS("red_cards", false, 1);

    fun clamp(v: Int): Int = v.coerceIn(0, max)

    companion object {
        fun fromKey(key: String?): Stat? = entries.firstOrNull { it.key == key }
    }
}

data class Player(
    val id: Int,
    val number: Int,
    val position: Position = Position.MF,
    /** Nickname or initials; optional and never sent anywhere. */
    val alias: String = "",
    val createdAt: Long = 0,
) {
    companion object {
        val NUMBERS = 1..99
        const val MAX_ALIAS = 24
    }
}

/** One player in one match: what the analysis says and what the coach entered. The coach wins. */
data class PlayerMatchStats(
    val matchId: Int,
    val playerId: Int,
    val analysis: Map<Stat, Int> = emptyMap(),
    val coach: Map<Stat, Int> = emptyMap(),
) {
    fun value(s: Stat): Int = coach[s] ?: analysis[s] ?: 0
    fun edited(s: Stat): Boolean = s in coach && s.fromAnalysis && s in analysis && coach[s] != analysis[s]
    val anyValue: Boolean get() = Stat.entries.any { value(it) != 0 }
}

/** When and from where a match got its analysis report ("file" imported, or "video" from tagged tracks). */
data class AnalysisReport(val receivedAt: Long, val via: String, val source: String, val generatedAt: String = "") {
    fun toJson(): JSONObject = JSONObject().put("receivedAt", receivedAt).put("via", via).put("source", source).put("generatedAt", generatedAt)

    companion object {
        fun fromJson(o: JSONObject?): AnalysisReport? =
            o?.let { AnalysisReport(it.optLong("receivedAt", 0), it.optString("via", "file"), it.optString("source", ""), it.optString("generatedAt", "")) }
    }
}

/**
 * What the video says of one player, from their tagged tracks: seconds seen,
 * meters run and top speed (m/s). Meters and speed exist only where the
 * worker computed the pitch; [metersSeconds] is the time they cover.
 */
data class PlayerVideo(val seconds: Int, val meters: Int?, val metersSeconds: Int, val topSpeed: Double?) {
    /** Meters per minute over the time with meters, or null. */
    val metersPerMinute: Double? get() = meters?.takeIf { metersSeconds > 0 }?.let { it * 60.0 / metersSeconds }
    val topSpeedKmh: Double? get() = topSpeed?.let { it * 3.6 }
    val isEmpty: Boolean get() = seconds == 0 && meters == null
}

/** What the coach sees after an import: which numbers were added to the squad and what was ignored. */
data class ImportSummary(val players: Int, val addedNumbers: List<Int>, val unknownKeys: List<String>, val coachKeysIgnored: List<String>)

object StatsCalc {
    data class Totals(val values: Map<Stat, Int>, val matches: Int, val minutesMatches: Int, val minutes: Int) {
        fun of(s: Stat): Int = values[s] ?: 0

        /** Completed / (completed + failed), or null without passes. */
        val passAccuracy: Double?
            get() = (of(Stat.PASSES_COMPLETED) + of(Stat.PASSES_FAILED)).takeIf { it > 0 }?.let { of(Stat.PASSES_COMPLETED).toDouble() / it }
    }

    /** A player's totals over [sheets] (any matches); a match counts as played with any non-zero value. */
    fun totals(sheets: List<PlayerMatchStats>): Totals {
        val values = Stat.entries.associateWith { s -> sheets.sumOf { it.value(s) } }
        val withMinutes = sheets.filter { it.value(Stat.MINUTES) > 0 }
        return Totals(values, sheets.count { it.anyValue }, withMinutes.size, withMinutes.sumOf { it.value(Stat.MINUTES) })
    }

    /**
     * [s] per 90 minutes, only over the matches with minutes, so a match with
     * goals but no report does not inflate it. Null without minutes.
     */
    fun per90(sheets: List<PlayerMatchStats>, s: Stat): Double? {
        val withMinutes = sheets.filter { it.value(Stat.MINUTES) > 0 }
        val minutes = withMinutes.sumOf { it.value(Stat.MINUTES) }
        if (minutes == 0) return null
        return withMinutes.sumOf { it.value(s) } * 90.0 / minutes
    }

    /** The team's totals in one match. */
    fun teamTotals(sheets: List<PlayerMatchStats>): Map<Stat, Int> = Stat.entries.associateWith { s -> sheets.sumOf { it.value(s) } }

    /** Stats from the video are locked in a match until it has a report; the coach's are always open. */
    fun locked(s: Stat, match: Match): Boolean = s.fromAnalysis && match.analysis == null

    /** Meters each tagged number ran in a clip's tracks; numbers without meters are left out. */
    fun metersByNumber(tracks: List<ClipAnalysis.TrackInfo>, tags: Map<Int, Int>): Map<Int, Int> =
        tracks.mapNotNull { t -> tags[t.id]?.let { n -> t.distanceM?.let { n to it } } }.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> Math.round(v.sum()).toInt() }

    /** Each tagged number's top speed over its tracks, in tenths of m/s. */
    fun topSpeedByNumber(tracks: List<ClipAnalysis.TrackInfo>, tags: Map<Int, Int>): Map<Int, Int> =
        tracks.mapNotNull { t -> tags[t.id]?.let { n -> t.topSpeed?.let { n to it } } }.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> Math.round(v.max() * 10).toInt() }

    /** Seconds each tagged number was seen in a clip's tracks (several tracks can be one player). */
    fun secondsByNumber(tracks: List<ClipAnalysis.TrackInfo>, tags: Map<Int, Int>): Map<Int, Int> =
        tracks.mapNotNull { t -> tags[t.id]?.let { it to t.seconds } }.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.sum().toInt() }
}

/** The analysis report imported from a JSON file (android_native/samples/analysis-report.example.json). */
object AnalysisReportParser {
    class ReportException(message: String) : Exception(message)

    data class Parsed(val source: String, val generatedAt: String, val players: Map<Int, Map<Stat, Int>>, val unknownKeys: List<String>, val coachKeys: List<String>)

    /** Version 1 only; invalid JSON, another version or a number out of 1..99 throws, and nothing changes. */
    fun parse(text: String): Parsed {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw ReportException("invalid JSON")
        }
        val version = root.optInt("version", -1)
        if (version != 1) throw ReportException("unsupported version $version")
        val arr = root.optJSONArray("players") ?: throw ReportException("no players")
        val players = linkedMapOf<Int, Map<Stat, Int>>()
        val unknown = sortedSetOf<String>()
        val coach = sortedSetOf<String>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: throw ReportException("player $i is not an object")
            val number = p.optInt("number", -1)
            if (number !in Player.NUMBERS) throw ReportException("number out of range: ${p.opt("number")}")
            val stats = p.optJSONObject("stats") ?: JSONObject()
            val values = linkedMapOf<Stat, Int>()
            for (key in stats.keys()) {
                val s = Stat.fromKey(key)
                when {
                    s == null -> unknown += key
                    !s.fromAnalysis -> coach += key
                    else -> values[s] = s.clamp(stats.optInt(key, 0))
                }
            }
            players[number] = (players[number].orEmpty()) + values
        }
        return Parsed(root.optString("source", ""), root.optString("generated_at", ""), players, unknown.toList(), coach.toList())
    }
}
