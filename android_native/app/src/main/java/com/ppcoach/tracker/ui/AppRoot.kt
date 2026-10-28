package com.ppcoach.tracker.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddBox
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ppcoach.tracker.data.ClipSource
import com.ppcoach.tracker.data.Match
import com.ppcoach.tracker.ui.components.BottomItem
import com.ppcoach.tracker.ui.components.ConsentDialog
import com.ppcoach.tracker.ui.components.PpBottomBar
import com.ppcoach.tracker.ui.components.SectionTitle
import com.ppcoach.tracker.ui.components.SheetOption
import com.ppcoach.tracker.ui.i18n.LocalStrings
import com.ppcoach.tracker.ui.screens.ClipAnalysisScreen
import com.ppcoach.tracker.ui.screens.ClipDetailScreen
import com.ppcoach.tracker.ui.screens.MatchDetailScreen
import com.ppcoach.tracker.ui.screens.MatchEditor
import com.ppcoach.tracker.ui.screens.MatchesScreen
import com.ppcoach.tracker.ui.screens.RecordScreen
import com.ppcoach.tracker.ui.screens.SettingsScreen
import com.ppcoach.tracker.ui.theme.PpTheme
import kotlinx.coroutines.delay

private enum class CreateFlow { NONE, SHEET, PICK_FOR_RECORD, PICK_FOR_IMPORT }

/** Draws the screen on top of the stack, the bottom bar on root screens and the Create sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: CoachViewModel, onExit: () -> Unit) {
    val s = LocalStrings.current
    val c = PpTheme.colors
    val lib by vm.library.collectAsState()
    var create by remember { mutableStateOf(CreateFlow.NONE) }
    var editing by remember { mutableStateOf<Match?>(null) }
    var importFor by remember { mutableStateOf<Int?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val m = importFor
        if (uri != null && m != null) vm.importVideo(m, uri)
        importFor = null
    }
    fun startImport(matchId: Int) {
        importFor = matchId
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }

    BackHandler { if (!vm.back()) onExit() }

    val top = vm.top
    val isRoot = top == Screen.Matches || top == Screen.Settings
    Column(Modifier.fillMaxSize().background(c.background)) {
        Box(Modifier.weight(1f)) {
            when (top) {
                Screen.Matches -> MatchesScreen(vm, lib, onEdit = { editing = it })
                Screen.Settings -> SettingsScreen(vm)
                is Screen.MatchDetail -> MatchDetailScreen(vm, lib, top.matchId, onEdit = { editing = it }, onImport = { startImport(top.matchId) })
                is Screen.Record -> RecordScreen(vm, top.matchId)
                is Screen.ClipDetail -> ClipDetailScreen(vm, lib, top.clipId, top.seekMs)
                is Screen.Analysis -> ClipAnalysisScreen(vm, lib, top.clipId)
            }
        }
        if (isRoot) {
            PpBottomBar(
                items = listOf(
                    BottomItem("matches", s.navMatches, Icons.Outlined.Home, Icons.Rounded.Home),
                    BottomItem("create", s.navCreate, Icons.Outlined.AddBox, Icons.Rounded.AddBox),
                    BottomItem("settings", s.navSettings, Icons.Outlined.Settings, Icons.Rounded.Settings),
                ),
                selected = if (top == Screen.Settings) "settings" else "matches",
                onSelect = {
                    when (it) {
                        "matches" -> vm.root(Screen.Matches)
                        "settings" -> vm.root(Screen.Settings)
                        "create" -> create = CreateFlow.SHEET
                    }
                },
            )
        }
    }

    if (create != CreateFlow.NONE) {
        ModalBottomSheet(onDismissRequest = { create = CreateFlow.NONE }, containerColor = c.surface) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                if (create == CreateFlow.SHEET) {
                    SheetOption(Icons.Rounded.Videocam, s.recordPlay) { create = CreateFlow.PICK_FOR_RECORD }
                    SheetOption(Icons.Rounded.PhotoLibrary, s.importVideo) { create = CreateFlow.PICK_FOR_IMPORT }
                    SheetOption(Icons.Rounded.Add, s.newMatch) {
                        create = CreateFlow.NONE
                        editing = vm.newMatchTemplate()
                    }
                } else {
                    // Which match: recent first, plus "New match".
                    Box(Modifier.padding(horizontal = 16.dp)) { SectionTitle(s.chooseMatch) }
                    val forRecord = create == CreateFlow.PICK_FOR_RECORD
                    for (m in lib.matches.sortedByDescending { it.createdAt }.take(6)) {
                        SheetOption(if (forRecord) Icons.Rounded.Videocam else Icons.Rounded.PhotoLibrary, "${m.home} ${s.vs} ${m.away} · ${m.date}") {
                            create = CreateFlow.NONE
                            if (forRecord) vm.push(Screen.Record(m.id)) else startImport(m.id)
                        }
                    }
                    SheetOption(Icons.Rounded.Add, s.newMatch) {
                        create = CreateFlow.NONE
                        editing = vm.newMatchTemplate()
                    }
                }
            }
        }
    }

    editing?.let { m ->
        MatchEditor(
            initial = m,
            isNew = m.id == 0,
            onDismiss = { editing = null },
            onSave = { edited ->
                val saved = vm.saveMatch(if (m.id == 0) null else m, edited)
                editing = null
                if (m.id == 0) vm.push(Screen.MatchDetail(saved.id))
            },
        )
    }

    vm.consentFor?.let { ConsentDialog(vm, onDismiss = { vm.consentFor = null }) }

    vm.message?.let { text ->
        LaunchedEffect(text) {
            delay(2500)
            vm.message = null
        }
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = if (isRoot) 76.dp else 16.dp, start = 16.dp, end = 16.dp), contentAlignment = Alignment.BottomCenter) {
            Snackbar(containerColor = c.textPrimary, contentColor = c.surface) { Text(text) }
        }
    }
}
