package com.ppcoach.tracker.data

/**
 * Jersey colors of the teams. The key is English and never changes: it goes
 * as-is into the SAM prompts ("player in red jersey") and into the worker's
 * team classifier, which uses the same RGB.
 */
enum class JerseyColor(val key: String, val argb: Long) {
    RED("red", 0xFFE53935),
    WHITE("white", 0xFFF5F5F5),
    BLUE("blue", 0xFF1E5BD8),
    LIGHT_BLUE("light blue", 0xFF6EC6FF),
    BLACK("black", 0xFF212121),
    YELLOW("yellow", 0xFFFDD835),
    GREEN("green", 0xFF2E7D32),
    ORANGE("orange", 0xFFFB8C00),
    PURPLE("purple", 0xFF7B1FA2),
    GRAY("gray", 0xFF9E9E9E);

    companion object {
        fun fromKey(key: String?, default: JerseyColor = RED): JerseyColor = entries.firstOrNull { it.key == key } ?: default
    }
}

enum class ClipKind(val key: String) {
    PLAY("play"), HALF("half"), FULL("full"), TRAINING("training");

    companion object {
        fun fromKey(key: String?): ClipKind = entries.firstOrNull { it.key == key } ?: PLAY

        /** A clip of 5 minutes or more starts as a half; 70 or more as a full match. */
        fun forDuration(durationMs: Long): ClipKind = when {
            durationMs >= 70 * 60_000L -> FULL
            durationMs >= 5 * 60_000L -> HALF
            else -> PLAY
        }
    }
}

enum class ClipSource(val key: String) {
    CAMERA("camera"), IMPORT("import");

    companion object {
        fun fromKey(key: String?): ClipSource = entries.firstOrNull { it.key == key } ?: CAMERA
    }
}

/**
 * Where a clip is in its life. local → preparing (chunks) → uploading →
 * uploaded (= queued at the API) → segmenting → ready | failed.
 */
enum class ClipStatus(val key: String) {
    LOCAL("local"),
    PREPARING("preparing"),
    UPLOADING("uploading"),
    UPLOADED("uploaded"),
    SEGMENTING("segmenting"),
    READY("ready"),
    FAILED("failed");

    companion object {
        fun fromKey(key: String?): ClipStatus = entries.firstOrNull { it.key == key } ?: LOCAL

        /** The API's clip status → ours. */
        fun fromRemote(status: String?): ClipStatus? = when (status) {
            "registered", "uploading" -> UPLOADING
            "queued" -> UPLOADED
            "segmenting" -> SEGMENTING
            "ready" -> READY
            "failed" -> FAILED
            else -> null
        }
    }
}

/** Where the videos live (StorageLocations.kt). */
enum class StorageKind(val key: String) {
    PHONE("phone"), PRIVATE("private"), SD("sd");

    companion object {
        fun fromKey(key: String?): StorageKind = entries.firstOrNull { it.key == key } ?: PHONE
    }
}

data class Match(
    val id: Int,
    val home: String,
    val away: String,
    val homeColor: JerseyColor = JerseyColor.RED,
    val awayColor: JerseyColor = JerseyColor.BLUE,
    val date: String, // yyyy-MM-dd
    val venue: String = "",
    val notes: String = "",
    val createdAt: Long = 0,
    /** Last answer to "Do we compute the pitch?", used for the other videos of the match ("" = not asked). */
    val pitchMode: String = "",
    /** Pictures of the pitch with the coach's strokes over its lines (sent with every clip of the match). */
    val references: List<PitchReference> = emptyList(),
) {
    val folder: String get() = "match_%04d".format(id)
    val nextReferenceId: Int get() = (references.maxOfOrNull { it.id } ?: 0) + 1
}

data class Clip(
    val id: Int,
    val matchId: Int,
    val title: String,
    val kind: ClipKind = ClipKind.PLAY,
    val source: ClipSource = ClipSource.CAMERA,
    val createdAt: Long = 0,
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Double = 0.0,
    val sizeBytes: Long = 0,
    val notes: String = "",
    val starred: Boolean = false,
    val status: ClipStatus = ClipStatus.LOCAL,
    val storage: StorageKind = StorageKind.PHONE,
    val remoteId: Int = 0,
    /** Chunk profile of the analysis copy (set when the copy is prepared). */
    val chunkSeconds: Int = 0,
    val overlapMs: Int = 0,
    val proxyHeight: Int = 0,
    val chunkCount: Int = 0,
    /** Last error shown to the coach (upload or analysis), empty if none. */
    val error: String = "",
    /** lines | no_lines | off; "" = not asked yet (PitchMode). Fixed once sent. */
    val pitchMode: String = "",
    /** The coach's points on the pitch, or null. */
    val touches: PitchTouches? = null,
    /** Share of the analysis copy's frames where the pitch is seen (0..1), -1 = not measured. */
    val pitchShare: Double = -1.0,
) {
    val fileName: String get() = "clip_%04d.mp4".format(id)
}

data class Library(
    val matches: List<Match> = emptyList(),
    val clips: List<Clip> = emptyList(),
    val nextMatchId: Int = 1,
    val nextClipId: Int = 1,
) {
    fun match(id: Int): Match? = matches.firstOrNull { it.id == id }
    fun clip(id: Int): Clip? = clips.firstOrNull { it.id == id }
    fun clipsOf(matchId: Int): List<Clip> = clips.filter { it.matchId == matchId }.sortedByDescending { it.createdAt }

    fun addMatch(build: (id: Int) -> Match): Pair<Library, Match> {
        val m = build(nextMatchId)
        return copy(matches = matches + m, nextMatchId = nextMatchId + 1) to m
    }

    fun addClip(build: (id: Int) -> Clip): Pair<Library, Clip> {
        val c = build(nextClipId)
        return copy(clips = clips + c, nextClipId = nextClipId + 1) to c
    }

    fun updateClip(id: Int, change: (Clip) -> Clip): Library = copy(clips = clips.map { if (it.id == id) change(it) else it })
    fun updateMatch(id: Int, change: (Match) -> Match): Library = copy(matches = matches.map { if (it.id == id) change(it) else it })
    fun removeClip(id: Int): Library = copy(clips = clips.filterNot { it.id == id })
    fun removeMatch(id: Int): Library = copy(matches = matches.filterNot { it.id == id }, clips = clips.filterNot { it.matchId == id })
}
