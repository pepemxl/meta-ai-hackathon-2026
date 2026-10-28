package com.ppcoach.tracker.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

data class Session(val token: String, val email: String, val name: String)

data class RemoteClip(
    val id: Int,
    val status: String,
    val chunkCount: Int,
    val received: Map<Int, String>,
    val partial: Map<Int, Long>,
    val jobError: String? = null,
    val jobId: Int = 0,
)

/** An HTTP error of the API, with the "error" field of its JSON as the message. */
class ApiException(val code: Int, message: String, val body: JSONObject? = null) : Exception(message) {
    /** 5xx, 408, 429 and 422 (a sha256 mismatch: the part is sent again) are worth retrying. */
    val retryable: Boolean get() = code >= 500 || code == 408 || code == 429 || code == 422
}

/**
 * Client of the pp-coach-tracker API (contract in api/README.md). No Android
 * types: tested on the JVM with MockWebServer.
 */
class ApiClient(baseUrl: String, private val token: String? = null, client: OkHttpClient? = null) {
    val baseUrl: String = normalizeUrl(baseUrl)
    private val http = client ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS) // one 8 MB part on a pitch-side connection
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        const val PART_BYTES = 8L * 1024 * 1024
        const val PROGRESS_STEP = 64 * 1024
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()
        private val YAML = "text/yaml; charset=utf-8".toMediaType()
        private val JPEG = "image/jpeg".toMediaType()

        /** Adds https:// when missing and drops the trailing "/"; keeps http:// for local servers. */
        fun normalizeUrl(url: String): String {
            var u = url.trim()
            if (u.isEmpty()) return u
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
            return u.trimEnd('/')
        }
    }

    private fun request(path: String): Request.Builder {
        val b = Request.Builder().url(baseUrl + path)
        if (token != null) b.header("Authorization", "Bearer $token")
        return b
    }

    private fun exec(req: Request): JSONObject {
        http.newCall(req).execute().use { resp -> return parse(resp) }
    }

    private fun parse(resp: Response): JSONObject {
        val text = resp.body?.string().orEmpty()
        val json = try {
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } catch (e: Exception) {
            JSONObject()
        }
        if (!resp.isSuccessful) throw ApiException(resp.code, json.optString("error").ifEmpty { "HTTP ${resp.code}" }, json)
        return json
    }

    fun health(): JSONObject = exec(request("/health").get().build())

    fun login(email: String, password: String): Session {
        val body = JSONObject().put("email", email.trim()).put("password", password).toString().toRequestBody(JSON)
        val j = exec(request("/auth/login").post(body).build())
        return Session(j.getString("token"), j.optString("email", email), j.optString("name", ""))
    }

    fun register(clip: JSONObject): RemoteClip = remoteClip(exec(request("/clips").post(clip.toString().toRequestBody(JSON)).build()))

    fun clip(clipId: Int): RemoteClip = remoteClip(exec(request("/clips/$clipId").get().build()))

    /**
     * One raw part of a chunk: [length] bytes of [file] from [offset]. A 409 with
     * expected_offset is not an error: returns that offset to continue from.
     * Returns the bytes the server has for the chunk.
     */
    fun putPart(clipId: Int, n: Int, file: File, offset: Long, length: Long, onBytes: (Long) -> Unit = {}): Long {
        val body = object : RequestBody() {
            override fun contentType() = OCTET
            override fun contentLength() = length
            override fun writeTo(sink: BufferedSink) {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(offset)
                    val buf = ByteArray(PROGRESS_STEP)
                    var left = length
                    var sent = 0L
                    while (left > 0) {
                        val read = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (read < 0) break
                        sink.write(buf, 0, read)
                        left -= read
                        sent += read
                        onBytes(sent)
                    }
                }
            }
        }
        val req = request("/clips/$clipId/chunks/$n/video?offset=$offset").put(body).build()
        try {
            return exec(req).getLong("received_bytes")
        } catch (e: ApiException) {
            if (e.code == 409 && e.body?.has("expected_offset") == true) return e.body.getLong("expected_offset")
            throw e
        }
    }

    fun finishChunk(clipId: Int, n: Int, yaml: String, sha256: String): JSONObject {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("sha256", sha256)
            .addFormDataPart("yaml", ProxyPaths.yaml(n), yaml.toRequestBody(YAML))
            .build()
        return exec(request("/clips/$clipId/chunks/$n").put(body).build())
    }

    fun complete(clipId: Int, index: String): RemoteClip {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("index", ProxyPaths.INDEX, index.toRequestBody(YAML))
            .build()
        return remoteClip(exec(request("/clips/$clipId/complete").post(body).build()))
    }

    /** GET /clips/:id/analysis as raw JSON text (ClipAnalysis.parse reads it). */
    fun analysis(clipId: Int): String {
        http.newCall(request("/clips/$clipId/analysis").get().build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val j = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
                throw ApiException(resp.code, j.optString("error").ifEmpty { "HTTP ${resp.code}" }, j)
            }
            return text
        }
    }

    /** The coach's points on the pitch (POST /clips/:id/calibration). */
    fun calibration(clipId: Int, body: JSONObject): JSONObject = exec(request("/clips/$clipId/calibration").post(body.toString().toRequestBody(JSON)).build())

    /** A reference picture of the pitch (PUT /clips/:id/references/:n, JPEG up to 2 MB). */
    fun putReference(clipId: Int, n: Int, jpeg: ByteArray): JSONObject =
        exec(request("/clips/$clipId/references/$n").put(jpeg.toRequestBody(JPEG)).build())

    fun consentEvent(event: JSONObject): JSONObject = exec(request("/consent").post(event.toString().toRequestBody(JSON)).build())

    private fun remoteClip(j: JSONObject): RemoteClip {
        val received = mutableMapOf<Int, String>()
        j.optJSONArray("received")?.let { arr -> for (i in 0 until arr.length()) arr.getJSONObject(i).let { received[it.getInt("number")] = it.optString("sha256") } }
        val partial = mutableMapOf<Int, Long>()
        j.optJSONArray("partial")?.let { arr -> for (i in 0 until arr.length()) arr.getJSONObject(i).let { partial[it.getInt("number")] = it.getLong("received_bytes") } }
        val job = j.optJSONObject("job")
        return RemoteClip(
            id = j.getInt("clip_id"),
            status = j.optString("status"),
            chunkCount = j.optInt("chunk_count"),
            received = received,
            partial = partial,
            jobError = job?.optString("error")?.takeIf { it.isNotEmpty() && it != "null" },
            jobId = j.optInt("job_id", job?.optInt("id") ?: 0),
        )
    }
}

/** Which parts of which chunks are still missing at the server (plain Kotlin, tested). */
object UploadPlan {
    data class Part(val chunk: Int, val offset: Long, val length: Long)

    /**
     * [chunks]: number → (size, sha256). Chunks the server has with the same
     * sha256 are skipped; a partial one resumes at the bytes the server has.
     */
    fun missingParts(chunks: Map<Int, Pair<Long, String>>, remote: RemoteClip, partBytes: Long = ApiClient.PART_BYTES): List<Part> {
        val out = mutableListOf<Part>()
        for ((n, info) in chunks.toSortedMap()) {
            val (size, sha) = info
            if (remote.received[n] == sha) continue
            var offset = (remote.partial[n] ?: 0L).coerceIn(0, size)
            if (offset == size && size > 0) {
                // Everything arrived but it was never closed: the close is still pending.
                out += Part(n, size, 0)
                continue
            }
            while (offset < size) {
                val len = minOf(partBytes, size - offset)
                out += Part(n, offset, len)
                offset += len
            }
        }
        return out
    }

    fun pendingBytes(parts: List<Part>): Long = parts.sumOf { it.length }
}
