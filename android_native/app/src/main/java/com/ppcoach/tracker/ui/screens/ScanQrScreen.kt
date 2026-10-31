package com.ppcoach.tracker.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.ppcoach.tracker.data.QrDecoder
import com.ppcoach.tracker.ui.CoachViewModel
import com.ppcoach.tracker.ui.components.PpTopBar
import com.ppcoach.tracker.ui.components.PrimaryButton
import com.ppcoach.tracker.ui.components.SecondaryButton
import com.ppcoach.tracker.ui.i18n.LocalStrings
import com.ppcoach.tracker.ui.theme.PpTheme
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scans the analysis system's setup QR with the back camera, or reads it from
 * a picture. The first QR found goes to CoachViewModel.onSetupQr, which checks
 * it and asks before logging in.
 */
@Composable
fun ScanQrScreen(vm: CoachViewModel) {
    val s = LocalStrings.current
    val c = PpTheme.colors
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var hasCamera by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    // One QR per visit: the analyzer keeps running for a frame or two after the first hit.
    val found = remember { AtomicBoolean(false) }
    val onText: (String) -> Unit = { text -> if (found.compareAndSet(false, true)) vm.onSetupQr(text) { found.set(false) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val text = readQr(context, uri)
            if (text == null) vm.toast(s.qrNotFound) else onText(text)
        }
    }
    Column(Modifier.fillMaxSize()) {
        PpTopBar(s.scanSetupQr, onBack = { vm.back() })
        Column(Modifier.padding(16.dp)) {
            Text(s.scanSetupQrHelp, style = MaterialTheme.typography.bodyLarge, color = c.textSecondary)
            Box(Modifier.padding(vertical = 16.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(c.textPrimary), contentAlignment = Alignment.Center) {
                if (hasCamera) {
                    val executor = remember { Executors.newSingleThreadExecutor() }
                    AndroidView(factory = { ctx ->
                        PreviewView(ctx).also { view ->
                            // A TextureView, so the preview stays inside the square (a SurfaceView draws over the rest).
                            view.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            view.scaleType = PreviewView.ScaleType.FILL_CENTER
                            val future = ProcessCameraProvider.getInstance(ctx)
                            future.addListener({
                                val provider = future.get()
                                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                                analysis.setAnalyzer(executor) { image -> analyze(image)?.let { text -> view.post { onText(text) } } }
                                provider.unbindAll()
                                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                            }, ContextCompat.getMainExecutor(ctx))
                        }
                    }, modifier = Modifier.fillMaxSize())
                    Box(Modifier.fillMaxWidth(0.7f).aspectRatio(1f).border(3.dp, c.onAccent, RoundedCornerShape(12.dp)))
                    DisposableEffect(Unit) {
                        onDispose {
                            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
                            executor.shutdown()
                        }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Text(s.cameraPermission, color = c.surface, style = MaterialTheme.typography.bodyLarge)
                        PrimaryButton(s.grantPermission, { permission.launch(Manifest.permission.CAMERA) }, Modifier.padding(top = 16.dp))
                    }
                }
            }
            SecondaryButton(s.qrFromImage, { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.PhotoLibrary)
            Text(s.qrSecretWarning, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

/** The camera frame's luminance (the Y plane) → the QR text, or null. */
private fun analyze(image: ImageProxy): String? = image.use {
    val plane = it.planes[0]
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining()).also { b -> buffer.get(b) }
    // rowStride may be wider than the image: the source reads the whole rows.
    val source = PlanarYUVLuminanceSource(data, plane.rowStride, it.height, 0, 0, it.width, it.height, false)
    QrDecoder.decode(source)
}

/** A picture from the gallery → the QR text, or null (scaled down so a 12 MP photo stays light). */
private fun readQr(context: android.content.Context, uri: Uri): String? = runCatching {
    val full = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longSide = maxOf(info.size.width, info.size.height)
        if (longSide > 1600) d.setTargetSampleSize(longSide / 1600 + 1)
    }
    val bmp = if (full.config == Bitmap.Config.ARGB_8888) full else full.copy(Bitmap.Config.ARGB_8888, false)
    val pixels = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
    QrDecoder.decode(RGBLuminanceSource(bmp.width, bmp.height, pixels))
}.getOrNull()
