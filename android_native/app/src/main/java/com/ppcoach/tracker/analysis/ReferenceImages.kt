package com.ppcoach.tracker.analysis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The reference pictures on disk: at most 1600 px on the long side, upright
 * (a camera app's photo comes rotated in its EXIF), JPEG; under the API's
 * 2 MB when uploaded.
 */
object ReferenceImages {
    const val MAX_SIDE = 1600
    private const val MAX_UPLOAD_BYTES = 1_900_000

    /** Rewrites the camera's photo in place: scaled, upright, JPEG 85. False if it cannot be read. */
    fun normalizePhoto(file: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val raw = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        val degrees = when (runCatching { ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(raw.width, raw.height))
        val m = Matrix().apply { postScale(scale, scale); postRotate(degrees) }
        val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        save(upright, file)
        if (upright !== raw) raw.recycle()
        upright.recycle()
        return true
    }

    fun save(bitmap: Bitmap, file: File, quality: Int = 85) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    /** The bytes to upload, recompressed if they pass the API's limit; null if the file is missing. */
    fun forUpload(file: File): ByteArray? {
        if (!file.isFile) return null
        val bytes = file.readBytes()
        if (bytes.size <= MAX_UPLOAD_BYTES) return bytes
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        var quality = 75
        while (true) {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= MAX_UPLOAD_BYTES || quality <= 35) return out.toByteArray().also { bmp.recycle() }
            quality -= 15
        }
    }
}
