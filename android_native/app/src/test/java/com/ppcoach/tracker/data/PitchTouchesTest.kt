package com.ppcoach.tracker.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PitchTouchesTest {
    /** Same names and meters as the worker's template.py (shared fixture, checked on both sides). */
    @Test
    fun templateMatchesTheWorker() {
        val fixture = JSONObject(File("../../worker/tests/fixtures/pitch_template.json").readText())
        for (size in listOf(11, 7)) {
            val expected = fixture.getJSONObject(size.toString())
            val pts = PitchTemplate.points(size)
            assertEquals(expected.keys().asSequence().toSet(), pts.keys)
            for ((name, xy) in pts) {
                val e = expected.getJSONArray(name)
                assertEquals("$size $name x", e.getDouble(0), xy.first, 1e-3)
                assertEquals("$size $name y", e.getDouble(1), xy.second, 1e-3)
            }
        }
    }

    @Test
    fun fourPointsAskForAFifth() {
        var t = PitchTouches(size = 7)
        assertEquals(PitchTouches.State.NEED_MORE, t.state)
        for (n in listOf("corner_far_left", "corner_far_right", "corner_near_left", "corner_near_right")) t = t.with(Touch(n, 0.5, 0.5))
        assertEquals(PitchTouches.State.NEED_FIFTH, t.state)
        t = t.with(Touch("center", 0.5, 0.5))
        assertEquals(PitchTouches.State.READY, t.state)
        // The same name again replaces the point, never duplicates it.
        assertEquals(5, t.with(Touch("center", 0.4, 0.4)).points.size)
    }

    @Test
    fun previewHomographyRecoversAKnownProjection() {
        // A perspective map pitch → normalized image.
        val h = doubleArrayOf(0.012, 0.004, 0.5, 0.0005, -0.01, 0.45, 0.0002, 0.006, 1.0)
        val tpl = PitchTemplate.points(7)
        val names = listOf("corner_far_left", "corner_far_right", "corner_near_left", "corner_near_right", "center")
        var t = PitchTouches(size = 7)
        for (n in names) {
            val (x, y) = Homography.apply(h, tpl.getValue(n).first, tpl.getValue(n).second)!!
            t = t.with(Touch(n, x, y))
        }
        val est = t.homography()
        assertNotNull(est)
        for (n in listOf("spot_left", "box_far_right", "circle_near")) {
            val (ex, ey) = Homography.apply(h, tpl.getValue(n).first, tpl.getValue(n).second)!!
            val (gx, gy) = Homography.apply(est!!, tpl.getValue(n).first, tpl.getValue(n).second)!!
            assertEquals(ex, gx, 1e-6)
            assertEquals(ey, gy, 1e-6)
        }
        assertNull(PitchTouches(points = listOf(Touch("center", 0.1, 0.1))).homography())
    }

    @Test
    fun requestAndStorage() {
        val t = PitchTouches(7, 1200, listOf(Touch("center", 0.5, 0.4), Touch("spot_left", 0.2, 0.45)))
        val req = CalibrationRequest.build(t, emptyList())!!
        assertEquals(7, req.getJSONObject("pitch").getInt("size"))
        val f = req.getJSONArray("frames").getJSONObject(0)
        assertEquals(1200L, f.getLong("t_ms"))
        assertEquals("spot_left", f.getJSONArray("points").getJSONObject(1).getString("name"))
        assertEquals(t, PitchTouches.fromJson(t.toJson()))
        // Out-of-range points are dropped when reading.
        val bad = JSONObject("""{"size": 9, "points": [{"name": "center", "x": 2.0, "y": 0.5}]}""")
        assertEquals(PitchTouches(11, 0, emptyList()), PitchTouches.fromJson(bad))
    }

    @Test
    fun libraryKeepsThePitchAnswerAndThePoints() {
        var lib = Library()
        lib = lib.addMatch { Match(it, "A", "B", date = "2026-10-28", pitchMode = PitchMode.NO_LINES) }.first
        val touches = PitchTouches(7, 500, listOf(Touch("center", 0.5, 0.5)))
        lib = lib.addClip { Clip(it, 1, "c", pitchMode = PitchMode.OFF, touches = touches) }.first
        val back = LibraryCodec.decode(LibraryCodec.encode(lib))
        assertEquals(lib, back)
        val unknown = LibraryCodec.decode("""{"matches":[{"id":1,"home":"A","away":"B","pitchMode":"sometimes"}],"clips":[]}""")
        assertEquals("", unknown.matches[0].pitchMode)
    }

    @Test
    fun analysisReadsTheBlockInMeters() {
        val heat = (0 until 8).joinToString(",", "[", "]") { r -> (0 until 12).joinToString(",", "[", "]") { c -> if (r == 0 && c == 0) "1" else "0" } }
        val text = """{"summary":"s","teams":{},"pitch":{"length_m":60,"width_m":40,"approximate":true,"seconds":12,"frames_with_homography":1.0,
            "teams":{"A":{"meters_per_minute":150.5,"speed_p95":3.9,"shape":{"length":20,"width":24},"heatmap":{"values":$heat}},"B":{"meters_per_minute":null}}}}"""
        val p = ClipAnalysis.parse(text).pitch!!
        assertEquals(7, p.size)
        assertTrue(p.approximate)
        assertEquals(150.5, p.teams["A"]!!.metersPerMinute!!, 1e-9)
        assertEquals(24.0, p.teams["A"]!!.widthM!!, 1e-9)
        assertNotNull(p.teams["A"]!!.heatmap)
        assertNull(p.teams["B"]!!.metersPerMinute)
        assertNull(ClipAnalysis.parse("""{"summary":"s","teams":{}}""").pitch)
    }
}
