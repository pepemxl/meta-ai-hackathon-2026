package com.ppcoach.tracker.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * A formation: the goalkeeper plus lines from defense to attack. The key is
 * stable (it goes in library.json). The first line defends, the last one
 * attacks and the ones in between are midfield.
 */
data class Formation(val key: String, val size: Int, val lines: List<Int>) {
    /** Slot 0 is the goalkeeper; then each line, left to right. */
    val slots: List<Slot> by lazy {
        val out = mutableListOf(Slot(0, Position.GK, 0.5, 0.92))
        lines.forEachIndexed { li, n ->
            val pos = when (li) {
                0 -> Position.DF
                lines.lastIndex -> Position.FW
                else -> Position.MF
            }
            // From the own goal (bottom) up to attack (top).
            val y = 0.78 - 0.66 * (li + 1) / lines.size
            for (k in 0 until n) out += Slot(out.size, pos, (k + 1.0) / (n + 1), y)
        }
        out
    }

    companion object {
        val ELEVEN = listOf(
            Formation("4-4-2", 11, listOf(4, 4, 2)), Formation("4-3-3", 11, listOf(4, 3, 3)), Formation("4-2-3-1", 11, listOf(4, 2, 3, 1)),
            Formation("4-1-4-1", 11, listOf(4, 1, 4, 1)), Formation("4-5-1", 11, listOf(4, 5, 1)), Formation("3-5-2", 11, listOf(3, 5, 2)),
            Formation("3-4-3", 11, listOf(3, 4, 3)), Formation("5-3-2", 11, listOf(5, 3, 2)), Formation("5-4-1", 11, listOf(5, 4, 1)),
        )
        val SEVEN = listOf(
            Formation("2-3-1", 7, listOf(2, 3, 1)), Formation("3-2-1", 7, listOf(3, 2, 1)),
            Formation("3-1-2", 7, listOf(3, 1, 2)), Formation("2-1-2-1", 7, listOf(2, 1, 2, 1)),
        )
        val ALL = ELEVEN + SEVEN

        /** The keys of 11 and of 7 never collide. */
        fun of(key: String?): Formation? = ALL.firstOrNull { it.key == key }
        fun forSize(size: Int): List<Formation> = if (size == 7) SEVEN else ELEVEN
    }
}

/** One place on the pitch: its line and where it is drawn (0..1, the own goal at the bottom). */
data class Slot(val index: Int, val line: Position, val x: Double, val y: Double)

/**
 * The starting lineup of a match: a formation and a squad number (or none)
 * in each slot. Only numbers, never names. A number is in one place at most.
 */
data class StartingLineup(val formation: String, val numbers: List<Int?>) {
    val shape: Formation get() = Formation.of(formation) ?: Formation.ELEVEN[0]

    /** Puts [number] in [slot] (null empties it); if it was somewhere else, that place is emptied. */
    fun assign(slot: Int, number: Int?): StartingLineup {
        if (slot !in numbers.indices) return this
        val cleared = numbers.map { if (number != null && it == number) null else it }
        return copy(numbers = cleared.toMutableList().also { it[slot] = number?.takeIf { n -> n in Player.NUMBERS } })
    }

    /**
     * Another formation: the goalkeeper stays in goal and each player keeps
     * their line while it has room, in order; who does not fit is left out.
     */
    fun withFormation(key: String): StartingLineup {
        val next = Formation.of(key) ?: return this
        val out = MutableList<Int?>(next.slots.size) { null }
        out[0] = numbers.getOrNull(0)
        for (line in listOf(Position.DF, Position.MF, Position.FW)) {
            val placed = shape.slots.filter { it.line == line }.mapNotNull { numbers.getOrNull(it.index) }
            val free = next.slots.filter { it.line == line }.map { it.index }
            placed.zip(free).forEach { (n, i) -> out[i] = n }
        }
        return StartingLineup(key, out)
    }

    /** Fills the empty slots with squad players not placed yet: those of the slot's line first, then anyone. */
    fun fillWith(players: List<Player>): StartingLineup {
        val out = numbers.toMutableList()
        val left = players.sortedBy { it.number }.filter { p -> p.number !in out }.toMutableList()
        for (pass in 0..1) {
            for (slot in shape.slots) {
                if (out[slot.index] != null) continue
                val p = left.firstOrNull { pass == 1 || it.position == slot.line } ?: continue
                out[slot.index] = p.number
                left.remove(p)
            }
        }
        return copy(numbers = out)
    }

    fun toJson(): JSONObject = JSONObject().put("formation", formation).put("numbers", JSONArray().apply { numbers.forEach { put(it ?: JSONObject.NULL) } })

    companion object {
        fun empty(key: String): StartingLineup = StartingLineup(key, List((Formation.of(key) ?: Formation.ELEVEN[0]).slots.size) { null })

        /** Tolerant: an unknown formation or a broken list gives null; numbers out of range or repeated are dropped. */
        fun fromJson(o: JSONObject?): StartingLineup? {
            val f = Formation.of(o?.optString("formation")) ?: return null
            val arr = o?.optJSONArray("numbers") ?: return null
            val seen = mutableSetOf<Int>()
            val numbers = List(f.slots.size) { i ->
                if (i >= arr.length() || arr.isNull(i)) null else arr.optInt(i, 0).takeIf { it in Player.NUMBERS && seen.add(it) }
            }
            return StartingLineup(f.key, numbers)
        }
    }
}
