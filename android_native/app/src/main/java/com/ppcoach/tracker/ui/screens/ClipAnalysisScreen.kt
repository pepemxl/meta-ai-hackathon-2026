package com.ppcoach.tracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ppcoach.tracker.data.JerseyColor
import com.ppcoach.tracker.data.Library
import com.ppcoach.tracker.ui.AnalysisState
import com.ppcoach.tracker.ui.CoachViewModel
import com.ppcoach.tracker.ui.Screen
import com.ppcoach.tracker.ui.components.Heatmap
import com.ppcoach.tracker.ui.components.InsightCard
import com.ppcoach.tracker.ui.components.KeyValue
import com.ppcoach.tracker.ui.components.PossessionBar
import com.ppcoach.tracker.ui.components.PpCard
import com.ppcoach.tracker.ui.components.PpProgress
import com.ppcoach.tracker.ui.components.PpTopBar
import com.ppcoach.tracker.ui.components.SectionTitle
import com.ppcoach.tracker.ui.components.SummaryCard
import com.ppcoach.tracker.ui.components.TeamDot
import com.ppcoach.tracker.ui.i18n.LocalStrings
import com.ppcoach.tracker.ui.theme.PpTheme

@Composable
fun ClipAnalysisScreen(vm: CoachViewModel, lib: Library, clipId: Int) {
    val s = LocalStrings.current
    val c = PpTheme.colors
    val clip = lib.clip(clipId) ?: return
    val match = lib.match(clip.matchId) ?: return
    LaunchedEffect(clipId) { vm.loadAnalysis(clipId) }
    Column(Modifier.fillMaxSize()) {
        PpTopBar(s.analysisTitle, onBack = { vm.back() })
        when (val state = vm.analyses[clipId]) {
            null, AnalysisState.Loading -> Column(Modifier.padding(16.dp)) { PpProgress(null, spark = true) }
            is AnalysisState.Missing -> Text(state.reason, style = MaterialTheme.typography.bodyLarge, color = c.textSecondary, modifier = Modifier.padding(16.dp))
            is AnalysisState.Loaded -> {
                val a = state.analysis
                val homeColor = JerseyColor.fromKey(a.homeColor.ifEmpty { match.homeColor.key }, match.homeColor)
                val awayColor = JerseyColor.fromKey(a.awayColor.ifEmpty { match.awayColor.key }, match.awayColor)
                val home = a.home.ifEmpty { match.home }
                val away = a.away.ifEmpty { match.away }
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (a.isSimulator) {
                        item {
                            Text(
                                s.simulatorWarning,
                                style = MaterialTheme.typography.labelLarge,
                                color = c.warning,
                                modifier = Modifier.fillMaxWidth().background(c.warningSoft, RoundedCornerShape(8.dp)).padding(12.dp),
                            )
                        }
                    }
                    item { SummaryCard(a.summary, if (a.isSimulator) s.simulatorWarning else s.analyzedWith) }
                    item {
                        PpCard {
                            SectionTitle(s.possession)
                            TeamsHeader(home, away, homeColor, awayColor)
                            Spacer(Modifier.height(4.dp))
                            val pa = a.teams["A"]?.possession
                            if (pa != null) PossessionBar(pa, homeColor, awayColor)
                            Spacer(Modifier.height(8.dp))
                            KeyValue(s.ballSeen, "${Math.round(a.ballSeenRatio * 100)} %")
                            Text(s.possessionChanges(a.possessionChanges), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                        }
                    }
                    item { SectionTitle(s.findings) }
                    if (a.insights.isEmpty()) item { Text(s.noFindings, style = MaterialTheme.typography.bodyLarge, color = c.textSecondary) }
                    items(a.insights) { ins ->
                        InsightCard(ins, formatDuration(ins.startMs)) { vm.push(Screen.ClipDetail(clipId, ins.startMs)) }
                    }
                    item {
                        PpCard {
                            SectionTitle(s.teamsSideBySide)
                            TeamsHeader(home, away, homeColor, awayColor)
                            val ta = a.teams["A"]
                            val tb = a.teams["B"]
                            SideBySide(s.playersVisible, ta?.playersPerFrame?.let { "%.1f".format(it) }, tb?.playersPerFrame?.let { "%.1f".format(it) })
                            SideBySide(s.longestPossession, ta?.longestPossessionMs?.let { formatDuration(it) }, tb?.longestPossessionMs?.let { formatDuration(it) })
                            SideBySide(s.pressure, ta?.pressersPerFrame?.let { "%.1f".format(it) }, tb?.pressersPerFrame?.let { "%.1f".format(it) })
                            SideBySide(s.widthInImage, ta?.spreadX?.let { "${Math.round(it * 100)} %" }, tb?.spreadX?.let { "${Math.round(it * 100)} %" })
                            SideBySide(s.depthInImage, ta?.spreadY?.let { "${Math.round(it * 100)} %" }, tb?.spreadY?.let { "${Math.round(it * 100)} %" })
                        }
                    }
                    val heat = listOf(Triple(home, homeColor, a.teams["A"]?.heatmap), Triple(away, awayColor, a.teams["B"]?.heatmap)).filter { it.third != null }
                    if (heat.isNotEmpty()) {
                        item {
                            PpCard {
                                SectionTitle(s.heatmapTitle)
                                Text(s.heatmapHelp, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                                for ((name, color, values) in heat) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                                        TeamDot(color)
                                        Spacer(Modifier.width(6.dp))
                                        Text(name, style = MaterialTheme.typography.labelLarge, color = c.textPrimary)
                                    }
                                    Heatmap(values!!, color)
                                }
                            }
                        }
                    }
                    if (a.limitations.isNotEmpty() || a.notes.isNotEmpty()) {
                        item {
                            PpCard {
                                SectionTitle(s.limitations)
                                for (line in a.notes + a.limitations) Text("• $line", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                            }
                        }
                    }
                    if (vm.settings.technical) {
                        item {
                            PpCard {
                                SectionTitle(s.modelsUsed)
                                KeyValue(s.segmenter, a.segmenter)
                                KeyValue("SAM", a.samModel)
                                KeyValue("Muse Spark", "${a.museModel} (${a.analyst})")
                                Text(s.framesSent(a.framesSent), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TeamsHeader(home: String, away: String, homeColor: JerseyColor, awayColor: JerseyColor) {
    val c = PpTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TeamDot(homeColor)
        Spacer(Modifier.width(6.dp))
        Text(home, style = MaterialTheme.typography.labelLarge, color = c.textPrimary, modifier = Modifier.weight(1f))
        Text(away, style = MaterialTheme.typography.labelLarge, color = c.textPrimary)
        Spacer(Modifier.width(6.dp))
        TeamDot(awayColor)
    }
}

@Composable
private fun SideBySide(label: String, a: String?, b: String?) {
    val c = PpTheme.colors
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
        Row(Modifier.fillMaxWidth()) {
            Text(a ?: "–", style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), color = c.textPrimary, modifier = Modifier.weight(1f))
            Box { Text(b ?: "–", style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), color = c.textPrimary) }
        }
    }
}

