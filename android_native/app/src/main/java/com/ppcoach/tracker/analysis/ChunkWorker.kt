@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.ppcoach.tracker.analysis

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ppcoach.tracker.AppGraph
import com.ppcoach.tracker.data.ChunkMeta
import com.ppcoach.tracker.data.ChunkProfile
import com.ppcoach.tracker.data.Chunks
import com.ppcoach.tracker.data.ClipInfo
import com.ppcoach.tracker.data.ClipStatus
import com.ppcoach.tracker.data.FrameRow
import com.ppcoach.tracker.data.PitchMask
import com.ppcoach.tracker.data.ProxyPaths
import com.ppcoach.tracker.data.ProxySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Makes the analysis copy of a clip: 10 fps chunks without audio, with their
 * YAML and chunks.yaml (docs/12-videos-largos-chunks.md). Resumable: chunks
 * whose YAML exists and is valid are kept, and the next one is aligned with
 * the map of the previous one.
 */
class ChunkWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val KEY_CLIP = "clip_id"
        const val TAG = "chunk"
    }

    override suspend fun doWork(): Result {
        AppGraph.init(applicationContext)
        val clipId = inputData.getInt(KEY_CLIP, 0)
        val lib = AppGraph.library.value
        val clip = lib.clip(clipId) ?: return Result.failure()
        val match = lib.match(clip.matchId) ?: return Result.failure()
        val settings = AppGraph.settings.load()
        val spec = ProxySpec.of(settings.resolution, clip.width, clip.height)
        // A large copy (4K) goes in shorter chunks so each one fits in the API's limit.
        val profile = ChunkProfile.forSpec(settings.profile, spec)
        val source = AppGraph.storage(applicationContext).videoFile(match, clip) ?: return Result.failure()
        if (!source.exists()) return Result.failure()
        val dir = AppGraph.proxyDir(applicationContext, ProxyPaths.clipDir(match, clip))

        // Another profile or size than the existing copy: discard it and start over.
        if (clip.chunkSeconds != profile.chunkSeconds || clip.overlapMs != profile.overlapMs || clip.proxyHeight != spec.maxHeight) {
            dir.deleteRecursively()
        }
        dir.mkdirs()
        val cuts = Chunks.plan(clip.durationMs, profile)
        AppGraph.commit { l ->
            l.updateClip(clipId) {
                it.copy(status = ClipStatus.PREPARING, chunkSeconds = profile.chunkSeconds, overlapMs = profile.overlapMs, proxyHeight = spec.maxHeight, chunkCount = cuts.size, error = "")
            }
        }

        val info = ClipInfo(clip.id, match.id, "${match.folder}/${clip.fileName}", clip.fps.takeIf { it > 0 } ?: 30.0, Math.round(clip.durationMs * (clip.fps.takeIf { it > 0 } ?: 30.0) / 1000).toInt(), clip.durationMs)
        val (outW, outH) = ProxySpec.outputSize(clip.width, clip.height, spec.maxHeight)
        val rows = mutableListOf<Chunks.IndexRow>()
        var prevMap: List<FrameRow> = emptyList()
        var pitchFrames = 0
        var pitchWith = 0
        try {
            for ((i, cut) in cuts.withIndex()) {
                val n = i + 1
                val mp4 = File(dir, ProxyPaths.mp4(n))
                val yamlFile = File(dir, ProxyPaths.yaml(n))
                val existing = if (yamlFile.exists() && mp4.exists()) Chunks.parseChunkYaml(yamlFile.readText()) else null
                if (existing != null && existing.height == outH && sha256(mp4) == existing.sha256) {
                    prevMap = existing.map
                    existing.withPitch?.let { pitchWith += it; pitchFrames += existing.count }
                    rows += Chunks.IndexRow(n, mp4.name, existing.startMs, existing.endMs, existing.count)
                    continue
                }
                val start = if (n == 1) cut.startMs else Chunks.alignedStart(prevMap, cut.startMs)
                setProgress(workDataOf("phase" to "prepare", "chunk" to n, "of" to cuts.size, "pct" to 0))
                // Encoding is the first 85 % of each chunk; the pitch mask, the last 15 %.
                transcode(source, mp4, start, cut.endMs, outH, spec.bitrate) { pct ->
                    setProgressAsync(workDataOf("phase" to "prepare", "chunk" to n, "of" to cuts.size, "pct" to pct * 85 / 100))
                }
                val outputMs = frameTimesMs(mp4).map { start + it }
                val map = Chunks.frameMap(outputMs, info.sourceFps)
                val overlap = if (n == 1) 0 else Chunks.overlapFrames(map, (prevMap.lastOrNull()?.ms ?: cut.startMs) + 1)
                val meta = ChunkMeta(
                    number = n, count = cuts.size, file = mp4.name, seconds = profile.chunkSeconds,
                    startMs = map.firstOrNull()?.ms ?: start, endMs = cut.endMs,
                    overlapMs = if (n == 1) 0 else (prevMap.lastOrNull()?.ms ?: cut.startMs) - (map.firstOrNull()?.ms ?: start) + 100,
                    width = outW, height = outH, bitrate = spec.bitrate, map = map, overlapFrames = overlap, sha256 = sha256(mp4),
                )
                // The pitch of each frame (docs/prompts/12-mascara-cancha.md). If it fails, the YAML goes without it.
                val started = System.currentTimeMillis()
                val pitch = try {
                    PitchSampler.sample(mp4) { pct ->
                        setProgressAsync(workDataOf("phase" to "prepare", "chunk" to n, "of" to cuts.size, "pct" to 85 + pct * 15 / 100))
                    }
                } catch (e: Exception) {
                    android.util.Log.w("ChunkWorker", "chunk $n: pitch failed: ${e.message}")
                    null
                }
                android.util.Log.i("ChunkWorker", "chunk $n: pitch in ${System.currentTimeMillis() - started} ms")
                if (pitch != null) {
                    pitchFrames += pitch.size
                    pitchWith += pitch.count { it.third.size >= 6 }
                }
                com.ppcoach.tracker.data.Durable.writeText(yamlFile, Chunks.chunkYaml(info, meta, pitch?.let { PitchMask.yamlSection(it) }))
                rows += Chunks.IndexRow(n, mp4.name, meta.startMs, meta.endMs, map.size)
                com.ppcoach.tracker.data.Durable.writeText(File(dir, ProxyPaths.INDEX), Chunks.indexYaml(clip.id, profile, cuts.size, rows))
                prevMap = map
            }
            com.ppcoach.tracker.data.Durable.writeText(File(dir, ProxyPaths.INDEX), Chunks.indexYaml(clip.id, profile, cuts.size, rows))
        } catch (e: Exception) {
            if (runAttemptCount < 2) return Result.retry()
            AppGraph.commit { l -> l.updateClip(clipId) { it.copy(status = ClipStatus.FAILED, error = "prepare: ${e.message ?: e.javaClass.simpleName}") } }
            return Result.failure()
        }
        val share = if (pitchFrames > 0) pitchWith.toDouble() / pitchFrames else -1.0
        AppGraph.commit { l -> l.updateClip(clipId) { (if (it.status == ClipStatus.PREPARING) it.copy(status = ClipStatus.LOCAL) else it).copy(pitchShare = share) } }
        return Result.success(workDataOf(KEY_CLIP to clipId))
    }

    /** Media3 Transformer: clip [startMs, endMs), 10 fps, at most [height] tall, H.264 at [bitrate], no audio. */
    private suspend fun transcode(src: File, out: File, startMs: Long, endMs: Long, height: Int, bitrate: Int, onProgress: (Int) -> Unit) {
        val tmp = File(out.parentFile, out.name + ".tmp.mp4")
        tmp.delete()
        val main = Handler(Looper.getMainLooper())
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(src))
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build())
            .build()
        val edited = EditedMediaItem.Builder(item)
            .setRemoveAudio(true)
            .setEffects(Effects(emptyList(), listOf(FrameDropEffect.createDefaultFrameDropEffect(Chunks.FPS.toFloat()), Presentation.createForHeight(height))))
            .build()
        var transformer: Transformer? = null
        var done = false
        val holder = ProgressHolder()
        val poll = object : Runnable {
            override fun run() {
                val t = transformer ?: return
                if (done) return
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                main.postDelayed(this, 500)
            }
        }
        try {
            suspendCancellableCoroutine { cont ->
                main.post {
                    val t = Transformer.Builder(applicationContext)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setEncoderFactory(
                            DefaultEncoderFactory.Builder(applicationContext)
                                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                                .build(),
                        )
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (cont.isActive) cont.resume(Unit)
                            }

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (cont.isActive) cont.resumeWithException(exportException)
                            }
                        })
                        .build()
                    transformer = t
                    t.start(edited, tmp.absolutePath)
                    main.postDelayed(poll, 500)
                }
                cont.invokeOnCancellation { main.post { transformer?.cancel() } }
            }
        } finally {
            done = true
            main.removeCallbacks(poll)
        }
        if (!tmp.renameTo(out)) {
            out.delete()
            tmp.renameTo(out)
        }
        onProgress(100)
    }

    /** Presentation time of each frame of the generated chunk, in ms from its start (measured, not planned). */
    private fun frameTimesMs(file: File): List<Long> {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.absolutePath)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return emptyList()
            ex.selectTrack(track)
            val times = mutableListOf<Long>()
            while (true) {
                val t = ex.sampleTime
                if (t < 0) break
                times += t / 1000
                ex.advance()
            }
            return times.sorted()
        } finally {
            ex.release()
        }
    }

    private suspend fun sha256(file: File): String = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                md.update(buf, 0, r)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }
}
