package com.ppcoach.tracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ppcoach.tracker.data.ClipAnalysis
import com.ppcoach.tracker.data.JerseyColor
import com.ppcoach.tracker.data.PitchMode
import com.ppcoach.tracker.data.PitchTemplate
import com.ppcoach.tracker.ui.CoachViewModel
import com.ppcoach.tracker.ui.i18n.LocalStrings
import com.ppcoach.tracker.ui.theme.PpTheme

/** "Do we compute the pitch?" (docs/mejoras/04-homografia-por-cuadro.md), asked once per match when sending. */
@Composable
fun PitchDialog(vm: CoachViewModel, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val c = PpTheme.colors
    var mode by remember { mutableStateOf(PitchMode.LINES) }
    var forMatch by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(s.pitchQuestionTitle, color = c.textPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(s.pitchQuestionIntro, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                Text(s.pitchLinesQuestion, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                for ((key, label) in listOf(PitchMode.LINES to s.pitchLines, PitchMode.NO_LINES to s.pitchNoLines, PitchMode.OFF to s.pitchOff)) {
                    Row(Modifier.fillMaxWidth().clickable { mode = key }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(mode == key, { mode = key }, colors = RadioButtonDefaults.colors(selectedColor = c.accent))
                        Text(label, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { forMatch = !forMatch }.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(forMatch, { forMatch = it }, colors = CheckboxDefaults.colors(checkedColor = c.accent, checkmarkColor = c.onAccent))
                    Text(s.pitchUseForMatch, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                }
            }
        },
        confirmButton = { PrimaryButton(s.ok, { vm.answerPitch(mode, forMatch) }) },
        dismissButton = { PpTextButton(s.cancel, onDismiss) },
    )
}

/** Maps pitch meters to a top-down canvas: the far touchline on top. */
class TopDown(private val length: Double, private val width: Double, private val size: Size) {
    fun map(x: Double, y: Double) = Offset(((x + length / 2) / length * size.width).toFloat(), ((width / 2 - y) / width * size.height).toFloat())
    val scale: Float get() = (size.width / length).toFloat()
}

fun DrawScope.pitchLines(template: Int, length: Double, width: Double, color: Color, stroke: Float) {
    val td = TopDown(length, width, size)
    for ((a, b) in PitchTemplate.lines(template, length, width)) drawLine(color, td.map(a.first, a.second), td.map(b.first, b.second), stroke)
    drawCircle(color, (PitchTemplate.circleRadius(template) * td.scale).toFloat(), td.map(0.0, 0.0), style = Stroke(stroke))
}

/** The pitch from above with a team's heatmap (8×12, far touchline on top) and the lines. */
@Composable
fun TopDownPitch(stats: ClipAnalysis.PitchStats, heatmap: List<List<Double>>?, color: JerseyColor, modifier: Modifier = Modifier) {
    val c = PpTheme.colors
    val team = Color(color.argb)
    val max = heatmap?.maxOf { r -> r.maxOrNull() ?: 0.0 }?.takeIf { it > 0 } ?: 1.0
    Canvas(modifier.fillMaxWidth().aspectRatio((stats.lengthM / stats.widthM).toFloat()).clip(RoundedCornerShape(8.dp)).background(c.surfaceHigh)) {
        if (heatmap != null) {
            val cw = size.width / ClipAnalysis.HEAT_COLS
            val ch = size.height / ClipAnalysis.HEAT_ROWS
            heatmap.forEachIndexed { r, row ->
                row.forEachIndexed { col, v ->
                    if (v > 0) drawRect(team.copy(alpha = (0.15 + 0.85 * v / max).toFloat()), Offset(col * cw, r * ch), Size(cw, ch))
                }
            }
        }
        pitchLines(stats.size, stats.lengthM, stats.widthM, c.textSecondary, 1.5.dp.toPx())
    }
}
