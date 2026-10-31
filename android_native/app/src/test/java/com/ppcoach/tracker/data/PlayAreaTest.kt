package com.ppcoach.tracker.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayAreaTest {
    private val square = PlayArea(listOf(0.25 to 0.25, 0.75 to 0.25, 0.75 to 0.75, 0.25 to 0.75), 0.05)

    @Test
    fun theMarginIsAFractionOfTheHeightAcrossAndAlong() {
        // The same cases as the worker's test_pitch_mask.py: a panorama 4 times wider than tall.
        assertTrue(square.contains(0.5, 0.5, 4.0))
        assertTrue(square.contains(0.5, 0.79, 4.0))
        assertFalse(square.contains(0.5, 0.81, 4.0))
        assertTrue(square.contains(0.76, 0.5, 4.0))
        assertFalse(square.contains(0.77, 0.5, 4.0))
    }

    @Test
    fun sidesAreSplitAndPointsRemovedDownToFour() {
        val five = square.splitSide(3)
        assertEquals(5, five.points.size)
        assertEquals(0.25 to 0.5, five.points[4])
        assertEquals(square.points, five.without(4).points)
        // Never fewer than four, never more than sixteen.
        assertEquals(square, square.without(0))
        var many = square
        repeat(20) { many = many.splitSide(0) }
        assertEquals(PlayArea.MAX_POINTS, many.points.size)
        assertEquals(1.0 to 0.0, square.moved(1, 1.4, -0.2).points[1])
    }

    @Test
    fun theApiGetsPointsAndMargin() {
        val json = square.splitSide(0).copy(tMs = 1500).apiJson()
        assertEquals(5, json.getJSONArray("points").length())
        assertEquals(0.5, json.getJSONArray("points").getJSONObject(1).getDouble("x"), 0.0)
        assertEquals(0.05, json.getDouble("margin"), 0.0)
        assertFalse(json.has("tMs"))
    }

    @Test
    fun itIsKeptWithTheClipAndTheMatch() {
        var lib = Library()
        lib = lib.addMatch { Match(it, "A", "B", date = "2026-10-31") }.first
        lib = lib.addClip { Clip(it, 1, "c") }.first
        lib = lib.updateClip(1) { it.copy(playArea = square.copy(tMs = 2000)) }.updateMatch(1) { it.copy(playArea = square) }
        assertEquals(lib, LibraryCodec.decode(LibraryCodec.encode(lib)))
        // An area that is not one is dropped when read.
        assertNull(PlayArea.fromJson(JSONObject("""{"points":[{"x":0.1,"y":0.1},{"x":0.9,"y":0.1},{"x":0.5,"y":0.9}],"margin":0.03}""")))
        assertNull(PlayArea.fromJson(JSONObject("""{"points":[{"x":0.1,"y":0.1},{"x":0.9,"y":0.1},{"x":0.9,"y":0.9},{"x":1.5,"y":0.9}]}""")))
        assertNull(PlayArea.fromJson(JSONObject("""{"points":[{"x":0.1,"y":0.1},{"x":0.9,"y":0.1},{"x":0.9,"y":0.9},{"x":0.1,"y":0.9}],"margin":0.4}""")))
    }
}
