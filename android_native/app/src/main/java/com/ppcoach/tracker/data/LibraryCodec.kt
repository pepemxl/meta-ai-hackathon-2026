package com.ppcoach.tracker.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * library.json ↔ Library. Tolerant: unknown fields are ignored and unknown
 * values fall back to the default, so an older or newer app can read it.
 */
object LibraryCodec {
    const val VERSION = 1

    fun encode(lib: Library): String {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("nextMatchId", lib.nextMatchId)
        root.put("nextClipId", lib.nextClipId)
        root.put("nextPitchId", lib.nextPitchId)
        root.put("pitches", JSONArray().apply { lib.pitches.forEach { put(it.toJson()) } })
        root.put("matches", JSONArray().apply { lib.matches.forEach { put(encodeMatch(it)) } })
        root.put("clips", JSONArray().apply { lib.clips.forEach { put(encodeClip(it)) } })
        return root.toString(2)
    }

    /** Throws on text that is not a library (the store then sets the file aside). */
    fun decode(text: String): Library {
        val root = JSONObject(text)
        val matches = root.optJSONArray("matches")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::decodeMatch) } } ?: emptyList()
        val clips = root.optJSONArray("clips")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::decodeClip) } } ?: emptyList()
        // Ids are never reused, even if the counters were lost.
        val nextMatch = maxOf(root.optInt("nextMatchId", 1), (matches.maxOfOrNull { it.id } ?: 0) + 1)
        val nextClip = maxOf(root.optInt("nextClipId", 1), (clips.maxOfOrNull { it.id } ?: 0) + 1)
        val pitches = root.optJSONArray("pitches")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(SavedPitch::fromJson) } } ?: emptyList()
        val nextPitch = maxOf(root.optInt("nextPitchId", 1), (pitches.maxOfOrNull { it.id } ?: 0) + 1)
        return Library(matches, clips, nextMatch, nextClip, pitches, nextPitch)
    }

    private fun encodeMatch(m: Match) = JSONObject().apply {
        put("id", m.id)
        put("home", m.home)
        put("away", m.away)
        put("homeColor", m.homeColor.key)
        put("awayColor", m.awayColor.key)
        put("date", m.date)
        put("venue", m.venue)
        put("notes", m.notes)
        put("createdAt", m.createdAt)
        put("pitchMode", m.pitchMode)
        put("references", JSONArray().apply { m.references.forEach { put(it.toJson()) } })
        put("pitchId", m.pitchId)
        put("camera", m.camera)
    }

    private fun decodeMatch(o: JSONObject): Match? {
        val id = o.optInt("id", 0)
        if (id <= 0) return null
        return Match(
            id = id,
            home = o.optString("home", ""),
            away = o.optString("away", ""),
            homeColor = JerseyColor.fromKey(o.optString("homeColor"), JerseyColor.RED),
            awayColor = JerseyColor.fromKey(o.optString("awayColor"), JerseyColor.BLUE),
            date = o.optString("date", ""),
            venue = o.optString("venue", ""),
            notes = o.optString("notes", ""),
            createdAt = o.optLong("createdAt", 0),
            pitchMode = o.optString("pitchMode", "").takeIf { PitchMode.valid(it) } ?: "",
            references = o.optJSONArray("references")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(PitchReference::fromJson) } } ?: emptyList(),
            pitchId = o.optInt("pitchId", 0).coerceAtLeast(0),
            camera = o.optString("camera", "").takeIf { CameraMode.valid(it) } ?: "",
        )
    }

    private fun encodeClip(c: Clip) = JSONObject().apply {
        put("id", c.id)
        put("matchId", c.matchId)
        put("fileName", c.fileName)
        put("title", c.title)
        put("kind", c.kind.key)
        put("source", c.source.key)
        put("createdAt", c.createdAt)
        put("durationMs", c.durationMs)
        put("width", c.width)
        put("height", c.height)
        put("fps", c.fps)
        put("sizeBytes", c.sizeBytes)
        put("notes", c.notes)
        put("starred", c.starred)
        put("status", c.status.key)
        put("storage", c.storage.key)
        put("remoteId", c.remoteId)
        put("chunkSeconds", c.chunkSeconds)
        put("overlapMs", c.overlapMs)
        put("proxyHeight", c.proxyHeight)
        put("chunkCount", c.chunkCount)
        put("error", c.error)
        put("pitchMode", c.pitchMode)
        c.touches?.let { put("touches", it.toJson()) }
        put("pitchShare", c.pitchShare)
        put("camera", c.camera)
    }

    private fun decodeClip(o: JSONObject): Clip? {
        val id = o.optInt("id", 0)
        val matchId = o.optInt("matchId", 0)
        if (id <= 0 || matchId <= 0) return null
        return Clip(
            id = id,
            matchId = matchId,
            title = o.optString("title", ""),
            kind = ClipKind.fromKey(o.optString("kind")),
            source = ClipSource.fromKey(o.optString("source")),
            createdAt = o.optLong("createdAt", 0),
            durationMs = o.optLong("durationMs", 0),
            width = o.optInt("width", 0),
            height = o.optInt("height", 0),
            fps = o.optDouble("fps", 0.0).let { if (it.isNaN()) 0.0 else it },
            sizeBytes = o.optLong("sizeBytes", 0),
            notes = o.optString("notes", ""),
            starred = o.optBoolean("starred", false),
            status = ClipStatus.fromKey(o.optString("status")),
            storage = StorageKind.fromKey(o.optString("storage")),
            remoteId = o.optInt("remoteId", 0),
            chunkSeconds = o.optInt("chunkSeconds", 0),
            overlapMs = o.optInt("overlapMs", 0),
            proxyHeight = o.optInt("proxyHeight", 0),
            chunkCount = o.optInt("chunkCount", 0),
            error = o.optString("error", ""),
            pitchMode = o.optString("pitchMode", "").takeIf { PitchMode.valid(it) } ?: "",
            touches = PitchTouches.fromJson(o.optJSONObject("touches")),
            pitchShare = o.optDouble("pitchShare", -1.0).let { if (it.isNaN() || it > 1.0) -1.0 else it },
            camera = o.optString("camera", "").takeIf { CameraMode.valid(it) } ?: "",
        )
    }
}
