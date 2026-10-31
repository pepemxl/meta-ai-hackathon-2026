package com.ppcoach.tracker.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StatsTest {
    private fun withSquad(): Library {
        var lib = Library().addMatch { Match(it, "A", "B", date = "2026-10-30") }.first
        lib = lib.savePlayer(Player(0, 8, Position.MF, "Cami")).first
        lib = lib.savePlayer(Player(0, 1, Position.GK)).first
        return lib
    }

    @Test
    fun squadNumbersAreUniqueAndInRange() {
        val lib = withSquad()
        assertEquals(listOf(1, 8), lib.playersByNumber.map { it.number })
        assertThrows(IllegalArgumentException::class.java) { lib.savePlayer(Player(0, 8)) }
        assertThrows(IllegalArgumentException::class.java) { lib.savePlayer(Player(0, 100)) }
        // Editing a player keeps its own number.
        val p8 = lib.playerByNumber(8)!!
        val (edited, _) = lib.savePlayer(p8.copy(position = Position.FW, alias = "  Cami la nueve que es muy larga  "))
        assertEquals(Position.FW, edited.player(p8.id)!!.position)
        assertEquals(Player.MAX_ALIAS, edited.player(p8.id)!!.alias.length)
    }

    @Test
    fun theCoachWinsAndGoingBackRestoresTheAnalysis() {
        var lib = withSquad()
        val id = lib.playerByNumber(8)!!.id
        val match = lib.matches[0]
        // Before the report: the video's stats are locked, goals are not.
        assertTrue(StatsCalc.locked(Stat.PASSES_COMPLETED, match))
        assertFalse(StatsCalc.locked(Stat.GOALS, match))
        lib = lib.setStat(1, id, Stat.GOALS, 2)
        val report = AnalysisReportParser.parse("""{"version":1,"source":"meta_sam","players":[{"number":8,"stats":{"minutes":55,"passes_completed":31,"goals":4}}]}""")
        lib = lib.applyReport(1, report, "file", 1000).first
        assertFalse(StatsCalc.locked(Stat.PASSES_COMPLETED, lib.matches[0]))
        val sheet = lib.sheet(1, id)!!
        assertEquals(2, sheet.value(Stat.GOALS)) // the report's goals are ignored
        assertEquals(31, sheet.value(Stat.PASSES_COMPLETED))
        lib = lib.setStat(1, id, Stat.PASSES_COMPLETED, 29)
        assertEquals(29, lib.sheet(1, id)!!.value(Stat.PASSES_COMPLETED))
        assertTrue(lib.sheet(1, id)!!.edited(Stat.PASSES_COMPLETED))
        // Reimporting updates the analysis and keeps the correction.
        lib = lib.applyReport(1, AnalysisReportParser.parse("""{"version":1,"players":[{"number":8,"stats":{"passes_completed":33}}]}"""), "file", 2000).first
        assertEquals(29, lib.sheet(1, id)!!.value(Stat.PASSES_COMPLETED))
        lib = lib.setStat(1, id, Stat.PASSES_COMPLETED, null)
        assertEquals(33, lib.sheet(1, id)!!.value(Stat.PASSES_COMPLETED))
        // Values are clamped: two yellows at most.
        assertEquals(2, lib.setStat(1, id, Stat.YELLOW_CARDS, 5).sheet(1, id)!!.value(Stat.YELLOW_CARDS))
    }

    @Test
    fun theParserAddsNewNumbersAndReportsWhatItIgnored() {
        val lib = withSquad()
        val parsed = AnalysisReportParser.parse(File("../samples/analysis-report.example.json").readText())
        assertEquals("meta_sam", parsed.source)
        val text = """{"version":1,"players":[{"number":23,"stats":{"minutes":200,"sprints":4,"fouls":1}}]}"""
        val (after, summary) = lib.applyReport(1, AnalysisReportParser.parse(text), "file", 1)
        assertEquals(listOf(23), summary.addedNumbers)
        assertEquals(listOf("sprints"), summary.unknownKeys)
        assertEquals(listOf("fouls"), summary.coachKeysIgnored)
        val p23 = after.playerByNumber(23)!!
        assertEquals(Position.MF, p23.position)
        assertEquals(130, after.sheet(1, p23.id)!!.value(Stat.MINUTES))
        assertEquals(3, parsed.players.size)
        for (bad in listOf("nope", """{"version":2,"players":[]}""", """{"version":1,"players":[{"number":0}]}""")) {
            assertThrows(AnalysisReportParser.ReportException::class.java) { AnalysisReportParser.parse(bad) }
        }
    }

    @Test
    fun per90CountsOnlyMatchesWithMinutesAndAccuracyNeedsPasses() {
        val withMinutes = PlayerMatchStats(1, 1, analysis = mapOf(Stat.MINUTES to 45, Stat.PASSES_COMPLETED to 9, Stat.PASSES_FAILED to 3), coach = mapOf(Stat.GOALS to 1))
        val noReport = PlayerMatchStats(2, 1, coach = mapOf(Stat.GOALS to 2))
        val sheets = listOf(withMinutes, noReport)
        assertEquals(2.0, StatsCalc.per90(sheets, Stat.GOALS)!!, 1e-9) // 1 goal in 45 min, not 3
        val t = StatsCalc.totals(sheets)
        assertEquals(3, t.of(Stat.GOALS))
        assertEquals(2, t.matches)
        assertEquals(1, t.minutesMatches)
        assertEquals(0.75, t.passAccuracy!!, 1e-9)
        assertNull(StatsCalc.per90(listOf(noReport), Stat.GOALS))
        assertNull(StatsCalc.totals(listOf(noReport)).passAccuracy)
    }

    @Test
    fun taggedTracksGiveMinutesPerNumberOverTheMatchClips() {
        var lib = withSquad()
        val tracks = listOf(
            ClipAnalysis.TrackInfo(1, "A", 1200.0, 0, 1_200_000, null, null),
            ClipAnalysis.TrackInfo(5, "A", 600.0, 1_200_000, 1_800_000, null, null), // the same player after a cut
            ClipAnalysis.TrackInfo(2, "A", 900.0, 0, 900_000, null, null),
            ClipAnalysis.TrackInfo(3, "B", 900.0, 0, 900_000, null, null), // not tagged
        )
        val tags = mapOf(1 to 8, 5 to 8, 2 to 11)
        val seconds = StatsCalc.secondsByNumber(tracks, tags)
        assertEquals(mapOf(8 to 1800, 11 to 900), seconds)
        lib = lib.addClip { Clip(it, 1, "c1", trackTags = tags, playerSeconds = seconds) }.first
        lib = lib.addClip { Clip(it, 1, "c2", playerSeconds = mapOf(8 to 1500)) }.first
        val p8 = lib.playerByNumber(8)!!.id
        lib = lib.setStat(1, p8, Stat.GOALS, 1)
        val (after, summary) = lib.syncVideoMinutes(1, "meta_sam", 5)
        assertEquals(55, after.sheet(1, p8)!!.value(Stat.MINUTES)) // (1800 + 1500) s
        assertEquals(1, after.sheet(1, p8)!!.value(Stat.GOALS))
        assertEquals(listOf(11), summary.addedNumbers)
        assertEquals("video", after.matches[0].analysis!!.via)
        // The minutes replace only the minutes: an imported pass count stays.
        val withPasses = after.applyReport(1, AnalysisReportParser.parse("""{"version":1,"players":[{"number":8,"stats":{"passes_completed":10}}]}"""), "file", 6).first
        val again = withPasses.syncVideoMinutes(1, "meta_sam", 7).first
        assertEquals(10, again.sheet(1, p8)!!.value(Stat.PASSES_COMPLETED))
    }

    @Test
    fun taggedTracksGiveMetersAndTopSpeedPerPlayerOnlyWithThePitch() {
        val tracks = listOf(
            ClipAnalysis.TrackInfo(1, "A", 600.0, 0, 600_000, 1200.4, 6.25),
            ClipAnalysis.TrackInfo(2, "A", 300.0, 600_000, 900_000, 610.0, 7.04), // the same player, faster
            ClipAnalysis.TrackInfo(3, "A", 300.0, 0, 300_000, null, null), // no pitch in this stretch
        )
        val tags = mapOf(1 to 8, 2 to 8, 3 to 4)
        assertEquals(mapOf(8 to 1810), StatsCalc.metersByNumber(tracks, tags))
        assertEquals(mapOf(8 to 70), StatsCalc.topSpeedByNumber(tracks, tags))
        var lib = Library().addMatch { Match(it, "A", "B", date = "2026-10-30") }.first.addMatch { Match(it, "A", "C", date = "2026-11-01") }.first
        lib = lib.addClip { Clip(it, 1, "c1", playerSeconds = mapOf(8 to 900, 4 to 300), playerMeters = mapOf(8 to 1810), playerTopSpeed = mapOf(8 to 70)) }.first
        lib = lib.addClip { Clip(it, 2, "c2", playerSeconds = mapOf(8 to 600), playerMeters = mapOf(8 to 1000), playerTopSpeed = mapOf(8 to 81)) }.first
        val both = lib.videoOf(8, setOf(1, 2))
        assertEquals(1500, both.seconds)
        assertEquals(2810, both.meters)
        assertEquals(112.4, both.metersPerMinute!!, 0.01)
        assertEquals(8.1, both.topSpeed!!, 1e-9)
        assertEquals(29.16, both.topSpeedKmh!!, 0.01)
        assertEquals(1810, lib.videoOf(8, setOf(1)).meters)
        val keeper = lib.videoOf(4, setOf(1, 2))
        assertEquals(300, keeper.seconds)
        assertNull(keeper.meters)
        assertNull(keeper.metersPerMinute)
        assertTrue(lib.videoOf(10, setOf(1, 2)).isEmpty)
        assertEquals(lib, LibraryCodec.decode(LibraryCodec.encode(lib)))
    }

    @Test
    fun theLibraryKeepsSquadStatsAndTagsAndAVersion1LibraryStillOpens() {
        var lib = withSquad()
        val id = lib.playerByNumber(8)!!.id
        lib = lib.applyReport(1, AnalysisReportParser.parse("""{"version":1,"source":"meta_sam","generated_at":"g","players":[{"number":8,"stats":{"minutes":55}}]}"""), "file", 9).first
        lib = lib.setStat(1, id, Stat.GOALS, 1)
        lib = lib.addClip { Clip(it, 1, "c", trackTags = mapOf(3 to 8), playerSeconds = mapOf(8 to 60)) }.first
        val back = LibraryCodec.decode(LibraryCodec.encode(lib))
        assertEquals(lib, back)
        assertEquals(2, JSONObject(LibraryCodec.encode(lib)).getInt("version"))
        val v1 = LibraryCodec.decode("""{"version":1,"matches":[{"id":1,"home":"A","away":"B"}],"clips":[]}""")
        assertTrue(v1.players.isEmpty() && v1.matchStats.isEmpty())
        assertNull(v1.matches[0].analysis)
        // Deleting a player or a match deletes their stats.
        assertTrue(back.removePlayer(id).matchStats.isEmpty())
        assertTrue(back.removeMatch(1).matchStats.isEmpty())
    }

    @Test
    fun theAnalysisReadsThePlayerTracks() {
        val text = """{"teams":{"A":{}},"video":{"fps":10},
            "tracks":[{"id":0,"label":"ball","frames":50},{"id":1,"label":"player","team":"A","frames":300,"first_ms":0,"last_ms":30000},
                      {"id":2,"label":"player","team":"B","frames":900,"first_ms":0,"last_ms":90000}],
            "pitch":{"tracks":[{"id":2,"team":"B","distance_m":210.5,"seconds":90,"top_speed":6.2}]}}"""
        val a = ClipAnalysis.parse(text)
        assertEquals(listOf(2, 1), a.tracks.map { it.id })
        assertEquals(90.0, a.tracks[0].seconds, 1e-9)
        assertEquals(210.5, a.tracks[0].distanceM!!, 1e-9)
        assertNull(a.tracks[1].distanceM)
    }
}
