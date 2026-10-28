package com.ppcoach.tracker.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.ppcoach.tracker.data.Homography
import com.ppcoach.tracker.data.Library
import com.ppcoach.tracker.data.PaintedLine
import com.ppcoach.tracker.data.PitchTemplate
import com.ppcoach.tracker.data.PitchTouches

import com.ppcoach.tracker.data.Touch
import com.ppcoach.tracker.ui.CoachViewModel
import com.ppcoach.tracker.ui.components.FilterChips
import com.ppcoach.tracker.ui.components.PpCard
import com.ppcoach.tracker.ui.components.PpTextButton
import com.ppcoach.tracker.ui.components.PpTopBar
import com.ppcoach.tracker.ui.components.PrimaryButton
import com.ppcoach.tracker.ui.components.TopDown
import com.ppcoach.tracker.ui.components.pitchLines
import com.ppcoach.tracker.ui.i18n.LocalStrings
import com.ppcoach.tracker.ui.theme.PpTheme
import com.ppcoach.tracker.ui.theme.SparkColors

private val DIAGRAM_MARGIN = 20.dp
private const val MARGIN_M = 3.0 // roughly what the margin adds, to keep the drawing's proportions

/**
 * The coach's points: a bar picks the frame; tap a point of the image, then
 * which one it is on the drawing of the pitch (11 or 7). In "Paint lines" the
 * finger runs over a line (faint, worn out or in poor light) and then the line
 * is tapped on the drawing, or left unnamed ("just a line", for SAM). From 4
 * marks the computed pitch is drawn in violet over the frame; with exactly 4 a
 * fifth is asked (with 4 any names fit).
 */
@Composable
fun PitchTouchesScreen(vm: CoachViewModel, lib: Library, clipId: Int) {
    val s = LocalStrings.current
    val c = PpTheme.colors
    val clip = lib.clip(clipId) ?: return
    var touches by remember(clipId) { mutableStateOf(clip.touches ?: PitchTouches(size = 11, tMs = minOf(1000L, clip.durationMs / 2))) }
    var pending by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var painting by remember { mutableStateOf(false) }
    var pendingLine by remember { mutableStateOf<com.ppcoach.tracker.data.Stroke?>(null) }
    val namedLines = PitchTemplate.namedLines(touches.size)
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var sliderMs by remember { mutableStateOf(touches.tMs.toFloat()) }
    LaunchedEffect(touches.tMs) { frame = vm.frameAt(clip, touches.tMs) }
    val violet = SparkColors[1]
    val template = PitchTemplate.points(touches.size)
    val (length, width) = PitchTemplate.dimensions(touches.size)
    val h = touches.homography()

    Column(Modifier.fillMaxSize()) {
        PpTopBar(s.pitchTouchesTitle, onBack = { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChips(listOf("11" to s.football11, "7" to s.football7), touches.size.toString()) { k ->
                // Another pitch: the names change, so the points start over.
                touches = PitchTouches(size = k.toInt(), tMs = touches.tMs)
                pending = null
                pendingLine = null
            }
            FilterChips(listOf("points" to s.modePoints, "lines" to s.modeLines), if (painting) "lines" else "points") { k ->
                painting = k == "lines"
                pending = null
                pendingLine = null
            }
            // The frame with the points and, from 4 points, the computed pitch.
            val bmp = frame
            val aspect = if (bmp != null && bmp.height > 0) bmp.width.toFloat() / bmp.height else 16f / 9f
            Box(Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
                if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize().pointerInput(touches.size, painting) {
                    if (!painting) detectTapGestures { o -> pending = (o.x / size.width).toDouble().coerceIn(0.0, 1.0) to (o.y / size.height).toDouble().coerceIn(0.0, 1.0) }
                }) {
                    if (h != null) {
                        val stroke = 2.dp.toPx()
                        fun px(x: Double, y: Double) = Homography.apply(h, x, y)?.let { Offset((it.first * size.width).toFloat(), (it.second * size.height).toFloat()) }
                        for ((a, b) in PitchTemplate.lines(touches.size)) {
                            val p = px(a.first, a.second)
                            val q = px(b.first, b.second)
                            if (p != null && q != null) drawLine(violet, p, q, stroke)
                        }
                        val r = PitchTemplate.circleRadius(touches.size)
                        val circle = (0..48).mapNotNull { k -> px(r * kotlin.math.cos(k * Math.PI / 24), r * kotlin.math.sin(k * Math.PI / 24)) }
                        circle.zipWithNext().forEach { (p, q) -> drawLine(violet, p, q, stroke) }
                    }
                    drawStrokes(touches.lines.filter { it.name != null }.map { it.points }, c.accent, 3.dp.toPx())
                    drawStrokes(touches.lines.filter { it.name == null }.map { it.points }, Color.White.copy(alpha = 0.8f), 3.dp.toPx())
                    for (t in touches.points) {
                        val o = Offset((t.x * size.width).toFloat(), (t.y * size.height).toFloat())
                        drawCircle(Color.White, 7.dp.toPx(), o)
                        drawCircle(c.accent, 5.dp.toPx(), o)
                    }
                    pending?.let {
                        val o = Offset((it.first * size.width).toFloat(), (it.second * size.height).toFloat())
                        drawCircle(Color.White, 9.dp.toPx(), o, style = Stroke(3.dp.toPx()))
                    }
                }
                if (painting) StrokeCanvas(emptyList(), c.accent, pending = pendingLine) { pendingLine = it }
            }
            Row {
                Text("${s.frameAt} ${formatDuration(sliderMs.toLong())}", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            }
            Slider(
                value = sliderMs,
                onValueChange = { sliderMs = it },
                onValueChangeFinished = {
                    touches = touches.copy(tMs = sliderMs.toLong(), points = emptyList(), lines = emptyList())
                    pending = null
                    pendingLine = null
                },
                valueRange = 0f..clip.durationMs.coerceAtLeast(1).toFloat(),
                colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
            )
            val waiting = pending != null || pendingLine != null
            val named = touches.lines.count { it.name != null }
            val status = when {
                pendingLine != null -> s.lineStepName
                pending != null -> s.touchStepName
                touches.state == PitchTouches.State.NEED_FIFTH -> s.touchNeedFifth
                touches.state == PitchTouches.State.READY -> if (touches.lines.isEmpty()) s.touchReady(touches.points.size) else s.touchReadyMarks(touches.points.size, touches.lines.size)
                painting -> s.lineStepPaint
                else -> s.touchStepImage
            }
            Text(status, style = MaterialTheme.typography.titleMedium, color = if (touches.state == PitchTouches.State.NEED_FIFTH && !waiting) c.warning else c.textPrimary, minLines = 2)
            // Always in its place while painting, so the drawing below does not jump when a stroke ends.
            if (painting) PpTextButton(s.justALine, {
                pendingLine?.let { touches = touches.with(PaintedLine(null, it)) }
                pendingLine = null
            }, Modifier.alpha(if (pendingLine != null) 1f else 0f))
            if (named == 0 && touches.lines.isNotEmpty() && !waiting) Text(s.marksSummary(touches.points.size, touches.lines.size), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)

            // The drawing of the pitch: tap which mark the pending point is.
            PpCard {
                Canvas(Modifier.fillMaxWidth().aspectRatio(((length + 2 * MARGIN_M) / (width + 2 * MARGIN_M)).toFloat()).clip(RoundedCornerShape(8.dp)).background(c.surfaceHigh).pointerInput(touches.size, pending, pendingLine) {
                    detectTapGestures { tap ->
                        // The marks on the outer lines sit inside a margin, so their whole tap area is on the drawing.
                        val m = DIAGRAM_MARGIN.toPx()
                        val o = tap - Offset(m, m)
                        val td = TopDown(length, width, androidx.compose.ui.geometry.Size(size.width - 2 * m, size.height - 2 * m))
                        val stroke = pendingLine
                        if (stroke != null) {
                            // Which line: the nearest segment of the drawing.
                            val near = namedLines.minByOrNull { (_, seg) -> segmentDistance(o, td.map(seg.first.first, seg.first.second), td.map(seg.second.first, seg.second.second)) }
                            if (near != null && segmentDistance(o, td.map(near.value.first.first, near.value.first.second), td.map(near.value.second.first, near.value.second.second)) < 24.dp.toPx()) {
                                touches = touches.with(PaintedLine(near.key, stroke))
                                pendingLine = null
                            }
                            return@detectTapGestures
                        }
                        val p = pending ?: return@detectTapGestures
                        val nearest = template.minByOrNull { (_, xy) -> (td.map(xy.first, xy.second) - o).getDistance() }
                        if (nearest != null && (td.map(nearest.value.first, nearest.value.second) - o).getDistance() < 32.dp.toPx()) {
                            touches = touches.with(Touch(nearest.key, p.first, p.second))
                            pending = null
                        }
                    }
                }) {
                    inset(DIAGRAM_MARGIN.toPx()) {
                        pitchLines(touches.size, length, width, c.textSecondary, 1.5.dp.toPx())
                        val td = TopDown(length, width, size)
                        val usedLines = touches.lines.mapNotNull { it.name }.toSet()
                        for ((name, seg) in namedLines) {
                            if (name in usedLines) drawLine(c.accent, td.map(seg.first.first, seg.first.second), td.map(seg.second.first, seg.second.second), 4.dp.toPx())
                        }
                        val used = touches.points.map { it.name }.toSet()
                        for ((name, xy) in template) {
                            val o = td.map(xy.first, xy.second)
                            drawCircle(if (name in used) c.accent else c.textPrimary, (if (name in used) 6 else 4).dp.toPx(), o)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PpTextButton(s.undo, {
                    touches = if (painting) touches.copy(lines = touches.lines.dropLast(1)) else touches.copy(points = touches.points.dropLast(1))
                    pending = null
                    pendingLine = null
                })
                PpTextButton(s.clearAll, { touches = touches.copy(points = emptyList(), lines = emptyList()); pending = null; pendingLine = null }, color = c.negative)
            }
            PrimaryButton(s.save, { vm.saveTouches(clipId, touches); vm.back() }, Modifier.fillMaxWidth())
        }
    }
}

/** Distance from [p] to the segment [a]–[b] (diagram pixels). */
private fun segmentDistance(p: Offset, a: Offset, b: Offset): Float {
    val d = b - a
    val len2 = d.x * d.x + d.y * d.y
    val t = if (len2 > 0f) (((p.x - a.x) * d.x + (p.y - a.y) * d.y) / len2).coerceIn(0f, 1f) else 0f
    return (p - (a + d * t)).getDistance()
}
