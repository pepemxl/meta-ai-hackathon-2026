package com.ppcoach.tracker.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ppcoach.tracker.data.Library
import com.ppcoach.tracker.data.PlayArea
import com.ppcoach.tracker.ui.CoachViewModel
import com.ppcoach.tracker.ui.components.PpTextButton
import com.ppcoach.tracker.ui.components.PpTopBar
import com.ppcoach.tracker.ui.components.PrimaryButton
import com.ppcoach.tracker.ui.i18n.LocalStrings
import com.ppcoach.tracker.ui.theme.PpTheme

// A panorama is shown at least this tall (wider than the screen, it is moved
// sideways), so a corner can be put on the line with a finger.
private val MIN_FRAME_HEIGHT = 200.dp
private val GRAB = 28.dp
private val LOUPE = 52.dp
private const val LOUPE_ZOOM = 3f

/**
 * The play area on a still camera: four points to drag onto the corners of
 * the pitch, a + halfway along each side for a line a panorama bends, press
 * and hold to remove a point, and the margin around it (drawn as the band the
 * worker keeps). While a point is dragged a loupe shows what is under the finger.
 */
@Composable
fun PlayAreaScreen(vm: CoachViewModel, lib: Library, clipId: Int) {
    val s = LocalStrings.current
    val c = PpTheme.colors
    val clip = lib.clip(clipId) ?: return
    var area by remember(clipId) { mutableStateOf(vm.startPlayArea(clip)) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var sliderMs by remember { mutableFloatStateOf(area.tMs.toFloat()) }
    // A wide frame needs more pixels than the usual 1280 to be sharp at MIN_FRAME_HEIGHT.
    LaunchedEffect(area.tMs) { frame = vm.frameAt(clip, area.tMs, 2560) }

    Column(Modifier.fillMaxSize()) {
        PpTopBar(s.playAreaTitle, onBack = { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(s.playAreaHelp, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            val bmp = frame
            val aspect = when {
                bmp != null && bmp.height > 0 -> bmp.width.toFloat() / bmp.height
                clip.width > 0 && clip.height > 0 -> clip.width.toFloat() / clip.height
                else -> 16f / 9f
            }
            AreaEditor(area, bmp?.asImageBitmap(), aspect, c.accent) { area = it }
            Text("${s.frameAt} ${formatDuration(sliderMs.toLong())}", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            Slider(
                value = sliderMs,
                onValueChange = { sliderMs = it },
                // The camera does not move: the points stay, only the picture changes.
                onValueChangeFinished = { area = area.copy(tMs = sliderMs.toLong()) },
                valueRange = 0f..clip.durationMs.coerceAtLeast(1).toFloat(),
                colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
            )
            Text(s.playAreaMargin(kotlin.math.round(area.margin * 100).toInt()), style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            Slider(
                value = area.margin.toFloat(),
                onValueChange = { area = area.copy(margin = (kotlin.math.round(it * 100) / 100.0)) },
                valueRange = 0f..PlayArea.MAX_MARGIN.toFloat(),
                colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
            )
            Text(s.playAreaMarginHelp, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PpTextButton(s.playAreaReset, { area = PlayArea.start(area.tMs).copy(margin = area.margin) })
                if (clip.playArea != null) PpTextButton(s.playAreaRemove, { vm.savePlayArea(clipId, null); vm.back() }, color = c.negative)
            }
            PrimaryButton(s.save, { vm.savePlayArea(clipId, area); vm.back() }, Modifier.fillMaxWidth())
        }
    }
}

/** The frame with the area over it; [onChange] gets every move, split and removal. */
@Composable
private fun AreaEditor(area: PlayArea, image: ImageBitmap?, aspect: Float, accent: Color, onChange: (PlayArea) -> Unit) {
    val current by rememberUpdatedState(area)
    val change by rememberUpdatedState(onChange)
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val boxW = with(density) { maxWidth.toPx() }
        val contentH = maxOf(boxW / aspect, with(density) { MIN_FRAME_HEIGHT.toPx() })
        val contentW = contentH * aspect
        val maxPan = (contentW - boxW).coerceAtLeast(0f)
        var pan by remember(maxPan) { mutableFloatStateOf(maxPan / 2) }
        var dragging by remember { mutableIntStateOf(-1) }
        fun toScreen(p: Pair<Double, Double>) = Offset((p.first * contentW).toFloat() - pan, (p.second * contentH).toFloat())
        fun toArea(o: Offset) = ((o.x + pan) / contentW).toDouble() to (o.y / contentH).toDouble()
        fun nearest(o: Offset, pts: List<Offset>, within: Float) = pts.indices.minByOrNull { (pts[it] - o).getDistance() }?.takeIf { (pts[it] - o).getDistance() <= within }
        fun midpoints(a: PlayArea) = a.points.indices.map { i -> toScreen(a.points[i]).let { p -> (p + toScreen(a.points[(i + 1) % a.points.size])) / 2f } }

        Canvas(
            Modifier.fillMaxWidth().height(with(density) { contentH.toDp() }).clip(RoundedCornerShape(8.dp)).background(Color.Black)
                .pointerInput(contentW, contentH) {
                    val grab = GRAB.toPx()
                    detectDragGestures(
                        onDragStart = { o -> dragging = nearest(o, current.points.map(::toScreen), grab) ?: -1 },
                        onDrag = { ch, amount ->
                            ch.consume()
                            val i = dragging
                            if (i >= 0) toArea(ch.position).let { (x, y) -> change(current.moved(i, x, y)) }
                            else pan = (pan - amount.x).coerceIn(0f, maxPan)
                        },
                        onDragEnd = { dragging = -1 },
                        onDragCancel = { dragging = -1 },
                    )
                }
                .pointerInput(contentW, contentH) {
                    val grab = GRAB.toPx()
                    detectTapGestures(
                        onTap = { o -> if (current.points.size < PlayArea.MAX_POINTS) nearest(o, midpoints(current), grab)?.let { change(current.splitSide(it)) } },
                        onLongPress = { o -> nearest(o, current.points.map(::toScreen), grab)?.let { change(current.without(it)) } },
                    )
                },
        ) {
            if (image != null) drawImage(image, dstOffset = IntOffset(-pan.toInt(), 0), dstSize = IntSize(contentW.toInt(), contentH.toInt()))
            val pts = area.points.map(::toScreen)
            val path = Path().apply {
                pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
                close()
            }
            // The margin: a stroke twice as wide as it, with round joins, is exactly the band the worker keeps.
            val band = (area.margin * contentH).toFloat() * 2
            if (band > 0f) drawPath(path, Color.White.copy(alpha = 0.22f), style = Stroke(band, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, accent.copy(alpha = 0.18f))
            drawPath(path, accent, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
            if (pts.size < PlayArea.MAX_POINTS) for (m in midpoints(area)) {
                drawCircle(Color.Black.copy(alpha = 0.55f), 9.dp.toPx(), m)
                val r = 5.dp.toPx()
                drawLine(Color.White, m - Offset(r, 0f), m + Offset(r, 0f), 2.dp.toPx())
                drawLine(Color.White, m - Offset(0f, r), m + Offset(0f, r), 2.dp.toPx())
            }
            pts.forEachIndexed { i, p ->
                drawCircle(Color.White, (if (i == dragging) 9 else 7).dp.toPx(), p)
                drawCircle(accent, (if (i == dragging) 7 else 5).dp.toPx(), p)
            }
            val i = dragging
            if (image != null && i in pts.indices) loupe(image, area.points[i], pts[i], contentW, accent)
        }
    }
}

/** What is under the finger, LOUPE_ZOOM times larger, in the corner away from it. */
private fun DrawScope.loupe(image: ImageBitmap, p: Pair<Double, Double>, finger: Offset, contentW: Float, accent: Color) {
    val r = LOUPE.toPx()
    val center = Offset(if (finger.x > size.width / 2) r + 8.dp.toPx() else size.width - r - 8.dp.toPx(), r + 8.dp.toPx())
    // Image pixels the loupe covers: its diameter at LOUPE_ZOOM times the size the frame is drawn at.
    val src = (2 * r / (LOUPE_ZOOM * contentW / image.width)).toInt().coerceAtLeast(8)
    val sx = (p.first * image.width).toInt() - src / 2
    val sy = (p.second * image.height).toInt() - src / 2
    val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, r)) }
    drawCircle(Color.Black, r, center)
    clipPath(circle) {
        // srcOffset must stay inside the image: shift the drawing by what was cut off at the edges.
        val ox = sx.coerceIn(0, (image.width - 1).coerceAtLeast(0))
        val oy = sy.coerceIn(0, (image.height - 1).coerceAtLeast(0))
        val w = (src - (ox - sx)).coerceAtMost(image.width - ox)
        val h = (src - (oy - sy)).coerceAtMost(image.height - oy)
        if (w > 0 && h > 0) {
            val k = 2 * r / src
            drawImage(
                image, srcOffset = IntOffset(ox, oy), srcSize = IntSize(w, h),
                dstOffset = IntOffset((center.x - r + (ox - sx) * k).toInt(), (center.y - r + (oy - sy) * k).toInt()),
                dstSize = IntSize((w * k).toInt(), (h * k).toInt()),
            )
        }
    }
    drawCircle(Color.White, r, center, style = Stroke(2.dp.toPx()))
    val t = 8.dp.toPx()
    drawLine(accent, center - Offset(t, 0f), center + Offset(t, 0f), 1.5.dp.toPx())
    drawLine(accent, center - Offset(0f, t), center + Offset(0f, t), 1.5.dp.toPx())
}
