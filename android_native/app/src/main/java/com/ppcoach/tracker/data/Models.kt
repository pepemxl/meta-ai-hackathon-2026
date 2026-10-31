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
    /** The saved pitch where it is played (SavedPitch.id), 0 = none: the next clips start on it. */
    val pitchId: Int = 0,
    /** Last answer to "The camera stays still" (CameraMode), used for the other videos of the match. */
    val camera: String = "",
    /** The analysis report that unlocked the stats from the video, or null (docs/11-stats-jugadores.md). */
    val analysis: AnalysisReport? = null,
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
    /** static | moving (CameraMode); "" = the match's answer. Fixed once sent. */
    val camera: String = "",
    /** The coach's tags on the analysis: track id → squad number (a player can have several tracks). */
    val trackTags: Map<Int, Int> = emptyMap(),
    /** Seconds each tagged number was seen in this clip, from its analysis (StatsCalc.secondsByNumber). */
    val playerSeconds: Map<Int, Int> = emptyMap(),
    /** Meters each tagged number ran in this clip (only with the pitch computed). */
    val playerMeters: Map<Int, Int> = emptyMap(),
    /** Each tagged number's top speed in this clip, in tenths of m/s (only with the pitch computed). */
    val playerTopSpeed: Map<Int, Int> = emptyMap(),
) {
    val fileName: String get() = "clip_%04d.mp4".format(id)
}

data class Library(
    val matches: List<Match> = emptyList(),
    val clips: List<Clip> = emptyList(),
    val nextMatchId: Int = 1,
    val nextClipId: Int = 1,
    /** Pitches the coach saved to reuse them (name, 11 or 7, meters). */
    val pitches: List<SavedPitch> = emptyList(),
    val nextPitchId: Int = 1,
    /** The coach's squad (numbers 1..99, unique) and each player's stats per match. */
    val players: List<Player> = emptyList(),
    val nextPlayerId: Int = 1,
    val matchStats: List<PlayerMatchStats> = emptyList(),
) {
    fun match(id: Int): Match? = matches.firstOrNull { it.id == id }
    fun pitch(id: Int): SavedPitch? = if (id > 0) pitches.firstOrNull { it.id == id } else null

    /** Most recently used first, then by name. */
    val pitchesByUse: List<SavedPitch> get() = pitches.sortedWith(compareByDescending<SavedPitch> { it.lastUsedAt }.thenBy { it.name.lowercase() })

    /** Adds [p] (id 0) or replaces the one with its id. */
    fun savePitch(p: SavedPitch): Pair<Library, SavedPitch> {
        if (p.id > 0 && pitch(p.id) != null) return copy(pitches = pitches.map { if (it.id == p.id) p else it }) to p
        val added = p.copy(id = nextPitchId)
        return copy(pitches = pitches + added, nextPitchId = nextPitchId + 1) to added
    }

    /** Deletes a saved pitch; matches that pointed to it are left without one. */
    fun removePitch(id: Int): Library = copy(
        pitches = pitches.filterNot { it.id == id },
        matches = matches.map { if (it.pitchId == id) it.copy(pitchId = 0) else it },
    )
    fun clip(id: Int): Clip? = clips.firstOrNull { it.id == id }

    /** The camera of a clip as it is sent: its answer, else its match's, else moving. */
    fun cameraOf(clip: Clip): String =
        clip.camera.takeIf { CameraMode.valid(it) } ?: match(clip.matchId)?.camera?.takeIf { CameraMode.valid(it) } ?: CameraMode.MOVING
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
    fun removeMatch(id: Int): Library = copy(
        matches = matches.filterNot { it.id == id },
        clips = clips.filterNot { it.matchId == id },
        matchStats = matchStats.filterNot { it.matchId == id },
    )

    // ── squad and stats ──────────────────────────────────────────────────────

    fun player(id: Int): Player? = players.firstOrNull { it.id == id }
    fun playerByNumber(number: Int): Player? = players.firstOrNull { it.number == number }
    val playersByNumber: List<Player> get() = players.sortedBy { it.number }

    /** Adds [p] (id 0) or replaces the one with its id. A number out of 1..99 or taken by another player throws. */
    fun savePlayer(p: Player): Pair<Library, Player> {
        require(p.number in Player.NUMBERS) { "number out of range" }
        require(players.none { it.number == p.number && it.id != p.id }) { "number taken" }
        val clean = p.copy(alias = p.alias.trim().take(Player.MAX_ALIAS))
        if (clean.id > 0 && player(clean.id) != null) return copy(players = players.map { if (it.id == clean.id) clean else it }) to clean
        val added = clean.copy(id = nextPlayerId)
        return copy(players = players + added, nextPlayerId = nextPlayerId + 1) to added
    }

    /** Deletes a player and their stats in every match. */
    fun removePlayer(id: Int): Library = copy(players = players.filterNot { it.id == id }, matchStats = matchStats.filterNot { it.playerId == id })

    fun sheet(matchId: Int, playerId: Int): PlayerMatchStats? = matchStats.firstOrNull { it.matchId == matchId && it.playerId == playerId }
    fun sheetsOf(matchId: Int): List<PlayerMatchStats> = matchStats.filter { it.matchId == matchId }
    fun sheetsOfPlayer(playerId: Int): List<PlayerMatchStats> = matchStats.filter { it.playerId == playerId }

    /** Puts a squad player on a match's sheet (no-op if already there). */
    fun addToMatch(matchId: Int, playerId: Int): Library =
        if (sheet(matchId, playerId) != null || player(playerId) == null) this else copy(matchStats = matchStats + PlayerMatchStats(matchId, playerId))

    private fun updateSheet(matchId: Int, playerId: Int, change: (PlayerMatchStats) -> PlayerMatchStats): Library {
        val withSheet = addToMatch(matchId, playerId)
        return withSheet.copy(matchStats = withSheet.matchStats.map { if (it.matchId == matchId && it.playerId == playerId) change(it) else it })
    }

    /** The coach's value of [stat] (clamped), or null to go back to the analysis. */
    fun setStat(matchId: Int, playerId: Int, stat: Stat, value: Int?): Library =
        updateSheet(matchId, playerId) { s -> s.copy(coach = if (value == null) s.coach - stat else s.coach + (stat to stat.clamp(value))) }

    fun removeFromMatch(matchId: Int, playerId: Int): Library = copy(matchStats = matchStats.filterNot { it.matchId == matchId && it.playerId == playerId })

    /**
     * Applies a report to a match: each number goes to the squad player with it
     * (a new one is added as a midfielder) and its analysis values are set;
     * the coach's corrections are never touched. [only] limits which stats are
     * replaced (the minutes from the video replace only the minutes).
     */
    fun applyReport(matchId: Int, parsed: AnalysisReportParser.Parsed, via: String, now: Long, only: Set<Stat>? = null): Pair<Library, ImportSummary> {
        var lib = this
        val added = mutableListOf<Int>()
        for ((number, values) in parsed.players) {
            val player = lib.playerByNumber(number) ?: lib.savePlayer(Player(0, number, Position.MF, createdAt = now)).let { (l, p) ->
                lib = l
                added += number
                p
            }
            lib = lib.updateSheet(matchId, player.id) { s ->
                val kept = if (only == null) emptyMap() else s.analysis.filterKeys { it !in only }
                s.copy(analysis = kept + values.filterKeys { only == null || it in only })
            }
        }
        lib = lib.updateMatch(matchId) { it.copy(analysis = AnalysisReport(now, via, parsed.source, parsed.generatedAt)) }
        return lib to ImportSummary(parsed.players.size, added, parsed.unknownKeys, parsed.coachKeys)
    }

    /** What the video says of [number] over the clips of [matchIds]: time seen, meters and top speed. */
    fun videoOf(number: Int, matchIds: Set<Int>): PlayerVideo {
        val cs = clips.filter { it.matchId in matchIds }
        return PlayerVideo(
            seconds = cs.sumOf { it.playerSeconds[number] ?: 0 },
            meters = cs.mapNotNull { it.playerMeters[number] }.takeIf { it.isNotEmpty() }?.sum(),
            metersSeconds = cs.filter { number in it.playerMeters }.sumOf { it.playerSeconds[number] ?: 0 },
            topSpeed = cs.mapNotNull { it.playerTopSpeed[number] }.maxOrNull()?.let { it / 10.0 },
        )
    }

    /** The minutes of each tagged number over every clip of the match, from the video (via "video"). */
    fun syncVideoMinutes(matchId: Int, source: String, now: Long): Pair<Library, ImportSummary> {
        val seconds = clips.filter { it.matchId == matchId }.flatMap { it.playerSeconds.entries }.groupBy({ it.key }, { it.value }).mapValues { (_, v) -> v.sum() }
        val players = seconds.filterKeys { it in Player.NUMBERS }.mapValues { (_, s) -> mapOf(Stat.MINUTES to Stat.MINUTES.clamp(Math.round(s / 60.0).toInt())) }
        return applyReport(matchId, AnalysisReportParser.Parsed(source, "", players, emptyList(), emptyList()), "video", now, only = setOf(Stat.MINUTES))
    }
}
