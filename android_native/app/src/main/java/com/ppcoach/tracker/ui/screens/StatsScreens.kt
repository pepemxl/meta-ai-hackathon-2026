package com.ppcoach.tracker.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ppcoach.tracker.data.ClipAnalysis
import com.ppcoach.tracker.data.Library
import com.ppcoach.tracker.data.Match
import com.ppcoach.tracker.data.Player
import com.ppcoach.tracker.data.PlayerMatchStats
import com.ppcoach.tracker.data.Position
import com.ppcoach.tracker.data.Stat
import com.ppcoach.tracker.data.StatsCalc
import com.ppcoach.tracker.ui.CoachViewModel
import com.ppcoach.tracker.ui.Screen
import com.ppcoach.tracker.ui.components.EmptyState
import com.ppcoach.tracker.ui.components.FilterChips
import com.ppcoach.tracker.ui.components.KeyValue
import com.ppcoach.tracker.ui.components.PpCard
import com.ppcoach.tracker.ui.components.PpTextButton
import com.ppcoach.tracker.ui.components.PpTopBar
import com.ppcoach.tracker.ui.components.PrimaryButton
import com.ppcoach.tracker.ui.components.SecondaryButton
import com.ppcoach.tracker.ui.components.SectionTitle
import com.ppcoach.tracker.ui.i18n.LocalStrings
import com.ppcoach.tracker.ui.i18n.Strings
import com.ppcoach.tracker.ui.theme.PpTheme

/** "#8 Cami" or "#8". The alias never leaves the phone. */
fun playerLabel(p: Player): String = "#${p.number}" + if (p.alias.isNotBlank()) " ${p.alias}" else ""

private fun pct(v: Double?): String = v?.let { "${Math.round(it * 100)} %" } ?: "–"

// ── squad ────────────────────────────────────────────────────────────────────

/** The squad: number, position and alias; tap a player for their stats. A root screen. */
@Composable
fun SquadScreen(vm: CoachViewModel, lib: Library) {
    val s = LocalStrings.current
    val c = PpTheme.colors
    var editing by remember { mutableStateOf<Player?>(null) }
    Column(Modifier.fillMaxSize()) {
        PpTopBar(s.squadTitle) {
            IconButton(onClick = { editing = Player(0, nextFreeNumber(lib)) }) { Icon(Icons.Rounded.Add, s.addPlayer, tint = c.textPrimary) }
        }
        if (lib.players.isEmpty()) {
            Box(Modifier.padding(16.dp)) { EmptyState(Icons.Rounded.Groups, s.squadEmptyTitle, s.squadEmptyHelp, s.addPlayer) { editing = Player(0, 1) } }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(lib.playersByNumber, key = { it.id }) { p ->
                    val t = StatsCalc.totals(lib.sheetsOfPlayer(p.id))
                    PpCard(onClick = { vm.push(Screen.PlayerStats(p.id)) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${p.number}", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary, modifier = Modifier.width(48.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.alias.ifBlank { s.positionLabel(p.position.key) }, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                                // Without an alias the position is the title: not twice.
                                val position = if (p.alias.isBlank()) "" else "${s.positionLabel(p.position.key)} · "
                                Text("$position${s.matchesPlayed}: ${t.matches} · ${s.statLabel("goals")}: ${t.of(Stat.GOALS)}",
                                    style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                            }
                            PpTextButton(s.editPlayer, { editing = p })
                        }
                    }
                }
            }
        }
    }
    editing?.let { p ->
        PlayerEditorDialog(
            initial = p,
            onDismiss = { editing = null },
            onSave = { edited -> if (vm.savePlayer(edited)) editing = null },
            onDelete = if (p.id > 0) ({ vm.deletePlayer(p.id); editing = null }) else null,
        )
    }
}

private fun nextFreeNumber(lib: Library): Int = Player.NUMBERS.firstOrNull { lib.playerByNumber(it) == null } ?: 1

@Composable
fun PlayerEditorDialog(initial: Player, onDismiss: () -> Unit, onSave: (Player) -> Unit, onDelete: (() -> Unit)?) {
    val s = LocalStrings.current
    val c = PpTheme.colors
    var number by remember { mutableStateOf(initial.number.toString()) }
    var position by remember { mutableStateOf(initial.position) }
    var alias by remember { mutableStateOf(initial.alias) }
    val n = number.trim().toIntOrNull()
    val valid = n != null && n in Player.NUMBERS
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(if (initial.id == 0) s.addPlayer else s.editPlayer, color = c.textPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(number, { number = it.filter(Char::isDigit).take(2) }, label = { Text(s.playerNumber) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = !valid, modifier = Modifier.fillMaxWidth())
                if (!valid) Text(s.numberInvalid, style = MaterialTheme.typography.bodyMedium, color = c.negative)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChips(Position.entries.map { it.key to s.positionLabel(it.key) }, position.key) { position = Position.fromKey(it) }
                }
                OutlinedTextField(alias, { alias = it.take(Player.MAX_ALIAS) }, label = { Text(s.playerAlias) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(s.playerAliasHelp, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                if (onDelete != null) PpTextButton(s.deletePlayer, onDelete, color = c.negative)
            }
        },
        confirmButton = { PrimaryButton(s.save, { onSave(initial.copy(number = n ?: 0, position = position, alias = alias)) }, enabled = valid) },
        dismissButton = { PpTextButton(s.cancel, onDismiss) },
    )
}

// ── one player ───────────────────────────────────────────────────────────────

/** A player's totals over every match (per 90 and pass accuracy), then match by match. */
@Composable
fun PlayerStatsScreen(vm: CoachViewModel, lib: Library, playerId: Int) {
    val s = LocalStrings.current
    val c = PpTheme.colors
    val p = lib.player(playerId) ?: return
    val inFilter = vm.statsFilter.matches(lib).map { it.id }.toSet()
    val sheets = lib.sheetsOfPlayer(playerId).filter { it.matchId in inFilter }.sortedByDescending { lib.match(it.matchId)!!.date }
    val t = StatsCalc.totals(sheets)
    Column(Modifier.fillMaxSize()) {
        PpTopBar(playerLabel(p), onBack = { vm.back() })
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(s.positionLabel(p.position.key), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary) }
            item { StatsFilterBar(vm, lib) }
            if (sheets.isEmpty()) {
                item { Text(s.noStatsYet, style = MaterialTheme.typography.bodyLarge, color = c.textSecondary) }
            } else {
                val video = lib.videoOf(p.number, sheets.map { it.matchId }.toSet())
                if (!video.isEmpty) item { VideoCard(video, s) }
                item {
                    PpCard {
                        SectionTitle(s.totals)
                        KeyValue(s.matchesPlayed, "${t.matches}")
                        KeyValue(s.statLabel("minutes"), "${t.minutes}")
                        KeyValue(s.passAccuracy, pct(t.passAccuracy))
                        for (st in Stat.entries.filter { it != Stat.MINUTES && t.of(it) > 0 }) {
                            val p90 = StatsCalc.per90(sheets, st)
                            KeyValue(s.statLabel(st.key), "${t.of(st)}" + (p90?.let { " · ${"%.2f".format(it)} ${s.per90}" } ?: ""))
                        }
                    }
                }
                item { SectionTitle(s.byMatch) }
                items(sheets, key = { it.matchId }) { sheet ->
                    val m = lib.match(sheet.matchId)!!
                    PpCard(onClick = { vm.push(Screen.MatchStats(m.id)) }) {
                        Text("${m.home} ${s.vs} ${m.away}", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                        Text(m.date, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                        Text(Stat.entries.filter { sheet.value(it) != 0 }.joinToString(" · ") { "${s.statLabel(it.key)} ${sheet.value(it)}" },
                            style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                    }
                }
            }
        }
    }
}

// ── one match ────────────────────────────────────────────────────────────────

/** A match's sheet: one card per player; tap to edit. Import a report or add squad players. */
@Composable
fun MatchStatsScreen(vm: CoachViewModel, lib: Library, matchId: Int) {
    val s = LocalStrings.current
    val c = PpTheme.colors
    val match = lib.match(matchId) ?: return
    val sheets = lib.sheetsOf(matchId).mapNotNull { sh -> lib.player(sh.playerId)?.let { it to sh } }.sortedBy { it.first.number }
    var editing by remember { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf(false) }
    var lineup by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importReport(matchId, uri) }
    Column(Modifier.fillMaxSize()) {
        PpTopBar("${s.matchStats} · ${match.home} ${s.vs} ${match.away}", onBack = { vm.back() }) {
            IconButton(onClick = { lineup = true }) { Icon(Icons.Rounded.SportsSoccer, s.lineupTitle, tint = c.textPrimary) }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                val report = match.analysis
                Text(report?.let { s.reportFrom(it.source, it.via) } ?: "${s.noReportYet}. ${s.statsLocked}",
                    style = MaterialTheme.typography.bodyMedium, color = if (report == null) c.warning else c.textSecondary)
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        PrimaryButton(s.addToMatch, { adding = true }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Add, enabled = lib.players.isNotEmpty())
                        DropdownMenu(adding, { adding = false }) {
                            for (p in lib.playersByNumber.filter { lib.sheet(matchId, it.id) == null }) {
                                DropdownMenuItem(text = { Text(playerLabel(p)) }, onClick = { vm.addToMatch(matchId, p.id) })
                            }
                        }
                    }
                    SecondaryButton(s.importReport, { picker.launch(arrayOf("application/json", "text/plain", "*/*")) }, Modifier.weight(1f), icon = Icons.Rounded.FileOpen)
                }
            }
            match.startingLineup?.let { l ->
                item {
                    PpCard(onClick = { lineup = true }) {
                        Text("${s.lineupTitle} · ${l.formation}", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                        Text(l.numbers.filterNotNull().joinToString(" · ") { "#$it" }.ifEmpty { "–" }, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                    }
                }
            }
            if (sheets.isEmpty()) item { Text(s.matchStatsEmpty, style = MaterialTheme.typography.bodyLarge, color = c.textSecondary) }
            items(sheets, key = { it.first.id }) { (p, sheet) ->
                PpCard(onClick = { editing = p.id }) {
                    Text(playerLabel(p) + " · " + s.positionLabel(p.position.key), style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                    Text(sheetLine(sheet, match, s), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                    val video = lib.videoOf(p.number, setOf(matchId))
                    if (video.meters != null || video.topSpeed != null) {
                        Text("${s.fromVideo}: ${s.videoLine(video.meters, video.topSpeedKmh)}", style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                    }
                }
            }
            if (sheets.isNotEmpty()) {
                item {
                    val team = StatsCalc.teamTotals(sheets.map { it.second })
                    PpCard {
                        SectionTitle(s.totals)
                        for (st in Stat.entries.filter { (team[it] ?: 0) > 0 }) KeyValue(s.statLabel(st.key), "${team[st]}")
                    }
                }
            }
        }
    }
    if (lineup) LineupDialog(vm, lib, match, onDismiss = { lineup = false })
    editing?.let { pid ->
        val p = lib.player(pid)
        val sheet = lib.sheet(matchId, pid)
        if (p != null && sheet != null) StatEditorDialog(vm, match, p, sheet, onDismiss = { editing = null })
    }
}

/** A player's time, meters and top speed from their tagged tracks. */
@Composable
private fun VideoCard(video: com.ppcoach.tracker.data.PlayerVideo, s: Strings) {
    val c = PpTheme.colors
    PpCard {
        SectionTitle(s.fromVideo)
        KeyValue(s.timeOnScreen, s.timeSeen(video.seconds))
        if (video.meters != null) {
            KeyValue(s.distanceRun, s.distance(video.meters))
            video.metersPerMinute?.let { KeyValue(s.playerMetersPerMinute, "${Math.round(it)}") }
        }
        video.topSpeedKmh?.let { KeyValue(s.topSpeed, "${"%.1f".format(it)} km/h") }
        if (video.meters == null) Text(s.videoNoPitch, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
    }
}

private fun sheetLine(sheet: PlayerMatchStats, match: Match, s: Strings): String {
    val shown = Stat.entries.filter { sheet.value(it) != 0 }
    if (shown.isEmpty()) return if (match.analysis == null) s.statsLocked else "–"
    return shown.joinToString(" · ") { "${s.statLabel(it.key)} ${sheet.value(it)}" + if (sheet.edited(it)) "*" else "" }
}

/** Every stat of one player in one match: locked (🔒) until a report, edited values with the analysis next to them. */
@Composable
fun StatEditorDialog(vm: CoachViewModel, match: Match, player: Player, sheet: PlayerMatchStats, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val c = PpTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("${playerLabel(player)} · ${match.date}", color = c.textPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (st in Stat.entries) {
                    val locked = StatsCalc.locked(st, match)
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text((if (locked) "🔒 " else "") + s.statLabel(st.key), style = MaterialTheme.typography.bodyLarge, color = if (locked) c.textSecondary else c.textPrimary)
                            if (sheet.edited(st)) {
                                Text("${s.editedFrom(sheet.analysis[st] ?: 0)} · ${s.backToAnalysis}", style = MaterialTheme.typography.bodyMedium, color = c.warning,
                                    modifier = Modifier.clickable { vm.setStat(match.id, player.id, st, null) })
                            }
                        }
                        Stepper(sheet.value(st), enabled = !locked, onChange = { vm.setStat(match.id, player.id, st, it) })
                    }
                    HorizontalDivider(color = c.divider)
                }
                PpTextButton(s.removeFromMatch, { vm.removeFromMatch(match.id, player.id); onDismiss() }, color = c.negative)
            }
        },
        confirmButton = { PrimaryButton(s.ok, onDismiss) },
    )
}

@Composable
private fun Stepper(value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    val c = PpTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        PpTextButton("−", { onChange(maxOf(0, value - 1)) }, color = if (enabled && value > 0) c.accentText else c.textSecondary)
        Text("$value", style = MaterialTheme.typography.titleMedium, color = if (enabled) c.textPrimary else c.textSecondary, modifier = Modifier.width(32.dp))
        PpTextButton("+", { if (enabled) onChange(value + 1) }, color = if (enabled) c.accentText else c.textSecondary)
    }
}

// ── tags on the analysis ─────────────────────────────────────────────────────

/** In the analysis: each player track with a squad number; "Send the minutes to the match". */
@Composable
fun TrackTagsCard(vm: CoachViewModel, lib: Library, clipId: Int, analysis: ClipAnalysis) {
    val s = LocalStrings.current
    val c = PpTheme.colors
    val clip = lib.clip(clipId) ?: return
    var team by remember { mutableStateOf("A") }
    PpCard {
        SectionTitle(s.trackTagsTitle)
        Text(s.trackTagsHelp, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
        if (lib.players.isEmpty()) {
            Text(s.tagsNeedSquad, style = MaterialTheme.typography.bodyMedium, color = c.warning)
            PpTextButton(s.navSquad, { vm.root(Screen.Squad) })
            return@PpCard
        }
        Row(Modifier.padding(vertical = 4.dp)) {
            FilterChips(listOf("A" to analysis.home.ifEmpty { "A" }, "B" to analysis.away.ifEmpty { "B" }), team) { team = it }
        }
        for (t in analysis.tracks.filter { it.team == team }.take(40)) {
            var open by remember(t.id) { mutableStateOf(false) }
            val number = clip.trackTags[t.id]
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Track ${t.id}", style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                    Text(s.trackLine(t.seconds.toInt(), t.distanceM), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                }
                Box {
                    PpTextButton(number?.let { n -> lib.playerByNumber(n)?.let(::playerLabel) ?: "#$n" } ?: s.noTag, { open = true })
                    DropdownMenu(open, { open = false }) {
                        DropdownMenuItem(text = { Text(s.noTag) }, onClick = { open = false; vm.tagTrack(clipId, t.id, null) })
                        for (p in lib.playersByNumber) DropdownMenuItem(text = { Text(playerLabel(p)) }, onClick = { open = false; vm.tagTrack(clipId, t.id, p.number) })
                    }
                }
            }
        }
        PrimaryButton(s.tagsToMatch, { vm.tagsToMatch(clipId, analysis) }, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = clip.trackTags.isNotEmpty())
    }
}
