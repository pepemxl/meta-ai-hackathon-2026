package com.ppcoach.tracker.data

import com.ppcoach.tracker.analysis.PitchSampler
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PitchMaskTest {
    private val fixture = JSONObject(File("../../worker/tests/fixtures/pitch_mask_cases.json").readText())

    /** Draws a case the same way worker/tests/test_pitch_mask.py does. */
    private fun raster(spec: JSONObject): IntArray {
        fun rgb(a: JSONArray) = (a.getInt(0) shl 16) or (a.getInt(1) shl 8) or a.getInt(2)
        val grid = IntArray(PitchMask.GRID_W * PitchMask.GRID_H) { rgb(spec.getJSONArray("background")) }
        val shapes = spec.getJSONArray("shapes")
        for (k in 0 until shapes.length()) {
            val shape = shapes.getJSONObject(k)
            val p = shape.getJSONArray("poly")
            val pts = (0 until p.length() step 2).map { p.getDouble(it) to p.getDouble(it + 1) }
            val color = rgb(shape.getJSONArray("rgb"))
            for (y in 0 until PitchMask.GRID_H) for (x in 0 until PitchMask.GRID_W) {
                if (PitchMask.pointInPolygon(x.toDouble() / (PitchMask.GRID_W - 1), y.toDouble() / (PitchMask.GRID_H - 1), pts)) grid[y * PitchMask.GRID_W + x] = color
            }
        }
        return grid
    }

    @Test
    fun sameResultAsTheWorker() {
        assertEquals(PitchMask.GRID_W, fixture.getJSONArray("grid").getInt(0))
        assertEquals(PitchMask.GRID_H, fixture.getJSONArray("grid").getInt(1))
        val cases = fixture.getJSONObject("cases")
        for (name in cases.keys()) {
            val c = cases.getJSONObject(name)
            val got = PitchMask.fromGrid(raster(c.getJSONObject("spec")))
            val poly = c.getJSONArray("polygon")
            assertEquals("$name fraction", c.getDouble("fraction"), got.fraction, 0.0)
            assertEquals("$name polygon", (0 until poly.length()).map { poly.getDouble(it) }, got.polygon)
        }
        assertTrue(cases.length() >= 4)
    }

    @Test
    fun broadcastCaseIsAPitchAndTheRingIsNot() {
        val cases = fixture.getJSONObject("cases")
        assertTrue(PitchMask.fromGrid(raster(cases.getJSONObject("broadcast").getJSONObject("spec"))).hasPitch)
        assertFalse(PitchMask.fromGrid(raster(cases.getJSONObject("ring_of_grass").getJSONObject("spec"))).hasPitch)
        assertFalse(PitchMask.fromGrid(raster(cases.getJSONObject("no_grass").getJSONObject("spec"))).hasPitch)
    }

    @Test
    fun hsvMatchesOpenCvRanges() {
        assertTrue(PitchMask.isGrass(50, 140, 60))
        assertFalse(PitchMask.isGrass(120, 110, 100)) // stands
        assertFalse(PitchMask.isGrass(20, 35, 22)) // too dark
        assertFalse(PitchMask.isGrass(235, 235, 235)) // white line
        assertFalse(PitchMask.isGrass(30, 90, 200)) // blue
    }

    @Test
    fun gridPointsAndColorConversion() {
        val xs = PitchMask.gridX(1280)
        assertEquals(4, xs[0])
        assertEquals(1276, xs[159])
        assertEquals(0, PitchMask.gridY(90)[0])
        // BT.601 limited range: black, white and a saturated green.
        assertEquals(0x000000, PitchSampler.yuvToRgb(16, 128, 128))
        assertEquals(0xFEFEFE, PitchSampler.yuvToRgb(235, 128, 128) and 0xFEFEFE)
        val green = PitchSampler.yuvToRgb(112, 85, 75)
        assertTrue(PitchMask.isGrass((green shr 16) and 0xFF, (green shr 8) and 0xFF, green and 0xFF))
    }

    @Test
    fun yamlSectionIsWrittenAndReadBack() {
        val section = PitchMask.yamlSection(listOf(Triple(0, 0.62, listOf(0.0, 0.41, 1.0, 0.38, 1.0, 1.0)), Triple(1, 0.03, emptyList())))
        assertTrue(section.startsWith("pitch:\n  method: \"hsv_v1\"\n  grid: [160, 90]\n"))
        assertTrue(section.contains("  with_pitch: 1\n"))
        assertTrue(section.contains("    - [0, 0.6200, [0.0000, 0.4100, 1.0000, 0.3800, 1.0000, 1.0000]]\n"))
        assertTrue(section.contains("    - [1, 0.0300, []]\n"))
        val meta = ChunkMeta(1, 1, "video_part_001.mp4", 60, 0, 1000, 0, 1280, 720, 1_500_000, listOf(FrameRow(0, 0, 0), FrameRow(1, 3, 100)), 0, "a".repeat(64))
        val yaml = Chunks.chunkYaml(ClipInfo(1, 1, "m/c.mp4", 30.0, 30, 1000), meta, section)
        val back = Chunks.parseChunkYaml(yaml)!!
        assertEquals(1, back.withPitch)
        assertEquals(2, back.map.size) // the pitch rows do not get mixed with the frame map
    }
}
