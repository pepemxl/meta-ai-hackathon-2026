package com.ppcoach.tracker.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApiClientTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    private fun api(token: String? = "tok") = ApiClient(server.url("/").toString(), token)
    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun healthAndLogin() {
        server.enqueue(json(200, """{"ok":true,"version":"0.1.0","db":true}"""))
        assertTrue(api().health().getBoolean("ok"))
        server.enqueue(json(200, """{"token":"jwt","email":"c@x.mx","name":"Coach"}"""))
        assertEquals(Session("jwt", "c@x.mx", "Coach"), api(null).login(" c@x.mx ", "pw"))
        server.takeRequest()
        val req = server.takeRequest()
        assertEquals("/auth/login", req.path)
        assertEquals("c@x.mx", JSONObject(req.body.readUtf8()).getString("email"))
        assertEquals(null, req.getHeader("Authorization"))
    }

    @Test
    fun registerReadsReceivedAndPartialChunks() {
        server.enqueue(json(200, """{"clip_id":7,"status":"uploading","chunk_count":2,"chunk_seconds":300,"overlap_ms":3000,
            "received":[{"number":1,"sha256":"aa"}],"partial":[{"number":2,"received_bytes":8388608}]}"""))
        val r = api().register(JSONObject().put("x", 1))
        assertEquals(7, r.id)
        assertEquals(mapOf(1 to "aa"), r.received)
        assertEquals(mapOf(2 to 8_388_608L), r.partial)
        assertEquals("Bearer tok", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun putPartSendsOnlyItsRangeWithGrowingProgress() {
        val file = tmp.newFile().apply { writeBytes(ByteArray(300_000) { (it % 251).toByte() }) }
        server.enqueue(json(200, """{"number":1,"received_bytes":300000}"""))
        val seen = mutableListOf<Long>()
        val got = api().putPart(3, 1, file, 100_000, 200_000) { seen += it }
        assertEquals(300_000L, got)
        val req = server.takeRequest()
        assertEquals("/clips/3/chunks/1/video?offset=100000", req.path)
        assertEquals("PUT", req.method)
        val body = req.body.readByteArray()
        assertEquals(200_000, body.size)
        assertEquals((100_000 % 251).toByte(), body[0])
        assertTrue(seen.zipWithNext().all { (a, b) -> b > a })
        assertEquals(200_000L, seen.last())
    }

    @Test
    fun offsetMismatchResumesFromTheServersOffset() {
        val file = tmp.newFile().apply { writeBytes(ByteArray(10)) }
        server.enqueue(json(409, """{"error":"offset mismatch","expected_offset":4}"""))
        assertEquals(4L, api().putPart(3, 1, file, 0, 10))
    }

    @Test
    fun finishChunkAndComplete() {
        server.enqueue(json(200, """{"number":1,"sha256":"aa","size_bytes":10}"""))
        api().finishChunk(3, 1, "sha256: \"aa\"\n", "aa")
        val r1 = server.takeRequest()
        assertEquals("/clips/3/chunks/1", r1.path)
        val body = r1.body.readUtf8()
        assertTrue(body.contains("name=\"sha256\"") && body.contains("name=\"yaml\"; filename=\"video_part_001.yaml\""))
        server.enqueue(json(200, """{"clip_id":3,"status":"queued","chunk_count":1,"received":[],"partial":[],"job_id":9}"""))
        val done = api().complete(3, "count: 1\n")
        assertEquals("queued", done.status)
        assertEquals(9, done.jobId)
    }

    @Test
    fun errorsCarryTheMessageAndWhetherToRetry() {
        val cases = mapOf(401 to false, 400 to false, 404 to false, 409 to false, 413 to false, 428 to false, 422 to true, 429 to true, 500 to true, 503 to true)
        for ((code, retry) in cases) {
            server.enqueue(json(code, """{"error":"e$code"}"""))
            try {
                api().clip(1)
                fail("expected $code")
            } catch (e: ApiException) {
                assertEquals(code, e.code)
                assertEquals("e$code", e.message)
                assertEquals("retryable for $code", retry, e.retryable)
            }
        }
        // OkHttp itself repeats a request answered with 408, so that one is checked directly.
        assertTrue(ApiException(408, "timeout").retryable)
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"))
        try {
            api().clip(1)
        } catch (e: ApiException) {
            assertEquals("HTTP 502", e.message)
        }
    }

    @Test
    fun consentEvent() {
        server.enqueue(json(200, """{"id":5,"action":"accept","version":"2026-10-27","at":"2026-10-27T16:04:05.000Z"}"""))
        val r = api().consentEvent(Consent.event("accept", Consent.VERSION, "2026-10-27T16:04:05Z", "0.1.0"))
        assertEquals(5, r.getInt("id"))
        val req = server.takeRequest()
        assertEquals("/consent", req.path)
        assertEquals("accept", JSONObject(req.body.readUtf8()).getString("action"))
    }

    @Test
    fun normalizeUrl() {
        assertEquals("https://ppcoachtracker.pepemxl.com", ApiClient.normalizeUrl("ppcoachtracker.pepemxl.com/"))
        assertEquals("http://10.0.2.2:3000", ApiClient.normalizeUrl(" http://10.0.2.2:3000/ "))
        assertEquals("https://x.mx/api", ApiClient.normalizeUrl("https://x.mx/api//"))
        assertEquals("", ApiClient.normalizeUrl(""))
    }

    @Test
    fun missingPartsForOneAndFiveMinuteChunks() {
        val mb = 1024L * 1024
        // Two 1-min chunks of ~11 MB; the first is at the server, the second half sent.
        val dev = mapOf(1 to (11 * mb to "a"), 2 to (11 * mb to "b"))
        val remote = RemoteClip(1, "uploading", 2, mapOf(1 to "a"), mapOf(2 to 8 * mb))
        val parts = UploadPlan.missingParts(dev, remote)
        assertEquals(listOf(UploadPlan.Part(2, 8 * mb, 3 * mb)), parts)
        // A 5-min chunk of 56 MB from scratch: 7 parts of 8 MB.
        val demo = mapOf(1 to (56 * mb to "x"))
        val all = UploadPlan.missingParts(demo, RemoteClip(1, "registered", 1, emptyMap(), emptyMap()))
        assertEquals(7, all.size)
        assertEquals(56 * mb, UploadPlan.pendingBytes(all))
        // The server has the same number with another sha256: send it again.
        assertEquals(7, UploadPlan.missingParts(demo, RemoteClip(1, "uploading", 1, mapOf(1 to "old"), emptyMap())).size)
        // Everything arrived but the close is pending: one empty part at the end.
        assertEquals(listOf(UploadPlan.Part(1, 56 * mb, 0)), UploadPlan.missingParts(demo, RemoteClip(1, "uploading", 1, emptyMap(), mapOf(1 to 56 * mb))))
        assertFalse(UploadPlan.missingParts(dev, RemoteClip(1, "queued", 2, mapOf(1 to "a", 2 to "b"), emptyMap())).isNotEmpty())
    }
}
