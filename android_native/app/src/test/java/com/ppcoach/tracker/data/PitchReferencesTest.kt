package com.ppcoach.tracker.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PitchReferencesTest {
    // A perspective map pitch → normalized image (the same as PitchTouchesTest).
    private val h = doubleArrayOf(0.012, 0.004, 0.5, 0.0005, -0.01, 0.45, 0.0002, 0.006, 1.0)

    private fun img(p: Pair<Double, Double>) = Homography.apply(h, p.first, p.second)!!

    /** A finger stroke along part of a named line of the 7-a-side pitch. */
    private fun stroke(name: String, from: Double = 0.2, to: Double = 0.8, n: Int = 9): Stroke {
        val (a, b) = PitchTemplate.namedLines(7).getValue(name)
        return (0 until n).map { k ->
            val t = from + (to - from) * k / (n - 1)
            img((a.first + (b.first - a.first) * t) to (a.second + (b.second - a.second) * t))
        }
    }

    /** The coach names the same lines as the worker's template.py (shared fixture, checked on both sides). */
    @Test
    fun namedLinesMatchTheWorker() {
        val fixture = JSONObject(File("../../worker/tests/fixtures/pitch_template_lines.json").readText())
        for (size in listOf(11, 7)) {
            val expected = fixture.getJSONObject(size.toString())
            val lines = PitchTemplate.namedLines(size)
            assertEquals(expected.keys().asSequence().toSet(), lines.keys)
            for ((name, seg) in lines) {
                val e = expected.getJSONArray(name)
                assertEquals("$size $name", e.getJSONArray(0).getDouble(0), seg.first.first, 1e-3)
                assertEquals("$size $name", e.getJSONArray(0).getDouble(1), seg.first.second, 1e-3)
                assertEquals("$size $name", e.getJSONArray(1).getDouble(0), seg.second.first, 1e-3)
                assertEquals("$size $name", e.getJSONArray(1).getDouble(1), seg.second.second, 1e-3)
            }
        }
    }

    @Test
    fun paintedLinesCountAsMarksAndFixThePreview() {
        val tpl = PitchTemplate.points(7)
        var t = PitchTouches(size = 7)
        for (n in listOf("center", "spot_left", "corner_far_right")) t = t.with(Touch(n, img(tpl.getValue(n)).first, img(tpl.getValue(n)).second))
        t = t.with(PaintedLine(null, stroke("halfway")))
        assertEquals(3, t.marks) // an unnamed line does not fix the pitch
        t = t.with(PaintedLine("touch_near", stroke("touch_near")))
        assertEquals(PitchTouches.State.NEED_FIFTH, t.state)
        t = t.with(PaintedLine("goal_left", stroke("goal_left", 0.3, 0.7)))
        assertEquals(PitchTouches.State.READY, t.state)
        // The same name again replaces the line; unnamed ones add up.
        assertEquals(3, t.with(PaintedLine("goal_left", stroke("goal_left"))).lines.size)
        assertEquals(4, t.with(PaintedLine(null, stroke("box_left"))).lines.size)
        val est = t.homography()!!
        for (n in listOf("box_far_right", "circle_near", "corner_near_left")) {
            val (ex, ey) = img(tpl.getValue(n))
            val (gx, gy) = Homography.apply(est, tpl.getValue(n).first, tpl.getValue(n).second)!!
            assertEquals(ex, gx, 1e-6)
            assertEquals(ey, gy, 1e-6)
        }
    }

    @Test
    fun strokesAreSimplifiedAndTapsAreNotStrokes() {
        val long = (0..300).map { it / 300.0 to 0.5 + 0.1 * kotlin.math.sin(it / 30.0) }
        val s = Strokes.simplify(long)
        assertEquals(Strokes.MAX_POINTS, s.size)
        assertEquals(long.first(), s.first())
        assertEquals(long.last().first, s.last().first, 1e-9)
        assertFalse(Strokes.isStroke(listOf(0.5 to 0.5, 0.501 to 0.5)))
        assertTrue(Strokes.isStroke(listOf(0.1 to 0.5, 0.5 to 0.5)))
    }

    @Test
    fun calibrationRequestCarriesTheFrameAndTheReferences() {
        assertNull(CalibrationRequest.build(null, emptyList()))
        val touches = PitchTouches(7, 1200, listOf(Touch("center", 0.5, 0.4)), listOf(PaintedLine("touch_near", stroke("touch_near")), PaintedLine(null, stroke("halfway"))))
        val refs = (1..7).map { PitchReference(it, if (it == 2) PitchReference.FRAME else PitchReference.PHOTO, tMs = if (it == 2) 4000 else -1, strokes = listOf(stroke("box_left"))) }
        val body = CalibrationRequest.build(touches, refs)!!
        assertEquals(7, body.getJSONObject("pitch").getInt("size"))
        val f = body.getJSONArray("frames").getJSONObject(0)
        assertEquals(1200L, f.getLong("t_ms"))
        val lines = f.getJSONArray("lines")
        assertEquals("touch_near", lines.getJSONObject(0).getString("name"))
        assertTrue(lines.getJSONObject(1).isNull("name"))
        val r = body.getJSONArray("references")
        assertEquals(6, r.length()) // the API keeps 6
        assertEquals(1, r.getJSONObject(0).getInt("n"))
        assertFalse(r.getJSONObject(0).has("t_ms"))
        assertEquals(4000L, r.getJSONObject(1).getLong("t_ms"))
        assertEquals("frame", r.getJSONObject(1).getString("source"))
        assertEquals(9, r.getJSONObject(0).getJSONArray("strokes").getJSONObject(0).getJSONArray("points").length())
        // Only references: no frames, still a body.
        val onlyRefs = CalibrationRequest.build(null, refs.take(1), size = 11)!!
        assertEquals(0, onlyRefs.getJSONArray("frames").length())
    }

    @Test
    fun libraryKeepsTheReferencesAndThePaintedLines() {
        var lib = Library()
        lib = lib.addMatch { Match(it, "A", "B", date = "2026-10-28") }.first
        val ref = PitchReference(1, PitchReference.PHOTO, strokes = listOf(listOf(0.1 to 0.2, 0.3 to 0.4)), createdAt = 5)
        lib = lib.updateMatch(1) { it.copy(references = listOf(ref)) }
        val touches = PitchTouches(7, 500, emptyList(), listOf(PaintedLine(null, listOf(0.1 to 0.8, 0.9 to 0.8)), PaintedLine("halfway", listOf(0.5 to 0.2, 0.5 to 0.9))))
        lib = lib.addClip { Clip(it, 1, "c", touches = touches) }.first
        val back = LibraryCodec.decode(LibraryCodec.encode(lib))
        assertEquals(listOf(ref), back.match(1)!!.references)
        assertEquals(2, back.match(1)!!.nextReferenceId)
        assertEquals(touches, back.clip(1)!!.touches)
    }
}
