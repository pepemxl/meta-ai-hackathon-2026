package com.ppcoach.tracker.data

import java.util.Locale

/**
 * Chunk profile of the analysis copy (docs/12-videos-largos-chunks.md):
 * dev = 1 min chunks with 2 s of overlap, demo = 5 min with 3 s (2 to 5 s).
 * Nothing else in the app assumes 60 s or a fixed overlap.
 */
data class ChunkProfile(val name: String, val chunkSeconds: Int, val overlapMs: Int) {
    init {
        require(chunkSeconds in MIN_SECONDS..MAX_SECONDS) { "chunkSeconds $chunkSeconds outside $MIN_SECONDS..$MAX_SECONDS" }
        require(overlapMs in MIN_OVERLAP_MS..MAX_OVERLAP_MS) { "overlapMs $overlapMs outside $MIN_OVERLAP_MS..$MAX_OVERLAP_MS" }
    }

    companion object {
        const val MIN_SECONDS = 60
        const val MAX_SECONDS = 300
        const val MIN_OVERLAP_MS = 2000
        const val MAX_OVERLAP_MS = 5000
        val DEV = ChunkProfile("dev", 60, 2000)
        val DEMO = ChunkProfile("demo", 300, 3000)

        /** The named profile, with the overlap chosen in Settings (clamped to 2..5 s). */
        fun of(name: String?, overlapMs: Int? = null): ChunkProfile {
            val base = if (name == DEMO.name) DEMO else DEV
            val overlap = (overlapMs ?: base.overlapMs).coerceIn(MIN_OVERLAP_MS, MAX_OVERLAP_MS)
            return base.copy(overlapMs = overlap)
        }
    }
}

data class Cut(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

/** One row of a chunk's map: frame of the chunk, frame of the original, ms in the original. */
data class FrameRow(val chunkFrame: Int, val sourceFrame: Int, val ms: Long)

data class ChunkMeta(
    val number: Int,
    val count: Int,
    val file: String,
    val seconds: Int,
    val startMs: Long,
    val endMs: Long,
    val overlapMs: Long,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val map: List<FrameRow>,
    val overlapFrames: Int,
    val sha256: String,
)

data class ClipInfo(
    val id: Int,
    val matchId: Int,
    val sourceFile: String,
    val sourceFps: Double,
    val sourceFrames: Int,
    val durationMs: Long,
)

object Chunks {
    const val FPS = 10
    const val SAM_USD_PER_1000_FRAMES = 0.20
    const val SAM_MAX_FRAMES_PER_REQUEST = 15_000
    const val DEFAULT_PROMPTS = 3

    /**
     * Planned cuts: [0, S), [S − T, 2S), [2S − T, 3S)… and the last one to the
     * end. If what is left after the last cut is shorter than 2·T, it joins the
     * previous chunk. A short clip is one chunk.
     */
    fun plan(durationMs: Long, profile: ChunkProfile): List<Cut> {
        val s = profile.chunkSeconds * 1000L
        val t = profile.overlapMs.toLong()
        val cuts = mutableListOf<Cut>()
        var start = 0L
        while (true) {
            val end = (cuts.size + 1) * s
            if (end >= durationMs) {
                cuts += Cut(start, durationMs)
                break
            }
            cuts += Cut(start, end)
            start = end - t
        }
        if (cuts.size > 1) {
            val last = cuts.last()
            if (last.durationMs - t < 2 * t) {
                cuts.removeAt(cuts.lastIndex)
                cuts[cuts.lastIndex] = Cut(cuts.last().startMs, last.endMs)
            }
        }
        return cuts
    }

    /**
     * Start of chunk n+1: the first frame chunk n kept inside the overlap, so
     * both chunks repeat exactly the same frames of the original.
     */
    fun alignedStart(previousMap: List<FrameRow>, plannedStartMs: Long): Long =
        previousMap.firstOrNull { it.ms >= plannedStartMs }?.ms ?: plannedStartMs

    /**
     * The map of a generated chunk: each output frame time (ms in the original)
     * paired with the nearest frame of the original by time. With [sourceTimesMs]
     * (variable frame rate) by binary search; otherwise from the measured fps.
     */
    fun frameMap(outputMs: List<Long>, sourceFps: Double, sourceTimesMs: List<Long>? = null): List<FrameRow> =
        outputMs.mapIndexed { i, ms ->
            val src = if (sourceTimesMs != null && sourceTimesMs.isNotEmpty()) nearest(sourceTimesMs, ms) else Math.round(ms * sourceFps / 1000.0).toInt()
            FrameRow(i, src, ms)
        }

    private fun nearest(sorted: List<Long>, ms: Long): Int {
        var lo = 0
        var hi = sorted.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (sorted[mid] < ms) lo = mid + 1 else hi = mid
        }
        return if (lo > 0 && ms - sorted[lo - 1] <= sorted[lo] - ms) lo - 1 else lo
    }

    /** Frames of chunk n that repeat the end of chunk n−1. */
    fun overlapFrames(map: List<FrameRow>, previousEndMs: Long?): Int =
        if (previousEndMs == null) 0 else map.count { it.ms < previousEndMs }

    fun samCostUsd(frames: Int, prompts: Int = DEFAULT_PROMPTS): Double = frames * prompts * SAM_USD_PER_1000_FRAMES / 1000.0

    private fun num(v: Double): String = String.format(Locale.US, "%.2f", v)
    private fun q(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** video_part_NNN.yaml, written by hand, readable by PyYAML and cv::FileStorage. */
    fun chunkYaml(clip: ClipInfo, c: ChunkMeta, pitch: String? = null): String = buildString {
        append("%YAML:1.0\n---\nversion: 1\n")
        append("clip:\n")
        append("  id: ${clip.id}\n  match_id: ${clip.matchId}\n  source_file: ${q(clip.sourceFile)}\n")
        append("  source_fps: ${num(clip.sourceFps)}\n  source_frames: ${clip.sourceFrames}\n  duration_ms: ${clip.durationMs}\n")
        append("chunk:\n")
        append("  number: ${c.number}\n  count: ${c.count}\n  file: ${q(c.file)}\n  seconds: ${c.seconds}\n")
        append("  start_ms: ${c.startMs}\n  end_ms: ${c.endMs}\n  overlap_ms: ${c.overlapMs}\n")
        append("encoding:\n")
        append("  fps: $FPS\n  width: ${c.width}\n  height: ${c.height}\n  codec: \"h264\"\n  bitrate: ${c.bitrate}\n  audio: 0\n")
        append("frames:\n  count: ${c.map.size}\n  overlap_frames: ${c.overlapFrames}\n")
        append("  # [chunk frame, original frame, ms in the original]\n  map:\n")
        for (r in c.map) append("    - [${r.chunkFrame}, ${r.sourceFrame}, ${r.ms}]\n")
        if (pitch != null) append(pitch)
        append("sha256: ${q(c.sha256)}\n")
    }

    data class IndexRow(val number: Int, val file: String, val startMs: Long, val endMs: Long, val frames: Int)

    fun indexYaml(clipId: Int, profile: ChunkProfile, count: Int, rows: List<IndexRow>): String = buildString {
        append("%YAML:1.0\n---\nversion: 1\n")
        append("clip_id: $clipId\nprofile: ${q(profile.name)}\nchunk_seconds: ${profile.chunkSeconds}\noverlap_ms: ${profile.overlapMs}\n")
        append("count: $count\ncomplete: ${if (rows.size == count) 1 else 0}\nchunks:\n")
        for (r in rows) append("  - { number: ${r.number}, file: ${q(r.file)}, start_ms: ${r.startMs}, end_ms: ${r.endMs}, frames: ${r.frames} }\n")
    }

    /** What a resumed job needs from a chunk YAML it wrote before. Null if it is not a complete chunk YAML. */
    data class ParsedChunk(val startMs: Long, val endMs: Long, val count: Int, val map: List<FrameRow>, val sha256: String, val height: Int, val withPitch: Int? = null)

    fun parseChunkYaml(text: String): ParsedChunk? {
        var section = ""
        var startMs: Long? = null
        var endMs: Long? = null
        var count: Int? = null
        var height = 0
        var sha: String? = null
        var withPitch: Int? = null
        val map = mutableListOf<FrameRow>()
        val row = Regex("""^\s+-\s*\[(\d+),\s*(-?\d+),\s*(-?\d+)]\s*$""")
        for (line in text.lineSequence()) {
            if (line.isNotEmpty() && !line[0].isWhitespace()) section = line.substringBefore(':')
            val trimmed = line.trim()
            when {
                section == "chunk" && trimmed.startsWith("start_ms:") -> startMs = trimmed.substringAfter(':').trim().toLongOrNull()
                section == "chunk" && trimmed.startsWith("end_ms:") -> endMs = trimmed.substringAfter(':').trim().toLongOrNull()
                section == "encoding" && trimmed.startsWith("height:") -> height = trimmed.substringAfter(':').trim().toIntOrNull() ?: 0
                section == "frames" && trimmed.startsWith("count:") -> count = trimmed.substringAfter(':').trim().toIntOrNull()
                section == "pitch" && trimmed.startsWith("with_pitch:") -> withPitch = trimmed.substringAfter(':').trim().toIntOrNull()
                section == "frames" -> row.find(line)?.let { m -> map += FrameRow(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toLong()) }
                line.startsWith("sha256:") -> sha = line.substringAfter(':').trim().trim('"')
            }
        }
        if (startMs == null || endMs == null || count == null || sha.isNullOrEmpty() || map.size != count) return null
        return ParsedChunk(startMs, endMs, count, map, sha, height, withPitch)
    }
}

/** The analysis copy's size: 720p at 1.5 Mbps, or 1080p at 3 Mbps with "high resolution". */
data class ProxySpec(val maxHeight: Int, val bitrate: Int) {
    /** Approximate MB per minute of video (video bitrate only). */
    val mbPerMinute: Double get() = bitrate / 8.0 * 60 / 1_000_000

    companion object {
        val STANDARD = ProxySpec(720, 1_500_000)
        val HIGH = ProxySpec(1080, 3_000_000)
        fun of(highResolution: Boolean) = if (highResolution) HIGH else STANDARD

        /** Output size keeping the aspect ratio, even dimensions, at most [maxHeight] tall. */
        fun outputSize(width: Int, height: Int, maxHeight: Int): Pair<Int, Int> {
            if (width <= 0 || height <= 0) return 1280 to 720
            val h = minOf(height, maxHeight)
            val w = Math.round(width.toDouble() * h / height).toInt()
            return (w / 2 * 2) to (h / 2 * 2)
        }
    }
}

object ProxyPaths {
    fun clipDir(match: Match, clip: Clip): String = "proxies/${match.folder}/clip_%04d".format(clip.id)
    fun mp4(n: Int) = "video_part_%03d.mp4".format(n)
    fun yaml(n: Int) = "video_part_%03d.yaml".format(n)
    const val INDEX = "chunks.yaml"
}
