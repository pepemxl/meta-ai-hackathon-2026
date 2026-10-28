package com.ppcoach.tracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ConsentAnalysisTest {
    @Test
    fun urlsFollowTheLanguageAndTheServer() {
        assertEquals("https://ppcoachtracker.pepemxl.com/legal/privacidad.html", Consent.url(null, Consent.Doc.PRIVACY, english = false))
        assertEquals("https://ppcoachtracker.pepemxl.com/legal/en/consent.html", Consent.url("", Consent.Doc.TERMS, english = true))
        assertEquals("http://10.0.2.2:3000/legal/en/players-form.html", Consent.url("http://10.0.2.2:3000/", Consent.Doc.PLAYERS_FORM, english = true))
    }

    @Test
    fun jsonAndVersion() {
        assertEquals("2026-10-27", Consent.VERSION)
        assertTrue(Consent.isCurrent("2026-10-27"))
        assertFalse(Consent.isCurrent("2025-01-01"))
        assertEquals("2026-10-27T16:04:05Z", Consent.isoNow(Instant.parse("2026-10-27T16:04:05.987Z")))
        val block = Consent.clipBlock("2026-10-27T16:04:05Z", "0.1.0")
        assertEquals(setOf("version", "accepted_at", "app_version"), block.keys().asSequence().toSet())
        assertEquals("withdraw", Consent.event("withdraw", "2026-01-01", "2026-10-27T16:04:05Z", "0.1.0").getString("action"))
    }

    @Test
    fun a428IsNeverRetriedAndSaysWhy() {
        val withdrawn = ApiException(428, "consent withdrawn")
        val required = ApiException(428, "consent required")
        assertFalse(withdrawn.retryable)
        assertEquals(Consent.Rejection.WITHDRAWN, Consent.rejection(withdrawn))
        assertEquals(Consent.Rejection.OUTDATED, Consent.rejection(required))
        assertNull(Consent.rejection(ApiException(500, "x")))
    }

    private val result = """
        {"version":1,"backends":{"segmenter":"sam_simulator","sam_model":"sam-3.1","analyst":"muse_spark","muse_spark_model":"muse-spark-1.3",
         "usage":{"calls":9,"frames_sent":2310}},"match":{"home":"Tigres","away":"Halcones","home_color":"red","away_color":"blue"},
         "video":{"duration_ms":75000,"chunks":2,"chunk_seconds":60},"ball":{"seen_ratio":0.8},"possession_changes":3,
         "teams":{"A":{"players_per_frame":3.5,"tracks":4,"possession":0.6,"longest_possession_ms":4200,"pressers_per_frame":null,
                       "shape":{"spread_x":0.12,"spread_y":0.05},"heatmap":{"cols":12,"rows":8,"values":${heat(true)}}},
                  "B":{"players_per_frame":3,"tracks":3,"possession":0.4,"heatmap":{"cols":12,"rows":8,"values":${heat(false)}}}},
         "summary":"El local tuvo el balón.","insights":[
           {"title":"Dato","description":"d","t_start_ms":0,"t_end_ms":1000,"severity":"info","recommendation":""},
           {"title":"Importante","description":"d","t_start_ms":2000,"t_end_ms":3000,"severity":"high","recommendation":"r"},
           {"title":"Revisar","description":"d","t_start_ms":1000,"t_end_ms":2000,"severity":"medium","recommendation":"r"}],
         "notes":["Pocos jugadores"],"limitations":["Pantalla, no metros"]}
    """.trimIndent()

    private fun heat(filled: Boolean) = (0 until 8).joinToString(",", "[", "]") { r -> (0 until 12).joinToString(",", "[", "]") { c -> if (filled && r == 2 && c == 3) "0.5" else "0" } }

    @Test
    fun parsesTheApiResponseOrTheResultAlone() {
        val wrapped = """{"clip_id":3,"job_id":9,"segmenter":"sam_simulator","analyst":"muse_spark","created_at":"2026-10-28T10:00:00.000Z","result":$result}"""
        for (text in listOf(wrapped, result)) {
            val a = ClipAnalysis.parse(text)
            assertTrue(a.isSimulator)
            assertEquals(2310, a.framesSent)
            assertEquals(listOf("Importante", "Revisar", "Dato"), a.insights.map { it.title })
            assertEquals(0.6, a.teams["A"]!!.possession!!, 1e-9)
            assertNull(a.teams["A"]!!.pressersPerFrame)
            assertEquals(0.5, a.teams["A"]!!.heatmap!![2][3], 1e-9)
            assertNull(a.teams["B"]!!.heatmap) // all zeros: does not count
            assertNull(a.teams["B"]!!.spreadX)
            assertEquals(listOf("Pocos jugadores"), a.notes)
        }
    }

    @Test
    fun aHeatmapOfAnotherSizeIsIgnoredAndMissingFieldsStayEmpty() {
        val a = ClipAnalysis.parse("""{"summary":"s","teams":{"A":{"heatmap":{"values":[[1,2],[3,4]]}}}}""")
        assertNull(a.teams["A"]!!.heatmap)
        assertEquals("", a.segmenter)
        assertTrue(a.insights.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun aBodyThatIsNotAnAnalysisThrows() {
        ClipAnalysis.parse("""{"error":"analysis not found","status":"queued"}""")
    }
}
