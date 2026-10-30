package com.ppcoach.tracker.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedPitchesTest {
    @Test
    fun libraryKeepsPitchesAndTheMatchPitch() {
        var lib = Library()
        val (l1, norte) = lib.savePitch(SavedPitch(0, "Norte 3", 7, 58.0, 38.5, createdAt = 1))
        val (l2, sur) = l1.savePitch(SavedPitch(0, "Sur", 11))
        lib = l2.addMatch { Match(it, "A", "B", date = "2026-10-30", pitchId = norte.id) }.first
        assertEquals(listOf(1, 2), listOf(norte.id, sur.id))
        assertEquals(3, lib.nextPitchId)
        val back = LibraryCodec.decode(LibraryCodec.encode(lib))
        assertEquals(lib, back)
        assertEquals(58.0 to 38.5, back.pitch(1)!!.dimensions)
        assertTrue(back.pitch(1)!!.measured)
        // Unmeasured: the typical size.
        assertFalse(back.pitch(2)!!.measured)
        assertEquals(105.0 to 68.0, back.pitch(2)!!.dimensions)
    }

    @Test
    fun editingReplacesAndDeletingFreesTheMatch() {
        var lib = Library().savePitch(SavedPitch(0, "Norte", 7)).first
        lib = lib.addMatch { Match(it, "A", "B", date = "2026-10-30", pitchId = 1) }.first
        lib = lib.savePitch(lib.pitch(1)!!.copy(lengthM = 60.0, widthM = 40.0)).first
        assertEquals(1, lib.pitches.size)
        assertEquals(60.0, lib.pitch(1)!!.lengthM)
        lib = lib.removePitch(1)
        assertTrue(lib.pitches.isEmpty())
        assertEquals(0, lib.matches.single().pitchId)
        assertNull(lib.pitch(0))
    }

    @Test
    fun mostRecentlyUsedFirst() {
        var lib = Library()
        lib = lib.savePitch(SavedPitch(0, "b", lastUsedAt = 5)).first
        lib = lib.savePitch(SavedPitch(0, "a", lastUsedAt = 5)).first
        lib = lib.savePitch(SavedPitch(0, "c", lastUsedAt = 9)).first
        assertEquals(listOf("c", "a", "b"), lib.pitchesByUse.map { it.name })
    }

    @Test
    fun measuresAreBothOrNoneInTheApiRanges() {
        assertNull(SavedPitch.measuresError("", ""))
        assertNull(SavedPitch.measuresError("60,5", "40"))
        assertEquals(60.5, SavedPitch.parseMeters(" 60,5 "))
        assertEquals(SavedPitch.MeasuresError.BOTH, SavedPitch.measuresError("60", ""))
        assertEquals(SavedPitch.MeasuresError.BOTH, SavedPitch.measuresError("60", "abc"))
        assertEquals(SavedPitch.MeasuresError.RANGE, SavedPitch.measuresError("140", "60"))
        assertEquals(SavedPitch.MeasuresError.RANGE, SavedPitch.measuresError("60", "10"))
        assertEquals(SavedPitch.MeasuresError.LONGER, SavedPitch.measuresError("40", "60"))
        // A stored pitch with a bad measure keeps neither.
        val bad = SavedPitch.fromJson(JSONObject("""{"id": 4, "name": "x", "size": 9, "lengthM": 60, "widthM": 500}"""))!!
        assertEquals(11, bad.size)
        assertNull(bad.lengthM)
        assertNull(bad.widthM)
    }

    @Test
    fun touchesOnAPitchUseItsMetersAndKeepTheMarksOfTheSameSize() {
        val marks = PitchTouches(7, 800, listOf(Touch("center", 0.5, 0.5)))
        val norte = SavedPitch(3, "Norte", 7, 58.0, 38.0)
        val on = marks.on(norte)
        assertEquals(3, on.pitchId)
        assertEquals(58.0 to 38.0, on.dimensions)
        assertEquals(marks.points, on.points)
        // Another size: the names change, the marks start over.
        val eleven = on.on(null, 11)
        assertEquals(0, eleven.pitchId)
        assertTrue(eleven.points.isEmpty())
        assertEquals(105.0 to 68.0, eleven.dimensions)
        assertEquals(on, PitchTouches.fromJson(on.toJson()))
    }

    @Test
    fun theCalibrationCarriesTheMetersWhenMeasured() {
        val t = PitchTouches(7, 0, listOf(Touch("center", 0.5, 0.5))).on(SavedPitch(1, "Norte", 7, 58.0, 38.0))
        val pitch = CalibrationRequest.build(t, emptyList())!!.getJSONObject("pitch")
        assertEquals(7, pitch.getInt("size"))
        assertEquals(58.0, pitch.getDouble("length_m"), 0.0)
        assertEquals(38.0, pitch.getDouble("width_m"), 0.0)
        val typical = CalibrationRequest.build(t.on(SavedPitch(2, "Sur", 7)), emptyList())!!.getJSONObject("pitch")
        assertFalse(typical.has("length_m"))
        // Measured after the marks: the request takes the meters it is given.
        val later = CalibrationRequest.build(t.on(null, 7), emptyList(), lengthM = 60.0, widthM = 40.0)!!.getJSONObject("pitch")
        assertEquals(60.0, later.getDouble("length_m"), 0.0)
    }
}
