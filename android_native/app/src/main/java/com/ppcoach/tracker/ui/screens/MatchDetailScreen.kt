package com.ppcoach.tracker.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Leaderboard
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ppcoach.tracker.data.Library
import com.ppcoach.tracker.data.Match
import com.ppcoach.tracker.ui.CoachViewModel
import com.ppcoach.tracker.ui.Screen
import com.ppcoach.tracker.ui.components.ClipRow
import com.ppcoach.tracker.ui.components.EmptyState
import com.ppcoach.tracker.ui.components.FilterChips
import com.ppcoach.tracker.ui.components.PpTextButton
import com.ppcoach.tracker.ui.components.PpTopBar
import com.ppcoach.tracker.ui.components.PrimaryButton
import com.ppcoach.tracker.ui.components.SecondaryButton
import com.ppcoach.tracker.ui.i18n.LocalStrings
import com.ppcoach.tracker.ui.theme.PpTheme

@Composable
fun MatchDetailScreen(vm: CoachViewModel, lib: Library, matchId: Int, onEdit: (Match) -> Unit, onImport: () -> Unit) {
    val s = LocalStrings.current
    val c = PpTheme.colors
    val match = lib.match(matchId) ?: return
    var filter by remember { mutableStateOf("all") }
    var confirmDelete by remember { mutableStateOf(false) }
    val clips = lib.clipsOf(matchId).filter {
        when (filter) {
            "all" -> true
            "starred" -> it.starred
            else -> it.kind.key == filter
        }
    }
    Column(Modifier.fillMaxSize()) {
        PpTopBar("${match.home} ${s.vs} ${match.away}", onBack = { vm.back() }) {
            IconButton(onClick = { vm.push(Screen.MatchStats(matchId)) }) { Icon(Icons.Rounded.Leaderboard, s.matchStats, tint = c.textPrimary) }
            IconButton(onClick = { onEdit(match) }) { Icon(Icons.Rounded.Edit, s.editMatch, tint = c.textPrimary) }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, s.deleteMatch, tint = c.textPrimary) }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(listOf(match.date, match.venue).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(s.recordPlay, { vm.push(Screen.Record(matchId)) }, Modifier.weight(1f), icon = Icons.Rounded.Videocam)
                    SecondaryButton(s.importVideo, onImport, Modifier.weight(1f), icon = Icons.Rounded.PhotoLibrary)
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChips(
                        listOf("all" to s.allClips, "starred" to s.starred) + listOf("play", "half", "full", "training").map { it to s.kindLabel(it) },
                        filter,
                    ) { filter = it }
                }
            }
            if (clips.isEmpty()) {
                item { EmptyState(Icons.Rounded.Videocam, s.noClips, s.noClipsHelp) }
            }
            items(clips, key = { it.id }) { clip ->
                LaunchedEffect(clip.id) { vm.loadThumbnail(clip) }
                ClipRow(clip.title, "${s.kindLabel(clip.kind.key)} · ${formatDuration(clip.durationMs)}", clip.status, clip.starred, vm.thumbnails[clip.id]) {
                    vm.push(Screen.ClipDetail(clip.id))
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = c.surface,
            title = { Text(s.deleteMatch, color = c.textPrimary) },
            text = { Text(s.confirmDelete, color = c.textSecondary) },
            confirmButton = { PpTextButton(s.delete, { confirmDelete = false; vm.deleteMatch(matchId) }, color = c.negative) },
            dismissButton = { PpTextButton(s.cancel, { confirmDelete = false }) },
        )
    }
}
