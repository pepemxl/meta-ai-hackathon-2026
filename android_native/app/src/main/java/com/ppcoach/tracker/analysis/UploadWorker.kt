package com.ppcoach.tracker.analysis

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ppcoach.tracker.AppGraph
import com.ppcoach.tracker.BuildConfig
import com.ppcoach.tracker.data.ApiClient
import com.ppcoach.tracker.data.ApiException
import com.ppcoach.tracker.data.ClipStatus
import com.ppcoach.tracker.data.Consent
import com.ppcoach.tracker.data.ProxyPaths
import com.ppcoach.tracker.data.UploadPlan
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Uploads the analysis copy of a clip: POST /clips, each missing chunk in 8 MB
 * parts resuming at the server's offset, then its YAML and sha256, then
 * POST /complete. The session is read encrypted WHEN RUNNING, never from the
 * input data (WorkManager stores it in plain text).
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val KEY_CLIP = "clip_id"
        const val TAG = "upload"
        const val MAX_ATTEMPTS = 8

        // Error codes kept in Clip.error; the UI shows them in the app's language.
        const val ERR_SESSION = "session_expired"
        const val ERR_LOGIN = "login_required"
        const val ERR_WITHDRAWN = "consent_withdrawn"
        const val ERR_TERMS = "terms_changed"
        const val ERR_TOO_LARGE = "part_too_large"
    }

    override suspend fun doWork(): Result {
        AppGraph.init(applicationContext)
        val clipId = inputData.getInt(KEY_CLIP, 0)
        val lib = AppGraph.library.value
        val clip = lib.clip(clipId) ?: return Result.failure()
        val match = lib.match(clip.matchId) ?: return Result.failure()
        val settingsStore = AppGraph.settings
        val settings = settingsStore.load()
        val token = settingsStore.token()
        if (settings.serverUrl.isBlank() || token == null) return fail(clipId, ERR_LOGIN)
        if (!Consent.isCurrent(settings.consentVersion)) return fail(clipId, ERR_TERMS)

        val dir = AppGraph.proxyDir(applicationContext, ProxyPaths.clipDir(match, clip))
        val count = clip.chunkCount
        val files = (1..count).associateWith { File(dir, ProxyPaths.mp4(it)) }
        if (count == 0 || files.values.any { !it.exists() } || !File(dir, ProxyPaths.INDEX).exists()) return Result.failure()

        val api = ApiClient(settings.serverUrl, token)
        AppGraph.commit { l -> l.updateClip(clipId) { it.copy(status = ClipStatus.UPLOADING, error = "") } }
        try {
            val body = JSONObject()
                .put("client_clip_id", "clip-${clip.id}-${clip.createdAt}")
                .put("title", clip.title.ifBlank { "Clip ${clip.id}" }.take(160))
                .put("duration_ms", clip.durationMs)
                .put("chunk_count", count)
                .put("chunk_seconds", clip.chunkSeconds)
                .put("overlap_ms", clip.overlapMs)
                .put(
                    "match",
                    JSONObject()
                        .put("client_match_id", "match-${match.id}-${match.createdAt}")
                        .put("home", match.home.ifBlank { "Local" }.take(160))
                        .put("away", match.away.ifBlank { "Visitante" }.take(160))
                        .put("home_color", match.homeColor.key)
                        .put("away_color", match.awayColor.key)
                        .put("date", match.date.ifBlank { "-" })
                        .put("venue", match.venue.take(160)),
                )
                .put("consent", Consent.clipBlock(settings.consentAt, BuildConfig.VERSION_NAME))
            val remote = api.register(body)
            AppGraph.commit { l -> l.updateClip(clipId) { it.copy(remoteId = remote.id) } }

            val chunks = files.mapValues { (_, f) -> f.length() to sha256(f) }
            val parts = UploadPlan.missingParts(chunks, remote)
            val total = chunks.values.sumOf { it.first }.coerceAtLeast(1)
            var done = total - UploadPlan.pendingBytes(parts)
            val byChunk = parts.groupBy { it.chunk }
            for ((n, chunkParts) in byChunk.toSortedMap()) {
                val file = files.getValue(n)
                var offset = chunkParts.first().offset
                val size = file.length()
                while (offset < size) {
                    val len = minOf(ApiClient.PART_BYTES, size - offset)
                    val base = done
                    val got = api.putPart(remote.id, n, file, offset, len) { sent ->
                        setProgressAsync(workDataOf("phase" to "upload", "pct" to ((base + sent) * 100 / total).toInt(), "chunk" to n, "of" to count))
                    }
                    done += (got - offset).coerceAtLeast(0)
                    offset = got
                }
                val yaml = File(dir, ProxyPaths.yaml(n)).readText()
                api.finishChunk(remote.id, n, yaml, chunks.getValue(n).second)
            }
            val final = api.complete(remote.id, File(dir, ProxyPaths.INDEX).readText())
            AppGraph.commit { l -> l.updateClip(clipId) { it.copy(status = ClipStatus.fromRemote(final.status) ?: ClipStatus.UPLOADED, error = "") } }
            return Result.success(workDataOf(KEY_CLIP to clipId))
        } catch (e: ApiException) {
            return when {
                e.code == 401 -> fail(clipId, ERR_SESSION)
                e.code == 428 -> fail(clipId, if (Consent.rejection(e) == Consent.Rejection.WITHDRAWN) ERR_WITHDRAWN else ERR_TERMS)
                e.code == 413 -> fail(clipId, ERR_TOO_LARGE)
                e.retryable && runAttemptCount < MAX_ATTEMPTS -> Result.retry()
                else -> fail(clipId, e.message ?: "HTTP ${e.code}")
            }
        } catch (e: IOException) {
            if (runAttemptCount < MAX_ATTEMPTS) return Result.retry()
            return fail(clipId, e.message ?: "network")
        }
    }

    private fun fail(clipId: Int, error: String): Result {
        AppGraph.commit { l -> l.updateClip(clipId) { it.copy(status = ClipStatus.FAILED, error = error) } }
        return Result.failure(workDataOf("error" to error))
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
