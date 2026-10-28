package com.ppcoach.tracker.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.ppcoach.tracker.data.Library
import com.ppcoach.tracker.data.PitchReference
import com.ppcoach.tracker.data.Stroke
import com.ppcoach.tracker.data.Strokes
import com.ppcoach.tracker.ui.CoachViewModel
import com.ppcoach.tracker.ui.Screen
import com.ppcoach.tracker.ui.components.PpCard
import com.ppcoach.tracker.ui.components.PpTextButton
import com.ppcoach.tracker.ui.components.PpTopBar
import com.ppcoach.tracker.ui.components.PrimaryButton
import com.ppcoach.tracker.ui.components.SecondaryButton
import com.ppcoach.tracker.ui.i18n.LocalStrings
import com.ppcoach.tracker.ui.theme.PpTheme

/**
 * The match's reference pictures of the pitch: a photo with the camera app or
 * a frame of this clip, each with the strokes the coach painted over its
 * lines (up to 6 per match). SAM uses the strokes as examples of a line.
 */
@Composable
fun PitchReferencesScreen(vm: CoachViewModel, lib: Library, matchId: Int, clipId: Int) {
    val s = LocalStrings.current
    val c = PpTheme.colors
    val match = lib.match(matchId) ?: return
    val clip = lib.clip(clipId)
    val refs = match.references
    val full = refs.size >= PitchReference.MAX
    var pendingPhoto by remember { mutableStateOf<PitchReference?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        pendingPhoto?.let { vm.addReferencePhoto(matchId, it, ok) }
        pendingPhoto = null
    }
    var sliderMs by remember { mutableStateOf(minOf(1000L, (clip?.durationMs ?: 0) / 2).toFloat()) }
    var frameMs by remember { mutableStateOf(sliderMs.toLong()) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(frameMs) { frame = clip?.let { vm.frameAt(it, frameMs, 960) } }
    val thumbs = remember { mutableStateMapOf<Int, Bitmap?>() }

    Column(Modifier.fillMaxSize()) {
        PpTopBar(s.referencesTitle, onBack = { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(s.referencesHelp, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            if (full) Text(s.referencesMax, style = MaterialTheme.typography.bodyMedium, color = c.warning)
            PrimaryButton(s.takePhoto, {
                vm.newReferencePhoto(matchId)?.let { (ref, uri) ->
                    pendingPhoto = ref
                    camera.launch(uri)
                }
            }, Modifier.fillMaxWidth(), enabled = !full, icon = Icons.Rounded.PhotoCamera)

            if (clip != null) {
                PpCard {
                    val bmp = frame
                    val aspect = if (bmp != null && bmp.height > 0) bmp.width.toFloat() / bmp.height else 16f / 9f
                    Box(Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
                        if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                    }
                    Text("${s.frameAt} ${formatDuration(sliderMs.toLong())}", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, modifier = Modifier.padding(top = 8.dp))
                    Slider(
                        value = sliderMs,
                        onValueChange = { sliderMs = it },
                        onValueChangeFinished = { frameMs = sliderMs.toLong() },
                        valueRange = 0f..clip.durationMs.coerceAtLeast(1).toFloat(),
                        colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
                    )
                    SecondaryButton(s.useThisFrame, { vm.addReferenceFrame(matchId, clip, frameMs) }, Modifier.fillMaxWidth(), enabled = !full)
                }
            }

            for (ref in refs) {
                LaunchedEffect(ref.id) { if (ref.id !in thumbs) thumbs[ref.id] = vm.loadReference(match, ref, 320) }
                PpCard(onClick = { vm.push(Screen.PaintReference(matchId, ref.id)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val t = thumbs[ref.id]
                        Box(Modifier.width(112.dp).aspectRatio(if (t != null && t.height > 0) t.width.toFloat() / t.height else 16f / 9f).clip(RoundedCornerShape(6.dp)).background(Color.Black)) {
                            if (t != null) {
                                Image(t.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                                Canvas(Modifier.fillMaxSize()) { drawStrokes(ref.strokes, c.accent, 2.dp.toPx()) }
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            val from = if (ref.source == PitchReference.FRAME) "${s.referenceFromFrame} · ${formatDuration(ref.tMs.coerceAtLeast(0))}" else s.referenceFromPhoto
                            Text(from, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                            Text(s.strokesCount(ref.strokes.size), style = MaterialTheme.typography.bodyMedium, color = if (ref.strokes.isEmpty()) c.warning else c.textSecondary)
                        }
                        IconButton({ vm.deleteReference(matchId, ref.id); thumbs.remove(ref.id) }) {
                            Icon(Icons.Rounded.Delete, s.delete, tint = c.negative)
                        }
                    }
                }
            }
        }
    }
}

/** Paint over the lines of one reference picture with a finger. */
@Composable
fun PaintReferenceScreen(vm: CoachViewModel, lib: Library, matchId: Int, refId: Int) {
    val s = LocalStrings.current
    val c = PpTheme.colors
    val match = lib.match(matchId) ?: return
    val ref = match.references.firstOrNull { it.id == refId } ?: return
    var strokes by remember(refId) { mutableStateOf(ref.strokes) }
    var image by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(refId) { image = vm.loadReference(match, ref) }

    Column(Modifier.fillMaxSize()) {
        PpTopBar(s.paintTitle, onBack = { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(s.paintHelp, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            val bmp = image
            val aspect = if (bmp != null && bmp.height > 0) bmp.width.toFloat() / bmp.height else 16f / 9f
            Box(Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
                if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                StrokeCanvas(strokes, c.accent) { strokes = strokes + listOf(it) }
            }
            Text(s.strokesCount(strokes.size), style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PpTextButton(s.undo, { strokes = strokes.dropLast(1) })
                PpTextButton(s.clearAll, { strokes = emptyList() }, color = c.negative)
            }
            PrimaryButton(s.save, { vm.saveReferenceStrokes(matchId, refId, strokes); vm.back() }, Modifier.fillMaxWidth())
        }
    }
}

/**
 * A transparent layer that records finger strokes (normalized 0..1) and draws
 * them; [onStroke] gets each finished stroke (a tap is not a stroke).
 */
@Composable
fun StrokeCanvas(strokes: List<Stroke>, color: Color, pending: Stroke? = null, pendingColor: Color = Color.White, onStroke: (Stroke) -> Unit) {
    var current by remember { mutableStateOf<Stroke>(emptyList()) }
    val finish by rememberUpdatedState(onStroke)
    Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
        fun norm(o: Offset) = (o.x / size.width).toDouble().coerceIn(0.0, 1.0) to (o.y / size.height).toDouble().coerceIn(0.0, 1.0)
        detectDragGestures(
            onDragStart = { current = listOf(norm(it)) },
            onDrag = { change, _ -> current = current + norm(change.position) },
            onDragEnd = {
                val done = current
                current = emptyList()
                if (Strokes.isStroke(done)) finish(Strokes.simplify(done))
            },
            onDragCancel = { current = emptyList() },
        )
    }) {
        drawStrokes(strokes, color, 3.dp.toPx())
        pending?.let { drawStrokes(listOf(it), pendingColor, 4.dp.toPx()) }
        drawStrokes(listOf(current), Color.White, 3.dp.toPx())
    }
}

fun DrawScope.drawStrokes(strokes: List<Stroke>, color: Color, width: Float) {
    for (st in strokes) {
        st.zipWithNext().forEach { (a, b) ->
            drawLine(color, Offset((a.first * size.width).toFloat(), (a.second * size.height).toFloat()), Offset((b.first * size.width).toFloat(), (b.second * size.height).toFloat()), width, StrokeCap.Round)
        }
    }
}
