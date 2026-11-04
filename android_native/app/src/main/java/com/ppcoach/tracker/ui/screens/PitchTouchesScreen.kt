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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.ppcoach.tracker.data.PitchSurface
import com.ppcoach.tracker.data.SavedPitch
import com.ppcoach.tracker.data.Strokes
import com.ppcoach.tracker.data.SurfaceSample
import com.ppcoach.tracker.data.Touch
import com.ppcoach.tracker.ui.CoachViewModel
import com.ppcoach.tracker.ui.components.FilterChips
import com.ppcoach.tracker.ui.components.PitchChoice
import com.ppcoach.tracker.ui.components.PitchEditorDialog
import com.ppcoach.tracker.ui.components.PitchPicker
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
private const val MAX_ZOOM = 8f
private const val MOVE_STEP = 0.25f // of what is in view

/**
 * The coach's points: a bar picks the frame; tap a point of the image, then
 * which one it is on the drawing of the pitch. The pitch comes from a
 * dropdown of saved pitches (name, 11 or 7 and its meters, measured once and
 * reused) or an unsaved 11 or 7 with the typical size. In "Paint lines" the
 * finger runs over a line (faint, worn out or in poor light) and then the line
 * is tapped on the drawing, or left unnamed ("just a line", for SAM). From 4
 * marks the computed pitch is drawn in violet over the frame; with exactly 4 a
 * fifth is asked (with 4 any names fit). A line can also be two taps, one on
 * each end. Buttons under the frame zoom it (up to ×8) and move it, to mark
 * small or far points precisely.
 */
@Composable
fun PitchTouchesScreen(vm: CoachViewModel, lib: Library, clipId: Int) {
    val s = LocalStrings.current
    val c = PpTheme.colors
    val clip = lib.clip(clipId) ?: return
    var touches by remember(clipId) { mutableStateOf(vm.startTouches(clip)) }
    var editing by remember { mutableStateOf<SavedPitch?>(null) }
    var pending by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var painting by remember { mutableStateOf(false) }
    // Tapping the grass (or the dirt) to sample its color for the saved pitch.
    var sampling by remember { mutableStateOf(false) }
    var lastTap by remember { mutableStateOf<Offset?>(null) }
    var pendingLine by remember { mutableStateOf<com.ppcoach.tracker.data.Stroke?>(null) }
    // A line from two taps: the first end, until the second.
    var twoPoints by remember { mutableStateOf(false) }
    var lineStart by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    // The zoom over the frame and the top-left corner of what is in view (0..1 of the frame).
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    fun zoomTo(z: Float) {
        val next = z.coerceIn(1f, MAX_ZOOM)
        val shift = 0.5f / zoom - 0.5f / next // keeps the middle of the view in place
        val max = 1f - 1f / next
        pan = Offset((pan.x + shift).coerceIn(0f, max), (pan.y + shift).coerceIn(0f, max))
        zoom = next
    }
    fun move(dx: Float, dy: Float) {
        val max = 1f - 1f / zoom
        pan = Offset((pan.x + dx * MOVE_STEP / zoom).coerceIn(0f, max), (pan.y + dy * MOVE_STEP / zoom).coerceIn(0f, max))
    }
    val namedLines = PitchTemplate.namedLines(touches.size, touches.lengthM, touches.widthM)
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var sliderMs by remember { mutableStateOf(touches.tMs.toFloat()) }
    LaunchedEffect(touches.tMs) { frame = vm.frameAt(clip, touches.tMs) }
    val violet = SparkColors[1]
    val template = PitchTemplate.points(touches.size, touches.lengthM, touches.widthM)
    val (length, width) = touches.dimensions
    val h = touches.homography()

    Column(Modifier.fillMaxSize()) {
        PpTopBar(s.pitchTouchesTitle, onBack = { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PitchPicker(lib.pitchesByUse, lib.pitch(touches.pitchId), touches.size) { choice ->
                val before = touches.size
                when (choice) {
                    is PitchChoice.Saved -> {
                        touches = touches.on(choice.pitch)
                        vm.usePitch(clip.matchId, choice.pitch.id)
                    }
                    is PitchChoice.Unsaved -> touches = touches.on(null, choice.size)
                    PitchChoice.New -> editing = SavedPitch(0, lib.match(clip.matchId)?.venue.orEmpty(), touches.size)
                    is PitchChoice.Edit -> editing = choice.pitch
                }
                // Another size: the names change, so the points started over.
                if (touches.size != before) {
                    pending = null
                    pendingLine = null
                    lineStart = null
                }
            }
            val saved = lib.pitch(touches.pitchId)
            val dirt = saved?.surface == PitchSurface.DIRT
            FilterChips(listOf("points" to s.modePoints, "lines" to s.modeLines, "sample" to s.modeSample(dirt)), if (sampling) "sample" else if (painting) "lines" else "points") { k ->
                painting = k == "lines"
                sampling = k == "sample"
                pending = null
                pendingLine = null
                lineStart = null
                lastTap = null
            }
            if (painting) FilterChips(listOf("free" to s.lineFreehand, "two" to s.lineTwoPoints), if (twoPoints) "two" else "free") { k ->
                twoPoints = k == "two"
                pendingLine = null
                lineStart = null
            }
            // The frame with the points and, from 4 points, the computed pitch.
            val bmp = frame
            val aspect = if (bmp != null && bmp.height > 0) bmp.width.toFloat() / bmp.height else 16f / 9f
            // While sampling, the cells of the frame the sample takes in, on the detector's 160×90 grid.
            val grid = remember(bmp) { bmp?.let { Bitmap.createScaledBitmap(it, GRID_W, GRID_H, true) } }
            val matched = remember(grid, saved?.sample) {
                val sample = saved?.sample
                if (grid == null || sample == null) null else {
                    val px = IntArray(GRID_W * GRID_H).also { grid.getPixels(it, 0, GRID_W, 0, 0, GRID_W, GRID_H) }
                    BooleanArray(px.size) { i -> sample.matches((px[i] shr 16) and 0xFF, (px[i] shr 8) and 0xFF, px[i] and 0xFF) }
                }
            }
            fun sampleAt(x: Float, y: Float) {
                val image = bmp ?: return
                val p = saved ?: return vm.toast(s.sampleNeedsPitch)
                if ((p.sample?.taps ?: 0) >= SurfaceSample.MAX_TAPS) return vm.toast(s.sampleFull)
                val px = patch(image, x, y)
                val tap = SurfaceSample.fromPixels(px)
                    ?: return vm.toast(if (SurfaceSample.rejection(px) == SurfaceSample.Rejection.SHADE) s.sampleShade(dirt) else s.sampleMixed(dirt))
                vm.savePitch(p.copy(sample = p.sample?.plus(tap) ?: tap))
                vm.usePitch(clip.matchId, p.id)
            }
            Box(Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
              // Everything inside is zoomed together, so taps and strokes stay in 0..1 of the frame.
              Box(Modifier.fillMaxSize().graphicsLayer {
                  transformOrigin = TransformOrigin(0f, 0f)
                  scaleX = zoom
                  scaleY = zoom
                  translationX = -pan.x * size.width * zoom
                  translationY = -pan.y * size.height * zoom
              }) {
                if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize().pointerInput(touches.size, touches.dimensions, painting, twoPoints, sampling, bmp, saved) {
                    if (!painting || twoPoints) detectTapGestures { o ->
                        val p = (o.x / size.width).toDouble().coerceIn(0.0, 1.0) to (o.y / size.height).toDouble().coerceIn(0.0, 1.0)
                        when {
                            sampling -> {
                                lastTap = o
                                sampleAt(o.x / size.width, o.y / size.height)
                            }
                            painting -> {
                                val start = lineStart
                                if (start == null) {
                                    lineStart = p
                                    pendingLine = null
                                } else {
                                    lineStart = null
                                    listOf(start, p).takeIf { Strokes.isStroke(it) }?.let { pendingLine = it }
                                }
                            }
                            else -> pending = p
                        }
                    }
                }) {
                    if (sampling) {
                        matched?.let { cells ->
                            val cw = size.width / GRID_W
                            val ch = size.height / GRID_H
                            for (i in cells.indices) if (cells[i]) drawRect(violet.copy(alpha = 0.45f), Offset(i % GRID_W * cw, i / GRID_W * ch), androidx.compose.ui.geometry.Size(cw, ch))
                        }
                        lastTap?.let { drawCircle(Color.White, PATCH * size.width, it, style = Stroke(2.dp.toPx() / zoom)) }
                        return@Canvas
                    }
                    if (h != null) {
                        val stroke = 2.dp.toPx() / zoom
                        fun px(x: Double, y: Double) = Homography.apply(h, x, y)?.let { Offset((it.first * size.width).toFloat(), (it.second * size.height).toFloat()) }
                        for ((a, b) in PitchTemplate.lines(touches.size, touches.lengthM, touches.widthM)) {
                            val p = px(a.first, a.second)
                            val q = px(b.first, b.second)
                            if (p != null && q != null) drawLine(violet, p, q, stroke)
                        }
                        val r = PitchTemplate.circleRadius(touches.size)
                        val circle = (0..48).mapNotNull { k -> px(r * kotlin.math.cos(k * Math.PI / 24), r * kotlin.math.sin(k * Math.PI / 24)) }
                        circle.zipWithNext().forEach { (p, q) -> drawLine(violet, p, q, stroke) }
                    }
                    // Sizes divided by the zoom, so marks keep their size on screen.
                    drawStrokes(touches.lines.filter { it.name != null }.map { it.points }, c.accent, 3.dp.toPx() / zoom)
                    drawStrokes(touches.lines.filter { it.name == null }.map { it.points }, Color.White.copy(alpha = 0.8f), 3.dp.toPx() / zoom)
                    for (t in touches.points) {
                        val o = Offset((t.x * size.width).toFloat(), (t.y * size.height).toFloat())
                        drawCircle(Color.White, 7.dp.toPx() / zoom, o)
                        drawCircle(c.accent, 5.dp.toPx() / zoom, o)
                    }
                    pending?.let {
                        val o = Offset((it.first * size.width).toFloat(), (it.second * size.height).toFloat())
                        drawCircle(Color.White, 9.dp.toPx() / zoom, o, style = Stroke(3.dp.toPx() / zoom))
                    }
                    if (twoPoints) {
                        lineStart?.let { drawCircle(Color.White, 6.dp.toPx() / zoom, Offset((it.first * size.width).toFloat(), (it.second * size.height).toFloat()), style = Stroke(2.dp.toPx() / zoom)) }
                        pendingLine?.let { drawStrokes(listOf(it), Color.White, 4.dp.toPx() / zoom) }
                    }
                }
                if (painting && !twoPoints) StrokeCanvas(emptyList(), c.accent, pending = pendingLine, zoom = zoom) { pendingLine = it }
              }
            }
            // Zoom and move the frame.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FrameButton(Icons.Rounded.ZoomOut, s.zoomOut, zoom > 1f) { zoomTo(zoom / 2f) }
                Text("×${zoom.toInt()}", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                FrameButton(Icons.Rounded.ZoomIn, s.zoomIn, zoom < MAX_ZOOM) { zoomTo(zoom * 2f) }
                Spacer(Modifier.weight(1f))
                val max = 1f - 1f / zoom
                FrameButton(Icons.Rounded.KeyboardArrowUp, s.moveLeft, pan.x > 0f, -90f) { move(-1f, 0f) }
                FrameButton(Icons.Rounded.KeyboardArrowUp, s.moveUp, pan.y > 0f) { move(0f, -1f) }
                FrameButton(Icons.Rounded.KeyboardArrowUp, s.moveDown, pan.y < max, 180f) { move(0f, 1f) }
                FrameButton(Icons.Rounded.KeyboardArrowUp, s.moveRight, pan.x < max, 90f) { move(1f, 0f) }
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
                    lineStart = null
                },
                valueRange = 0f..clip.durationMs.coerceAtLeast(1).toFloat(),
                colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
            )
            val waiting = pending != null || pendingLine != null || lineStart != null
            val named = touches.lines.count { it.name != null }
            val status = when {
                sampling -> saved?.sample?.let { s.sampleSummary(it.taps, it.hue, it.sat, it.value) } ?: if (saved == null) s.sampleNeedsPitch else s.sampleStep(dirt)
                pendingLine != null -> s.lineStepName
                pending != null -> s.touchStepName
                lineStart != null -> s.lineStepSecondEnd
                touches.state == PitchTouches.State.NEED_FIFTH -> s.touchNeedFifth
                touches.state == PitchTouches.State.READY -> if (touches.lines.isEmpty()) s.touchReady(touches.points.size) else s.touchReadyMarks(touches.points.size, touches.lines.size)
                painting -> if (twoPoints) s.lineStepFirstEnd else s.lineStepPaint
                else -> s.touchStepImage
            }
            Text(status, style = MaterialTheme.typography.titleMedium, color = if (touches.state == PitchTouches.State.NEED_FIFTH && !waiting) c.warning else c.textPrimary, minLines = 2)
            // Always in its place while painting, so the drawing below does not jump when a stroke ends.
            if (sampling) {
                Text(s.sampleHelp, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                if (saved?.sample != null) PpTextButton(s.clearSample, { vm.savePitch(saved.copy(sample = null)); lastTap = null }, color = c.negative)
            }
            if (painting) PpTextButton(s.justALine, {
                pendingLine?.let { touches = touches.with(PaintedLine(null, it)) }
                pendingLine = null
            }, Modifier.alpha(if (pendingLine != null) 1f else 0f))
            if (named == 0 && touches.lines.isNotEmpty() && !waiting) Text(s.marksSummary(touches.points.size, touches.lines.size), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)

            // The drawing of the pitch: tap which mark the pending point is.
            PpCard {
                Canvas(Modifier.fillMaxWidth().aspectRatio(((length + 2 * MARGIN_M) / (width + 2 * MARGIN_M)).toFloat()).clip(RoundedCornerShape(8.dp)).background(c.surfaceHigh).pointerInput(touches.size, touches.dimensions, pending, pendingLine) {
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
                    lineStart = null
                })
                PpTextButton(s.clearAll, { touches = touches.copy(points = emptyList(), lines = emptyList()); pending = null; pendingLine = null; lineStart = null }, color = c.negative)
            }
            PrimaryButton(s.save, { vm.saveTouches(clipId, touches); vm.back() }, Modifier.fillMaxWidth())
        }
    }
    editing?.let { p ->
        PitchEditorDialog(
            initial = p,
            onDismiss = { editing = null },
            onSave = { edited ->
                val saved = vm.savePitch(edited)
                vm.usePitch(clip.matchId, saved.id)
                val before = touches.size
                touches = touches.on(saved)
                if (touches.size != before) {
                    pending = null
                    pendingLine = null
                    lineStart = null
                }
                editing = null
            },
            onDelete = if (p.id > 0) ({
                vm.deletePitch(p.id)
                touches = touches.on(null, touches.size)
                editing = null
            }) else null,
        )
    }
}

/** A button under the frame; the arrows are one icon turned by [turn] degrees. */
@Composable
private fun FrameButton(icon: ImageVector, label: String, enabled: Boolean, turn: Float = 0f, onClick: () -> Unit) {
    val c = PpTheme.colors
    IconButton(onClick, enabled = enabled) {
        Icon(icon, label, Modifier.rotate(turn), tint = if (enabled) c.textPrimary else c.textSecondary.copy(alpha = 0.4f))
    }
}

private const val GRID_W = 160
private const val GRID_H = 90
/** Half the side of the sampled patch, a fraction of the frame's width. */
private const val PATCH = 0.015f

/** The pixels (0xRRGGBB) of a square patch of [bmp] around ([x], [y]) in 0..1. */
private fun patch(bmp: Bitmap, x: Float, y: Float): IntArray {
    val r = maxOf(3, (PATCH * bmp.width).toInt())
    val cx = (x * bmp.width).toInt().coerceIn(0, bmp.width - 1)
    val cy = (y * bmp.height).toInt().coerceIn(0, bmp.height - 1)
    val x0 = (cx - r).coerceAtLeast(0)
    val y0 = (cy - r).coerceAtLeast(0)
    val w = (cx + r).coerceAtMost(bmp.width - 1) - x0 + 1
    val h = (cy + r).coerceAtMost(bmp.height - 1) - y0 + 1
    return IntArray(w * h).also { bmp.getPixels(it, 0, w, x0, y0, w, h) }.map { it and 0xFFFFFF }.toIntArray()
}

/** Distance from [p] to the segment [a]–[b] (diagram pixels). */
private fun segmentDistance(p: Offset, a: Offset, b: Offset): Float {
    val d = b - a
    val len2 = d.x * d.x + d.y * d.y
    val t = if (len2 > 0f) (((p.x - a.x) * d.x + (p.y - a.y) * d.y) / len2).coerceIn(0f, 1f) else 0f
    return (p - (a + d * t)).getDistance()
}
