package com.ppcoach.tracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Leaderboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ppcoach.tracker.data.Formation
import com.ppcoach.tracker.data.Library
import com.ppcoach.tracker.data.Match
import com.ppcoach.tracker.data.Player
import com.ppcoach.tracker.data.Stat
import com.ppcoach.tracker.data.StartingLineup
import com.ppcoach.tracker.data.StatsFilter
import com.ppcoach.tracker.data.StatsTable
import com.ppcoach.tracker.ui.CoachViewModel
import com.ppcoach.tracker.ui.Screen
import com.ppcoach.tracker.ui.components.EmptyState
import com.ppcoach.tracker.ui.components.FilterChips
import com.ppcoach.tracker.ui.components.PpCard
import com.ppcoach.tracker.ui.components.PpTextButton
import com.ppcoach.tracker.ui.components.PpTopBar
import com.ppcoach.tracker.ui.components.PrimaryButton
import com.ppcoach.tracker.ui.i18n.LocalStrings
import com.ppcoach.tracker.ui.i18n.Strings
import com.ppcoach.tracker.ui.theme.PpTheme
import java.time.Instant
import java.time.ZoneOffset

// ── the stats tab ────────────────────────────────────────────────────────────

/**
 * Every squad player over the matches of the filter, sorted by any stat; the
 * sorted value big on the right, with its value per 90 minutes. A root screen.
 */
@Composable
fun StatsTabScreen(vm: CoachViewModel, lib: Library) {
    val s = LocalStrings.current
    val c = PpTheme.colors
    Column(Modifier.fillMaxSize()) {
        PpTopBar(s.statsTabTitle)
        if (lib.players.isEmpty()) {
            Box(Modifier.padding(16.dp)) { EmptyState(Icons.Rounded.Leaderboard, s.squadEmptyTitle, s.statsNeedSquad, s.goToSquad) { vm.root(Screen.Squad) } }
            return
        }
        val matches = vm.statsFilter.matches(lib)
        val rows = StatsTable.rows(lib, matches, vm.statsSort)
        val sortStat = Stat.fromKey(vm.statsSort)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { StatsFilterBar(vm, lib) }
            item {
                Text(s.sortBy, style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp)) {
                    val keys = listOf("minutes", "goals", "assists", StatsTable.PASS_ACCURACY) + Stat.entries.map { it.key }.filter { it !in setOf("minutes", "goals", "assists") }
                    FilterChips(keys.map { it to (if (it == StatsTable.PASS_ACCURACY) s.passAccuracy else s.statLabel(it)) }, vm.statsSort) { vm.statsSort = it }
                }
            }
            if (matches.isNotEmpty()) {
                items(rows, key = { it.player.id }) { r ->
                    PpCard(onClick = { vm.push(Screen.PlayerStats(r.player.id)) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${r.player.number}", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary, modifier = Modifier.width(48.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.player.alias.ifBlank { s.positionLabel(r.player.position.key) }, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                                Text(s.statsRowLine(r.totals.matches, r.totals.minutes, r.totals.of(Stat.GOALS), r.totals.of(Stat.ASSISTS), r.totals.passAccuracy),
                                    style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                val big = if (sortStat == null) r.totals.passAccuracy?.let { "${Math.round(it * 100)} %" } ?: "–" else "${r.totals.of(sortStat)}"
                                Text(big, style = MaterialTheme.typography.headlineMedium, color = c.textPrimary)
                                if (sortStat != null && sortStat != Stat.MINUTES) {
                                    r.per90(sortStat)?.let { Text("${"%.2f".format(it)} ${s.per90}", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The filter chips and how many matches enter; shared by the tab and a player's page. */
@Composable
fun StatsFilterBar(vm: CoachViewModel, lib: Library) {
    val s = LocalStrings.current
    val c = PpTheme.colors
    var dates by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val f = vm.statsFilter
    val key = when (f) {
        StatsFilter.All -> "all"
        is StatsFilter.Last -> "last${f.n}"
        is StatsFilter.Dates -> "dates"
        is StatsFilter.Picked -> "picked"
    }
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            val options = listOf("all" to s.filterAll) + StatsFilter.LAST.map { "last$it" to s.filterLast(it) } + listOf("dates" to s.filterDates, "picked" to s.filterPicked)
            FilterChips(options, key) { k ->
                when {
                    k == "all" -> vm.statsFilter = StatsFilter.All
                    k.startsWith("last") -> vm.statsFilter = StatsFilter.Last(k.removePrefix("last").toInt())
                    k == "dates" -> dates = true
                    k == "picked" -> picking = true
                }
            }
        }
        val n = f.matches(lib).size
        val detail = (f as? StatsFilter.Dates)?.let { if (it.from == it.to) " · ${it.from}" else " · ${it.from} – ${it.to}" }.orEmpty()
        Text(if (n == 0) s.filterNone else s.filterCount(n) + detail, style = MaterialTheme.typography.bodyMedium,
            color = if (n == 0) c.warning else c.textSecondary, modifier = Modifier.padding(top = 4.dp))
    }
    if (dates) DatesDialog(onDismiss = { dates = false }) { from, to -> vm.statsFilter = StatsFilter.Dates(from, to); dates = false }
    if (picking) {
        PickMatchesDialog(lib, (f as? StatsFilter.Picked)?.ids ?: f.matches(lib).map { it.id }.toSet(), onDismiss = { picking = false }) { ids ->
            vm.statsFilter = StatsFilter.Picked(ids)
            picking = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatesDialog(onDismiss: () -> Unit, onPick: (String, String) -> Unit) {
    val s = LocalStrings.current
    val state = rememberDateRangePickerState()
    fun day(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            val from = day(state.selectedStartDateMillis)
            PpTextButton(s.ok, { if (from != null) onPick(from, day(state.selectedEndDateMillis) ?: from) })
        },
        dismissButton = { PpTextButton(s.cancel, onDismiss) },
    ) {
        DateRangePicker(state, Modifier.height(480.dp), title = { Text(s.filterDatesHelp, Modifier.padding(start = 24.dp, top = 16.dp)) })
    }
}

@Composable
private fun PickMatchesDialog(lib: Library, initial: Set<Int>, onDismiss: () -> Unit, onPick: (Set<Int>) -> Unit) {
    val s = LocalStrings.current
    val c = PpTheme.colors
    var picked by remember { mutableStateOf(initial) }
    val matches = StatsFilter.All.matches(lib)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(s.filterPicked, color = c.textPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row {
                    PpTextButton(s.pickAll, { picked = matches.map { it.id }.toSet() })
                    PpTextButton(s.pickNone, { picked = emptySet() })
                }
                for (m in matches) {
                    Row(Modifier.fillMaxWidth().clickable { picked = if (m.id in picked) picked - m.id else picked + m.id }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(m.id in picked, { on -> picked = if (on) picked + m.id else picked - m.id }, colors = CheckboxDefaults.colors(checkedColor = c.accent))
                        Column {
                            Text("${m.home} ${s.vs} ${m.away}", style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                            Text(m.date, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                        }
                    }
                }
            }
        },
        confirmButton = { PrimaryButton(s.ok, { onPick(picked) }) },
        dismissButton = { PpTextButton(s.cancel, onDismiss) },
    )
}

// ── starting lineup ──────────────────────────────────────────────────────────

/**
 * The starting lineup of a match on a pitch: a formation of 11 or 7 and, in
 * each place, a squad player (those of that line first) or a typed number.
 * Only numbers are saved.
 */
@Composable
fun LineupDialog(vm: CoachViewModel, lib: Library, match: Match, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val c = PpTheme.colors
    val defaultSize = lib.pitch(match.pitchId)?.size ?: 11
    var lineup by remember { mutableStateOf(match.startingLineup ?: StartingLineup.empty(Formation.forSize(defaultSize)[0].key)) }
    var editing by remember { mutableStateOf<Int?>(null) }
    val shape = lineup.shape
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(s.lineupTitle, color = c.textPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChips(listOf("11" to s.football11, "7" to s.football7), shape.size.toString()) { size ->
                    if (size.toInt() != shape.size) lineup = lineup.withFormation(Formation.forSize(size.toInt())[0].key)
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChips(Formation.forSize(shape.size).map { it.key to it.key }, shape.key) { lineup = lineup.withFormation(it) }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(12.dp)).background(c.surfaceHigh)) {
                    val w = maxWidth
                    val h = maxHeight
                    // Halfway line and the own box, to read the pitch.
                    Box(Modifier.offset(y = h * 0.03f).fillMaxWidth().height(1.dp).background(c.textSecondary))
                    Box(Modifier.offset(x = w * 0.25f, y = h * 0.84f).width(w * 0.5f).height(h * 0.16f).border(1.dp, c.textSecondary))
                    for (slot in shape.slots) {
                        val number = lineup.numbers.getOrNull(slot.index)
                        Box(
                            Modifier.offset(x = w * slot.x.toFloat() - 22.dp, y = h * slot.y.toFloat() - 22.dp).size(44.dp).clip(CircleShape)
                                .background(if (number != null) c.accent else c.surface).border(1.dp, if (number != null) c.accent else c.divider, CircleShape)
                                .clickable { editing = slot.index },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(number?.toString() ?: s.positionShort(slot.line.key), style = MaterialTheme.typography.labelLarge,
                                color = if (number != null) c.onAccent else c.textSecondary)
                        }
                    }
                }
                PpTextButton(s.fillWithSquad, { lineup = lineup.fillWith(lib.players) })
            }
        },
        confirmButton = { PrimaryButton(s.save, { vm.saveLineup(match.id, lineup); onDismiss() }) },
        dismissButton = { PpTextButton(s.cancel, onDismiss) },
    )
    editing?.let { index ->
        val slot = shape.slots[index]
        SlotDialog(lib.players, slot.line.key, lineup.numbers[index], s, onDismiss = { editing = null }) { n ->
            lineup = lineup.assign(index, n)
            editing = null
        }
    }
}

/** Who plays in one place: the squad (its line first), a typed number, or nobody. */
@Composable
private fun SlotDialog(players: List<Player>, line: String, current: Int?, s: Strings, onDismiss: () -> Unit, onPick: (Int?) -> Unit) {
    val c = PpTheme.colors
    var typed by remember { mutableStateOf(current?.toString().orEmpty()) }
    val ordered = players.sortedWith(compareBy<Player> { it.position.key != line }.thenBy { it.number })
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(s.positionLabel(line), color = c.textPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (p in ordered) {
                    Text("${playerLabel(p)} · ${s.positionLabel(p.position.key)}", style = MaterialTheme.typography.bodyLarge,
                        color = if (p.number == current) c.accentText else c.textPrimary,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(p.number) }.padding(vertical = 10.dp))
                }
                OutlinedTextField(typed, { typed = it.filter(Char::isDigit).take(2) }, label = { Text(s.typeNumber) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                PpTextButton(s.emptySlot, { onPick(null) })
            }
        },
        confirmButton = { PrimaryButton(s.ok, { onPick(typed.toIntOrNull()?.takeIf { it in Player.NUMBERS }) }) },
        dismissButton = { PpTextButton(s.cancel, onDismiss) },
    )
}
