package com.ppcoach.tracker.ui.screens

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.ppcoach.tracker.analysis.UploadWorker
import com.ppcoach.tracker.data.ChunkProfile
import com.ppcoach.tracker.data.Chunks
import com.ppcoach.tracker.data.Clip
import com.ppcoach.tracker.data.ClipKind
import com.ppcoach.tracker.data.ClipStatus
import com.ppcoach.tracker.data.Library
import com.ppcoach.tracker.data.CameraMode
import com.ppcoach.tracker.data.PitchMode
import com.ppcoach.tracker.data.ProxyPaths
import com.ppcoach.tracker.data.ProxySpec
import com.ppcoach.tracker.ui.CoachViewModel
import com.ppcoach.tracker.ui.Screen
import com.ppcoach.tracker.ui.SendProgress
import com.ppcoach.tracker.ui.components.KeyValue
import com.ppcoach.tracker.ui.components.PpCard
import com.ppcoach.tracker.ui.components.PpChip
import com.ppcoach.tracker.ui.components.PpProgress
import com.ppcoach.tracker.ui.components.PpTextButton
import com.ppcoach.tracker.ui.components.PpTopBar
import com.ppcoach.tracker.ui.components.PrimaryButton
import com.ppcoach.tracker.ui.components.SectionTitle
import com.ppcoach.tracker.ui.components.SparkButton
import com.ppcoach.tracker.ui.components.StatusChip
import com.ppcoach.tracker.ui.i18n.LocalStrings
import com.ppcoach.tracker.ui.i18n.Strings
import com.ppcoach.tracker.ui.theme.PpTheme
import java.io.File

@Composable
fun ClipDetailScreen(vm: CoachViewModel, lib: Library, clipId: Int, seekMs: Long) {
    val s = LocalStrings.current
    val c = PpTheme.colors
    val context = LocalContext.current
    val clip = lib.clip(clipId) ?: return
    val match = lib.match(clip.matchId) ?: return
    val file = vm.videoFile(clip)
    var confirmDelete by remember { mutableStateOf(false) }
    var title by remember(clipId) { mutableStateOf(clip.title) }
    var notes by remember(clipId) { mutableStateOf(clip.notes) }

    val player = remember(clipId) {
        ExoPlayer.Builder(context).build().apply {
            if (file != null && file.exists()) {
                setMediaItem(MediaItem.fromUri(file.toURI().toString()))
                prepare()
                if (seekMs >= 0) {
                    seekTo(seekMs)
                    playWhenReady = true
                }
            }
        }
    }
    DisposableEffect(clipId) { onDispose { player.release() } }
    LaunchedEffect(clipId) { vm.refreshRemote(clipId) }

    val infos by vm.progress(clipId).collectAsState(initial = emptyList())
    val progress = vm.toProgress(infos)

    Column(Modifier.fillMaxSize()) {
        PpTopBar(clip.title.ifBlank { s.clipDetails }, onBack = { vm.back() }) {
            IconButton(onClick = { vm.updateClip(clipId) { it.copy(starred = !it.starred) } }) {
                Icon(if (clip.starred) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (clip.starred) s.unstar else s.star, tint = if (clip.starred) c.star else c.textPrimary)
            }
            IconButton(onClick = { file?.let { share(context, it, "video/mp4") } }) { Icon(Icons.Rounded.Share, s.share, tint = c.textPrimary) }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, s.deleteClip, tint = c.textPrimary) }
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).padding(0.dp)) {
                if (file != null && file.exists()) {
                    AndroidView(factory = { PlayerView(it).apply { this.player = player; setBackgroundColor(android.graphics.Color.BLACK) } }, modifier = Modifier.fillMaxSize())
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(s.videoMissing, color = c.textSecondary) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                IconButton(onClick = { player.seekTo((player.currentPosition - 5000).coerceAtLeast(0)) }) { Icon(Icons.Rounded.Replay5, "-5 s", tint = c.textPrimary) }
                IconButton(onClick = { player.seekTo(player.currentPosition + 5000) }) { Icon(Icons.Rounded.Forward5, "+5 s", tint = c.textPrimary) }
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SendCard(vm, clip, progress, s)
                PitchCard(vm, clip, locked = (progress != null && !progress.finished) || clip.status !in listOf(ClipStatus.LOCAL, ClipStatus.FAILED), s = s)
                PpCard {
                    KeyValue(s.navMatches, "${match.home} ${s.vs} ${match.away}")
                    KeyValue(s.date.substringBefore(" ("), match.date)
                    KeyValue(s.duration, formatDuration(clip.durationMs))
                    KeyValue(s.size, "${formatMb(clip.sizeBytes)} MB")
                    if (vm.settings.technical) {
                        KeyValue(s.resolution, "${clip.width}×${clip.height}")
                        KeyValue(s.frameRate, "%.2f".format(clip.fps))
                        KeyValue(s.file, file?.absolutePath ?: "-")
                        KeyValue(s.recordingFormat, s.recordingFormatValue)
                    }
                }
                PpCard {
                    OutlinedTextField(title, { title = it.take(160) }, label = { Text(s.title) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(notes, { notes = it.take(1000) }, label = { Text(s.notes) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(s.kind, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (k in ClipKind.entries) PpChip(s.kindLabel(k.key), clip.kind == k) { vm.updateClip(clipId) { it.copy(kind = k) } }
                    }
                    if (title != clip.title || notes != clip.notes) {
                        PrimaryButton(s.save, { vm.updateClip(clipId) { it.copy(title = title.trim(), notes = notes.trim()) } }, Modifier.padding(top = 8.dp))
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && file != null) {
                        PpTextButton(s.exportGallery, { vm.toast(if (exportToGallery(context, file, clip)) s.exported else s.exportFailed) })
                    }
                }
                if (vm.settings.technical) TechnicalCard(vm, clip, s)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = c.surface,
            title = { Text(s.deleteClip, color = c.textPrimary) },
            text = { Text(s.confirmDelete, color = c.textSecondary) },
            confirmButton = { PpTextButton(s.delete, { confirmDelete = false; vm.deleteClip(clipId) }, color = c.negative) },
            dismissButton = { PpTextButton(s.cancel, { confirmDelete = false }) },
        )
    }
}

/** "Send for analysis" with its two-step bar: preparing 0–50 %, sending 50–100 %. */
@Composable
private fun SendCard(vm: CoachViewModel, clip: Clip, progress: SendProgress?, s: Strings) {
    val c = PpTheme.colors
    val context = LocalContext.current
    val running = progress != null && !progress.finished
    PpCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(clip.status)
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        when {
            running && progress!!.phase == "prepare" -> {
                PpProgress(progress.pct / 200f)
                Text(s.preparingChunk(progress.chunk.coerceAtLeast(1), progress.of.coerceAtLeast(1), progress.pct), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            }
            running && progress!!.waiting -> {
                PpProgress(0.5f)
                Text(s.waitingConnection, style = MaterialTheme.typography.bodyMedium, color = c.warning)
            }
            running -> {
                PpProgress(0.5f + progress!!.pct / 200f)
                Text(s.sendingPct(progress.pct), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            }
            clip.status == ClipStatus.UPLOADED -> Text("${s.sentOk} · ${s.queuedForAnalysis}", style = MaterialTheme.typography.bodyMedium, color = c.positive)
            clip.status == ClipStatus.SEGMENTING -> {
                PpProgress(null, spark = true)
                Text(s.analyzing, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
            }
            clip.status == ClipStatus.READY -> {
                Text(s.analysisReady, style = MaterialTheme.typography.bodyMedium, color = c.positive)
                SparkButton(s.viewAnalysis, { vm.push(Screen.Analysis(clip.id)) }, Modifier.fillMaxWidth().padding(top = 8.dp), icon = Icons.Rounded.AutoAwesome)
            }
            clip.status == ClipStatus.FAILED -> {
                Text(if (clip.remoteId != 0 && clip.error.isNotBlank() && clip.error !in errorCodes) s.analysisFailed else s.statusFailed, style = MaterialTheme.typography.bodyMedium, color = c.negative)
                if (clip.error.isNotBlank()) Text(errorText(clip.error, s), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            }
        }
        val canSend = !running && clip.status !in listOf(ClipStatus.UPLOADED, ClipStatus.SEGMENTING, ClipStatus.READY)
        if (canSend && clip.pitchShare in 0.0..0.4999) {
            Text(s.fewPitchWarning, style = MaterialTheme.typography.bodyMedium, color = c.warning, modifier = Modifier.padding(top = 8.dp))
        }
        if (canSend) {
            if (vm.hasServer && vm.settings.consentVersion.isNotEmpty()) {
                Text(s.consentLine, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, modifier = Modifier.padding(top = 8.dp))
            }
            if (!vm.hasServer) Text(s.sendNoServer, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, modifier = Modifier.padding(top = 8.dp))
            PrimaryButton(if (clip.status == ClipStatus.FAILED) s.retry else s.sendForAnalysis, { vm.send(clip.id) }, Modifier.fillMaxWidth().padding(top = 8.dp), icon = Icons.AutoMirrored.Rounded.Send)
        }
        // Without a server, the chunks are shared once they are ready.
        val dir = vm.proxyDir(clip)
        if (!vm.hasServer && !running && clip.chunkCount > 0 && dir != null && File(dir, ProxyPaths.INDEX).exists()) {
            PpTextButton(s.share, { shareChunks(context, dir) })
        }
    }
}

/** "Compute the pitch": the switch, the answer, "Change answer" and the coach's points. Locked once sent. */
@Composable
private fun PitchCard(vm: CoachViewModel, clip: Clip, locked: Boolean, s: Strings) {
    val c = PpTheme.colors
    val on = clip.pitchMode != PitchMode.OFF
    PpCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.computePitch, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                Text(
                    when (clip.pitchMode) {
                        PitchMode.LINES -> s.pitchAnswerLines
                        PitchMode.NO_LINES -> s.pitchAnswerNoLines
                        PitchMode.OFF -> s.pitchOff
                        else -> s.pitchAnswerAsk
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSecondary,
                )
            }
            Switch(on, { vm.setPitchEnabled(clip.id, it) }, enabled = !locked, colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
        }
        // Still or moving camera: one homography for the whole video, or carried frame by frame.
        // Once sent it shows what was sent (a clip sent before this answer went as moving).
        val static = if (locked) clip.camera == CameraMode.STATIC else vm.library.collectAsState().value.cameraOf(clip) == CameraMode.STATIC
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Checkbox(static, { vm.setCameraStatic(clip.id, it) }, enabled = !locked, colors = CheckboxDefaults.colors(checkedColor = c.accent))
            Column(Modifier.weight(1f)) {
                Text(s.cameraStatic, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                Text(if (static) s.cameraStaticHelp else s.cameraMovingHelp, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            }
        }
        // With a still camera the coach can mark the play area: what is outside it is not analyzed.
        if (static) {
            val area = clip.playArea
            Text(
                if (area != null) s.playAreaSummary(area.points.size, kotlin.math.round(area.margin * 100).toInt()) else s.playAreaNone,
                style = MaterialTheme.typography.bodyMedium, color = if (area != null) c.textPrimary else c.textSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (!locked) PpTextButton(s.markPlayArea, { vm.push(Screen.PlayArea(clip.id)) })
        }
        if (on) {
            val n = clip.touches?.points?.size ?: 0
            val lines = clip.touches?.lines?.size ?: 0
            val refs = vm.library.collectAsState().value.match(clip.matchId)?.references.orEmpty()
            if (n > 0 || lines > 0) Text(s.marksSummary(n, lines), style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
            if (refs.isNotEmpty()) Text(s.referencesSummary(refs.size, refs.sumOf { it.strokes.size }), style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
            if (clip.pitchMode == PitchMode.NO_LINES && n == 0 && lines == 0) Text(s.noLinesNoPoints, style = MaterialTheme.typography.bodyMedium, color = c.warning)
            if (!locked) {
                Row {
                    PpTextButton(s.markPitchPoints, { vm.push(Screen.PitchTouches(clip.id)) })
                    PpTextButton(s.changeAnswer, { vm.pitchFor = clip.id to false })
                }
                PpTextButton(s.referenceImages, { vm.push(Screen.PitchReferences(clip.matchId, clip.id)) })
            }
        }
    }
}

private val errorCodes = setOf(UploadWorker.ERR_SESSION, UploadWorker.ERR_LOGIN, UploadWorker.ERR_WITHDRAWN, UploadWorker.ERR_TERMS, UploadWorker.ERR_TOO_LARGE)

fun errorText(code: String, s: Strings): String = when (code) {
    UploadWorker.ERR_SESSION -> s.sessionExpired
    UploadWorker.ERR_LOGIN -> s.loginFirst
    UploadWorker.ERR_WITHDRAWN -> s.consentWithdrawnSend
    UploadWorker.ERR_TERMS -> s.termsChanged
    UploadWorker.ERR_TOO_LARGE -> s.partTooLarge
    else -> code
}

@Composable
private fun TechnicalCard(vm: CoachViewModel, clip: Clip, s: Strings) {
    val dir = vm.proxyDir(clip)
    val spec = ProxySpec.of(vm.settings.resolution, clip.width, clip.height)
    val profile = ChunkProfile.forSpec(vm.settings.profile, spec)
    val planned = Chunks.plan(clip.durationMs, profile)
    val frames = planned.sumOf { (it.durationMs / 100).toInt() }
    val bytes = dir?.listFiles()?.filter { it.name.endsWith(".mp4") }?.sumOf { it.length() } ?: 0
    PpCard {
        SectionTitle(s.chunkProfile)
        KeyValue(if (profile.name == "demo") s.profileDemo else s.profileDev, s.overlapLabel(profile.overlapMs / 1000))
        Text(s.chunksSummary(if (clip.chunkCount > 0) clip.chunkCount else planned.size, profile.chunkSeconds, formatMb(bytes), frames), style = MaterialTheme.typography.bodyMedium, color = PpTheme.colors.textPrimary)
        Text(s.samCost("%.2f".format(Chunks.samCostUsd(frames))), style = MaterialTheme.typography.bodyMedium, color = PpTheme.colors.textSecondary)
        Text(s.proxySpec(spec.maxHeight, "%.1f".format(spec.bitrate / 1e6), "%.0f".format(spec.mbPerMinute)), style = MaterialTheme.typography.bodyMedium, color = PpTheme.colors.textSecondary)
        if (clip.remoteId != 0) KeyValue("remote id", clip.remoteId.toString())
        if (clip.chunkCount > 0) PpTextButton(s.deleteCopy, { vm.deleteProxy(clip); vm.toast(s.copyDeleted) }, color = PpTheme.colors.negative)
    }
}

private fun share(context: android.content.Context, file: File, type: String) {
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val intent = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, null))
}

private fun shareChunks(context: android.content.Context, dir: File) {
    val files = dir.listFiles()?.filter { it.name.endsWith(".mp4") || it.name.endsWith(".yaml") }?.sortedBy { it.name } ?: return
    val uris = ArrayList(files.map { FileProvider.getUriForFile(context, context.packageName + ".files", it) })
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, null))
}

/** Android 10+: copies the video to Movies/pp-coach-tracker in the gallery. */
private fun exportToGallery(context: android.content.Context, file: File, clip: Clip): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !file.exists()) return false
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, "pp-coach-tracker_${clip.fileName}")
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/pp-coach-tracker")
    }
    val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    return try {
        context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } != null
    } catch (e: Exception) {
        false
    }
}

