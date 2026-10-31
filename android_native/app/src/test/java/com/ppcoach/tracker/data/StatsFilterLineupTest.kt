package com.ppcoach.tracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsFilterLineupTest {
    private fun lib(): Library {
        var lib = Library()
        for ((d, created) in listOf("2026-10-01" to 1L, "2026-10-08" to 2L, "2026-10-15" to 3L, "2026-10-15" to 4L, "2026-10-22" to 5L)) {
            lib = lib.addMatch { Match(it, "A", "B", date = d, createdAt = created) }.first
        }
        return lib
    }

    @Test
    fun theFilterPicksTheMatchesThatAddUp() {
        val lib = lib()
        assertEquals(listOf(5, 4, 3, 2, 1), StatsFilter.All.matches(lib).map { it.id })
        assertEquals(listOf(5, 4, 3), StatsFilter.Last(3).matches(lib).map { it.id })
        assertEquals(5, StatsFilter.Last(10).matches(lib).size)
        // A range, in either order; one day is fine.
        assertEquals(listOf(4, 3, 2), StatsFilter.Dates("2026-10-08", "2026-10-15").matches(lib).map { it.id })
        assertEquals(listOf(4, 3, 2), StatsFilter.Dates("2026-10-15", "2026-10-08").matches(lib).map { it.id })
        assertEquals(listOf(4, 3), StatsFilter.Dates("2026-10-15", "2026-10-15").matches(lib).map { it.id })
        assertEquals(listOf(5, 1), StatsFilter.Picked(setOf(1, 5, 99)).matches(lib).map { it.id })
        assertTrue(StatsFilter.Picked(emptySet()).matches(lib).isEmpty())
    }

    @Test
    fun theTableSortsByAnyStatOverTheFilteredMatches() {
        var lib = lib()
        lib = lib.savePlayer(Player(0, 8)).first.savePlayer(Player(0, 9, Position.FW)).first
        val p8 = lib.playerByNumber(8)!!.id
        val p9 = lib.playerByNumber(9)!!.id
        lib = lib.setStat(5, p8, Stat.GOALS, 1).setStat(1, p9, Stat.GOALS, 3).setStat(5, p9, Stat.PASSES_COMPLETED, 1).setStat(5, p8, Stat.PASSES_COMPLETED, 8)
            .setStat(5, p8, Stat.PASSES_FAILED, 2).setStat(5, p9, Stat.PASSES_FAILED, 3)
        assertEquals(listOf(9, 8), StatsTable.rows(lib, StatsFilter.All.matches(lib), "goals").map { it.player.number })
        // Only the last match: number 8 scored there, 9 did not.
        assertEquals(listOf(8, 9), StatsTable.rows(lib, StatsFilter.Last(1).matches(lib), "goals").map { it.player.number })
        assertEquals(listOf(8, 9), StatsTable.rows(lib, StatsFilter.All.matches(lib), StatsTable.PASS_ACCURACY).map { it.player.number })
        val row = StatsTable.rows(lib, StatsFilter.All.matches(lib), "goals").first()
        assertEquals(3, row.totals.of(Stat.GOALS))
        assertNull(row.per90(Stat.GOALS)) // no minutes
    }

    @Test
    fun formationsHaveTheirPlayersAndStableKeys() {
        assertEquals(9, Formation.ELEVEN.size)
        assertEquals(4, Formation.SEVEN.size)
        assertTrue(Formation.ELEVEN.all { it.slots.size == 11 })
        assertTrue(Formation.SEVEN.all { it.slots.size == 7 })
        assertEquals(Formation.ALL.size, Formation.ALL.map { it.key }.toSet().size)
        val f = Formation.of("4-2-3-1")!!
        assertEquals(listOf(Position.GK) + List(4) { Position.DF } + List(5) { Position.MF } + Position.FW, f.slots.map { it.line })
        assertEquals(listOf(Position.GK, Position.DF, Position.DF, Position.MF, Position.MF, Position.MF, Position.FW), Formation.of("2-3-1")!!.slots.map { it.line })
        assertNull(Formation.of("4-4-3"))
    }

    @Test
    fun aNumberIsInOnePlaceAndChangingFormationKeepsTheLines() {
        var l = StartingLineup.empty("4-4-2")
        l = l.assign(0, 1).assign(1, 4).assign(2, 5).assign(5, 8).assign(9, 9).assign(10, 10)
        l = l.assign(3, 4) // the 4 moves: its old place is emptied
        assertNull(l.numbers[1])
        assertEquals(4, l.numbers[3])
        // 4-4-2 → 3-5-2: the keeper stays, defenders keep defense while it fits, forwards stay forwards.
        val m = l.withFormation("3-5-2")
        assertEquals(1, m.numbers[0])
        assertEquals(listOf(5, 4), m.shape.slots.filter { it.line == Position.DF }.mapNotNull { m.numbers[it.index] })
        assertEquals(listOf(8), m.shape.slots.filter { it.line == Position.MF }.mapNotNull { m.numbers[it.index] })
        assertEquals(listOf(9, 10), m.shape.slots.filter { it.line == Position.FW }.mapNotNull { m.numbers[it.index] })
        // To a smaller line: who does not fit is left out.
        val seven = StartingLineup.empty("4-4-2").assign(1, 2).assign(2, 3).assign(3, 4).withFormation("2-3-1")
        assertEquals(listOf(2, 3), seven.numbers.filterNotNull())
    }

    @Test
    fun fillingWithTheSquadPutsEachLineFirstAndTheLineupSurvivesTheLibrary() {
        val squad = listOf(Player(1, 1, Position.GK), Player(2, 4, Position.DF), Player(3, 9, Position.FW), Player(4, 6, Position.MF), Player(5, 2, Position.DF))
        val l = StartingLineup.empty("2-3-1").fillWith(squad)
        assertEquals(1, l.numbers[0])
        assertEquals(listOf(2, 4), listOf(l.numbers[1], l.numbers[2]))
        assertEquals(9, l.numbers[6])
        assertEquals(6, l.numbers[3])
        assertEquals(5, l.numbers.filterNotNull().size)
        var lib = Library().addMatch { Match(it, "A", "B", date = "2026-10-30", startingLineup = l) }.first
        assertEquals(lib, LibraryCodec.decode(LibraryCodec.encode(lib)))
        val broken = StartingLineup.fromJson(org.json.JSONObject("""{"formation":"2-3-1","numbers":[1,1,200,null]}"""))!!
        assertEquals(listOf(1, null, null, null, null, null, null), broken.numbers)
        assertNull(StartingLineup.fromJson(org.json.JSONObject("""{"formation":"9-9-9","numbers":[]}""")))
    }
}
