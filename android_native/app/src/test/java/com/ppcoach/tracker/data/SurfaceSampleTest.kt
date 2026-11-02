package com.ppcoach.tracker.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceSampleTest {
    // Dirt in the sun (hue 15, saturation 96, value 185) and in the shade.
    private val sun = 0xB99673
    private val shade = 0x8C7155

    @Test
    fun aPatchGivesTheRangesAroundItsColorsAndTapsAddUp() {
        val one = SurfaceSample.fromPixels(IntArray(100) { sun })!!
        assertEquals(11..19, one.hue)
        assertEquals(76..116, one.sat)
        assertEquals(155..215, one.value)
        assertTrue(one.matches(0xB9, 0x96, 0x73))
        assertFalse(one.matches(0x8C, 0x71, 0x55)) // the shade is darker than this tap
        assertFalse(one.matches(50, 140, 60)) // grass
        val both = one.plus(SurfaceSample.fromPixels(IntArray(100) { shade })!!)
        assertEquals(2, both.taps)
        assertTrue(both.matches(0x8C, 0x71, 0x55) && both.matches(0xB9, 0x96, 0x73))
        // A few odd pixels (a line, a shoe) do not stretch the ranges.
        val mixed = SurfaceSample.fromPixels(IntArray(100) { if (it < 3) 0xFFFFFF else sun })!!
        assertEquals(one, mixed)
        assertNull(SurfaceSample.fromPixels(IntArray(SurfaceSample.MIN_PIXELS - 1) { sun }))
        // A tap that took a red jersey too: refused, not a sample of every hue.
        assertNull(SurfaceSample.fromPixels(IntArray(100) { if (it < 20) 0xDC2828 else sun }))
    }

    @Test
    fun itTravelsAndIsKeptWithTheSavedPitch() {
        val sample = SurfaceSample(11..19, 76..116, 155..215, taps = 3)
        val api = sample.apiJson()
        assertEquals(setOf("hue", "sat", "val"), api.keys().asSequence().toSet())
        assertEquals("[11,19] [76,116] [155,215]", listOf("hue", "sat", "val").joinToString(" ") { api.getJSONArray(it).toString() })
        val p = SavedPitch(1, "Llano", surface = PitchSurface.DIRT, sample = sample)
        assertEquals(p, SavedPitch.fromJson(p.toJson()))
        assertNull(SavedPitch.fromJson(SavedPitch(1, "Sin muestra").toJson())!!.sample)
        // Out of range or upside down: no sample.
        assertNull(SurfaceSample.fromJson(JSONObject("""{"hue":[20,10],"sat":[0,255],"val":[0,255]}""")))
        assertNull(SurfaceSample.fromJson(JSONObject("""{"hue":[0,200],"sat":[0,255],"val":[0,255]}""")))
    }
}
