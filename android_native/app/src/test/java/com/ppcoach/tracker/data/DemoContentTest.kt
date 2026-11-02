package com.ppcoach.tracker.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DemoContentTest {
    @Test
    fun theSampleMatchHasTheTeamsAndColorsOfItsAnalysis() {
        val result = """{"match":{"home":"Rojos","away":"Celestes","home_color":"red","away_color":"light blue"},"teams":{}}"""
        val m = DemoContent.match(result, "2026-11-01")
        assertEquals(listOf("Rojos", "Celestes"), listOf(m.home, m.away))
        assertEquals(JerseyColor.RED to JerseyColor.LIGHT_BLUE, m.homeColor to m.awayColor)
        assertEquals(0, m.id)
        // The API's envelope works the same; missing names and colors fall back.
        val bare = DemoContent.match("""{"result":{"match":{"home_color":"pink"}}}""", "2026-11-01")
        assertEquals(Triple("A", "B", JerseyColor.RED to JerseyColor.BLUE), Triple(bare.home, bare.away, bare.homeColor to bare.awayColor))
    }
}
