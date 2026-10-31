package com.ppcoach.tracker.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.ppcoach.tracker.data.ClipSource
import com.ppcoach.tracker.data.ProxyResolution
import com.ppcoach.tracker.ui.CoachViewModel
import com.ppcoach.tracker.ui.components.PrimaryButton
import com.ppcoach.tracker.ui.i18n.LocalStrings
import com.ppcoach.tracker.ui.theme.PpTheme
import kotlinx.coroutines.delay

/**
 * Landscape, full screen, always dark, screen kept on. Each tap of the red
 * button is one clip; the screen stays open for the next play.
 */
@SuppressLint("MissingPermission")
@Composable
fun RecordScreen(vm: CoachViewModel, matchId: Int) {
    val s = LocalStrings.current
    val c = PpTheme.colors
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var hasCamera by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> hasCamera = r[Manifest.permission.CAMERA] == true }

    DisposableEffect(Unit) {
        val activity = context as? Activity
        val old = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.requestedOrientation = old ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!hasCamera) {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.cameraPermission, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                PrimaryButton(s.grantPermission, { permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }, Modifier.padding(top = 16.dp))
            }
        } else {
            val capture = remember {
                // With "Original" the analysis keeps the recorded size: 4K when the phone can.
                val qualities = if (vm.settings.resolution == ProxyResolution.ORIGINAL) listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD) else listOf(Quality.FHD, Quality.HD, Quality.SD)
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.fromOrderedList(qualities, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                    .build()
                VideoCapture.withOutput(recorder)
            }
            var recording by remember { mutableStateOf<Recording?>(null) }
            var startedAt by remember { mutableLongStateOf(0L) }
            var elapsed by remember { mutableLongStateOf(0L) }
            AndroidView(factory = { ctx ->
                PreviewView(ctx).also { view ->
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        val provider = providerFuture.get()
                        val preview = androidx.camera.core.Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                        provider.unbindAll()
                        try {
                            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                        } catch (e: Exception) {
                            vm.toast(s.recordFailed)
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                }
            }, modifier = Modifier.fillMaxSize())
            DisposableEffect(Unit) {
                onDispose {
                    recording?.stop()
                    runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
                }
            }
            if (recording != null) {
                LaunchedEffect(startedAt) {
                    while (true) {
                        elapsed = System.currentTimeMillis() - startedAt
                        delay(250)
                    }
                }
                Text(
                    "● ${s.recording} ${formatDuration(elapsed)}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.TopCenter).padding(16.dp).background(c.record, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            } else {
                Text(s.recordHint, color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.TopCenter).padding(16.dp))
            }
            IconButton(onClick = { vm.back() }, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, s.back, tint = Color.White)
            }
            // 72 dp record button.
            Box(
                Modifier.align(Alignment.CenterEnd).padding(24.dp).size(72.dp).border(4.dp, Color.White, CircleShape).padding(6.dp)
                    .background(c.record, if (recording != null) RoundedCornerShape(8.dp) else CircleShape)
                    .clickable {
                        val current = recording
                        if (current != null) {
                            current.stop()
                            recording = null
                        } else {
                            val (clip, file) = vm.newClipFile(matchId) ?: return@clickable
                            val pending = capture.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
                            val prepared = if (hasAudio) pending.withAudioEnabled() else pending
                            startedAt = System.currentTimeMillis()
                            recording = prepared.start(ContextCompat.getMainExecutor(context)) { event ->
                                if (event is VideoRecordEvent.Finalize) {
                                    if (event.hasError() && event.error != VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) {
                                        file.delete()
                                        vm.toast(s.recordFailed)
                                    } else {
                                        vm.addClip(matchId, clip.id, file, ClipSource.CAMERA, clip.storage)
                                        vm.toast(s.clipSaved(file.name))
                                    }
                                }
                            }
                        }
                    },
            )
        }
    }
}
