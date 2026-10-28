package com.ppcoach.tracker.analysis

import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.ppcoach.tracker.data.PitchMask
import java.io.File

/**
 * The pitch mask of every frame of a chunk: decodes it with MediaCodec to
 * buffers (no surface), reads from each YUV frame only the points of the
 * 160×90 grid, converts them to RGB (BT.601, limited range) and runs PitchMask.
 * The video's rotation is honored.
 */
object PitchSampler {
    private const val TIMEOUT_US = 10_000L

    /** Rows (chunk frame, grass fraction, polygon) in decode order. Throws if the chunk cannot be decoded. */
    fun sample(file: File, onProgress: (Int) -> Unit = {}): List<Triple<Int, Double, List<Double>>> {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(file.absolutePath)
            val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val total = if (format.containsKey("frame-count")) format.getInteger("frame-count") else 0
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            val rows = mutableListOf<Triple<Int, Double, List<Double>>>()
            var inputDone = false
            var frame = 0
            while (true) {
                if (!inputDone) {
                    val inIndex = c.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = c.getInputBuffer(inIndex)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            c.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIndex, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIndex = c.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        val image = c.getOutputImage(outIndex)
                        if (image != null) {
                            val m = PitchMask.fromGrid(grid(image, rotation))
                            rows += Triple(frame, m.fraction, m.polygon)
                            image.close()
                        }
                        frame++
                        if (total > 0) onProgress(minOf(100, frame * 100 / total))
                    }
                    c.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return rows
        } finally {
            codec?.let {
                runCatching { it.stop() }
                it.release()
            }
            ex.release()
        }
    }

    /** The RGB grid (rgb[y * GRID_W + x] = 0xRRGGBB) of a YUV_420_888 image, as displayed. */
    fun grid(image: Image, rotation: Int): IntArray {
        val crop = image.cropRect
        val stored = crop.width() to crop.height()
        // Displayed size: swapped for 90/270.
        val (dw, dh) = if (rotation % 180 == 0) stored else stored.second to stored.first
        val gx = PitchMask.gridX(dw)
        val gy = PitchMask.gridY(dh)
        val (yp, up, vp) = image.planes.let { Triple(it[0], it[1], it[2]) }
        val out = IntArray(PitchMask.GRID_W * PitchMask.GRID_H)
        for (j in 0 until PitchMask.GRID_H) for (i in 0 until PitchMask.GRID_W) {
            // Displayed (x, y) → stored pixel.
            val (sx, sy) = when ((rotation % 360 + 360) % 360) {
                90 -> gy[j] to (stored.second - 1 - gx[i])
                180 -> (stored.first - 1 - gx[i]) to (stored.second - 1 - gy[j])
                270 -> (stored.first - 1 - gy[j]) to gx[i]
                else -> gx[i] to gy[j]
            }
            val px = crop.left + sx
            val py = crop.top + sy
            val y = yp.buffer.get(py * yp.rowStride + px * yp.pixelStride).toInt() and 0xFF
            val u = up.buffer.get((py / 2) * up.rowStride + (px / 2) * up.pixelStride).toInt() and 0xFF
            val v = vp.buffer.get((py / 2) * vp.rowStride + (px / 2) * vp.pixelStride).toInt() and 0xFF
            out[j * PitchMask.GRID_W + i] = yuvToRgb(y, u, v)
        }
        return out
    }

    /** BT.601, limited range. */
    fun yuvToRgb(y: Int, u: Int, v: Int): Int {
        val c = 1.164 * (y - 16)
        val r = (c + 1.596 * (v - 128)).toInt().coerceIn(0, 255)
        val g = (c - 0.813 * (v - 128) - 0.391 * (u - 128)).toInt().coerceIn(0, 255)
        val b = (c + 2.018 * (u - 128)).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }
}
