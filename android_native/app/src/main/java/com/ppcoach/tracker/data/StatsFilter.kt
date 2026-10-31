package com.ppcoach.tracker.data

/**
 * Which matches add up in the stats tab and on a player's page: all; the
 * team's last 3, 5 or 10 by date; a range of dates (one day is fine); or
 * matches picked one by one. It lives in the ViewModel, shared by both.
 */
sealed interface StatsFilter {
    data object All : StatsFilter
    data class Last(val n: Int) : StatsFilter
    /** yyyy-MM-dd, both included. */
    data class Dates(val from: String, val to: String) : StatsFilter
    data class Picked(val ids: Set<Int>) : StatsFilter

    /** The matches that add up, most recent first. */
    fun matches(lib: Library): List<Match> {
        val byDate = lib.matches.sortedWith(compareByDescending<Match> { it.date }.thenByDescending { it.createdAt })
        return when (this) {
            All -> byDate
            is Last -> byDate.take(n)
            is Dates -> {
                val (a, b) = if (from <= to) from to to else to to from
                byDate.filter { it.date in a..b }
            }
            is Picked -> byDate.filter { it.id in ids }
        }
    }

    companion object {
        val LAST = listOf(3, 5, 10)
    }
}

/** One row of the stats tab: a player's totals over the filtered matches. */
data class StatsRow(val player: Player, val totals: StatsCalc.Totals, val sheets: List<PlayerMatchStats>) {
    fun per90(s: Stat): Double? = StatsCalc.per90(sheets, s)
}

object StatsTable {
    /** What the tab can sort by: any stat, plus pass accuracy. */
    const val PASS_ACCURACY = "pass_accuracy"

    /** Every squad player over [matches], sorted by [sortKey] (a Stat key or PASS_ACCURACY), highest first, then by number. */
    fun rows(lib: Library, matches: List<Match>, sortKey: String): List<StatsRow> {
        val ids = matches.map { it.id }.toSet()
        val rows = lib.players.map { p ->
            val sheets = lib.sheetsOfPlayer(p.id).filter { it.matchId in ids }
            StatsRow(p, StatsCalc.totals(sheets), sheets)
        }
        val value: (StatsRow) -> Double = { r ->
            if (sortKey == PASS_ACCURACY) r.totals.passAccuracy ?: -1.0 else (Stat.fromKey(sortKey)?.let { r.totals.of(it) } ?: 0).toDouble()
        }
        return rows.sortedWith(compareByDescending<StatsRow>(value).thenBy { it.player.number })
    }
}
