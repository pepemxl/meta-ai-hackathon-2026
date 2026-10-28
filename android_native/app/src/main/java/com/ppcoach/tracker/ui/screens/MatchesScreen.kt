package com.ppcoach.tracker.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ppcoach.tracker.data.JerseyColor
import com.ppcoach.tracker.data.Library
import com.ppcoach.tracker.data.Match
import com.ppcoach.tracker.ui.CoachViewModel
import com.ppcoach.tracker.ui.Screen
import com.ppcoach.tracker.ui.components.ClipCarousel
import com.ppcoach.tracker.ui.components.EmptyState
import com.ppcoach.tracker.ui.components.MatchCard
import com.ppcoach.tracker.ui.components.PpTextButton
import com.ppcoach.tracker.ui.components.PpTopBar
import com.ppcoach.tracker.ui.components.PrimaryButton
import com.ppcoach.tracker.ui.components.TeamDot
import com.ppcoach.tracker.ui.i18n.LocalStrings
import com.ppcoach.tracker.ui.theme.PpTheme

@Composable
fun MatchesScreen(vm: CoachViewModel, lib: Library, onEdit: (Match) -> Unit) {
    val s = LocalStrings.current
    val c = PpTheme.colors
    Column(Modifier.fillMaxSize()) {
        PpTopBar(s.appName) {
            IconButton(onClick = { onEdit(vm.newMatchTemplate()) }) { Icon(Icons.Rounded.Add, s.newMatch, tint = c.textPrimary) }
        }
        if (lib.matches.isEmpty()) {
            EmptyState(Icons.Rounded.SportsSoccer, s.matchesEmptyTitle, s.matchesEmptyHelp, s.newMatch) { onEdit(vm.newMatchTemplate()) }
            return
        }
        val recent = (lib.clips.filter { it.starred } + lib.clips.sortedByDescending { it.createdAt }).distinctBy { it.id }.take(10)
        LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (recent.isNotEmpty()) {
                item {
                    LaunchedEffect(recent.map { it.id }) { recent.forEach { vm.loadThumbnail(it) } }
                    ClipCarousel(recent.map { Triple(it.title, it.status, vm.thumbnails[it.id]) }) { i -> vm.push(Screen.ClipDetail(recent[i].id)) }
                }
            }
            items(lib.matches.sortedByDescending { it.date + it.createdAt }, key = { it.id }) { m ->
                val clips = lib.clipsOf(m.id)
                LaunchedEffect(clips.firstOrNull()?.id) { clips.firstOrNull()?.let { vm.loadThumbnail(it) } }
                val total = clips.sumOf { it.durationMs }
                val meta = listOf(m.date, m.venue, s.clipsCount(clips.size) + if (total > 0) " · ${formatDuration(total)}" else "").filter { it.isNotBlank() }.joinToString(" · ")
                Column(Modifier.padding(horizontal = 16.dp)) {
                    MatchCard(m.home, m.away, m.homeColor, m.awayColor, meta, clips.firstOrNull()?.let { vm.thumbnails[it.id] }) { vm.push(Screen.MatchDetail(m.id)) }
                }
            }
        }
    }
}

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun formatMb(bytes: Long): String = "%.1f".format(bytes / 1_048_576.0)

@Composable
fun MatchEditor(initial: Match, isNew: Boolean, onDismiss: () -> Unit, onSave: (Match) -> Unit) {
    val s = LocalStrings.current
    val c = PpTheme.colors
    var home by remember { mutableStateOf(initial.home) }
    var away by remember { mutableStateOf(initial.away) }
    var homeColor by remember { mutableStateOf(initial.homeColor) }
    var awayColor by remember { mutableStateOf(initial.awayColor) }
    var date by remember { mutableStateOf(initial.date) }
    var venue by remember { mutableStateOf(initial.venue) }
    var notes by remember { mutableStateOf(initial.notes) }
    val valid = home.isNotBlank() && away.isNotBlank() && Regex("""\d{4}-\d{2}-\d{2}""").matches(date)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(if (isNew) s.newMatch else s.editMatch, color = c.textPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(home, { home = it.take(80) }, label = { Text(s.home) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ColorPicker(s.homeColor, homeColor) { homeColor = it }
                OutlinedTextField(away, { away = it.take(80) }, label = { Text(s.away) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ColorPicker(s.awayColor, awayColor) { awayColor = it }
                OutlinedTextField(date, { date = it.take(10) }, label = { Text(s.date) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(venue, { venue = it.take(80) }, label = { Text(s.venue) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(notes, { notes = it.take(500) }, label = { Text(s.notes) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            PrimaryButton(s.save, { onSave(initial.copy(home = home.trim(), away = away.trim(), homeColor = homeColor, awayColor = awayColor, date = date, venue = venue.trim(), notes = notes.trim())) }, enabled = valid)
        },
        dismissButton = { PpTextButton(s.cancel, onDismiss) },
    )
}

@Composable
private fun ColorPicker(label: String, selected: JerseyColor, onSelect: (JerseyColor) -> Unit) {
    val c = PpTheme.colors
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
        Spacer(Modifier.height(4.dp))
        for (row in JerseyColor.entries.chunked(5)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                for (jc in row) {
                    IconButton(onClick = { onSelect(jc) }) {
                        if (jc == selected) {
                            androidx.compose.foundation.layout.Box(
                                Modifier.border(2.dp, c.accent, CircleShape).padding(3.dp),
                                contentAlignment = Alignment.Center,
                            ) { TeamDot(jc, 22.dp) }
                        } else {
                            TeamDot(jc, 22.dp)
                        }
                    }
                }
            }
        }
    }
}
