package com.ppcoach.tracker.data

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Turns a video a quarter at a time by rewriting the rotation of its video
 * track (the matrix of `tkhd` in MP4/MOV), in place: no re-encoding, the size
 * does not change and the player, the thumbnail and the analysis copy follow it.
 */
object VideoRotation {
    private const val ONE = 0x10000

    /** The rotation in degrees (0, 90, 180, 270) of [file]'s video track, or null when it cannot be read. */
    fun degrees(file: File): Int? = try {
        RandomAccessFile(file, "r").use { raf -> matrixOffset(raf)?.let { readDegrees(raf, it) } }
    } catch (e: IOException) {
        null
    }

    /** Turns [file] [quarters] quarters clockwise (negative: counterclockwise); the new degrees, or null if it could not. */
    fun rotate(file: File, quarters: Int): Int? = try {
        RandomAccessFile(file, "rw").use { raf ->
            val at = matrixOffset(raf) ?: return null
            val old = readDegrees(raf, at) ?: return null
            val new = Math.floorMod(old + 90 * quarters, 360)
            writeDegrees(raf, at, new)
            new
        }
    } catch (e: IOException) {
        null
    }

    private class Box(val type: String, val start: Long, val content: Long, val end: Long)

    private fun boxes(raf: RandomAccessFile, from: Long, to: Long): List<Box> {
        val out = mutableListOf<Box>()
        var at = from
        while (at + 8 <= to) {
            raf.seek(at)
            var size = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = String(ByteArray(4).also { raf.readFully(it) }, Charsets.ISO_8859_1)
            var header = 8L
            if (size == 1L) {
                size = raf.readLong()
                header = 16
            } else if (size == 0L) {
                size = to - at
            }
            if (size < header || at + size > to) break
            out += Box(type, at, at + header, at + size)
            at += size
        }
        return out
    }

    private fun child(raf: RandomAccessFile, box: Box, type: String): Box? = boxes(raf, box.content, box.end).firstOrNull { it.type == type }

    /** Where the matrix of the video track's `tkhd` starts, or null. */
    private fun matrixOffset(raf: RandomAccessFile): Long? {
        val moov = boxes(raf, 0, raf.length()).firstOrNull { it.type == "moov" } ?: return null
        for (trak in boxes(raf, moov.content, moov.end).filter { it.type == "trak" }) {
            val hdlr = child(raf, trak, "mdia")?.let { child(raf, it, "hdlr") } ?: continue
            raf.seek(hdlr.content + 8) // version and flags, pre_defined
            if (String(ByteArray(4).also { raf.readFully(it) }, Charsets.ISO_8859_1) != "vide") continue
            val tkhd = child(raf, trak, "tkhd") ?: return null
            raf.seek(tkhd.content)
            // version, flags, times, track id, reserved, duration; then reserved, layer, group, volume, reserved.
            val at = tkhd.content + if (raf.readUnsignedByte() == 1) 52 else 40
            return if (at + 36 <= tkhd.end) at else null
        }
        return null
    }

    /** The matrix as a rotation; null when it is something else (a mirror, a scale). */
    private fun readDegrees(raf: RandomAccessFile, at: Long): Int? {
        raf.seek(at)
        val m = IntArray(9) { raf.readInt() }
        return when (listOf(m[0], m[1], m[3], m[4])) {
            listOf(ONE, 0, 0, ONE) -> 0
            listOf(0, ONE, -ONE, 0) -> 90
            listOf(-ONE, 0, 0, -ONE) -> 180
            listOf(0, -ONE, ONE, 0) -> 270
            else -> null
        }
    }

    /** The same matrix Android's MediaMuxer writes for that rotation (setOrientationHint). */
    private fun writeDegrees(raf: RandomAccessFile, at: Long, degrees: Int) {
        val (a, b, c, d) = when (degrees) {
            90 -> listOf(0, ONE, -ONE, 0)
            180 -> listOf(-ONE, 0, 0, -ONE)
            270 -> listOf(0, -ONE, ONE, 0)
            else -> listOf(ONE, 0, 0, ONE)
        }
        raf.seek(at)
        val m = IntArray(9) { raf.readInt() }
        // u, v and w (the projection column) stay; the translation goes, as the rotation has none.
        raf.seek(at)
        for (x in intArrayOf(a, b, m[2], c, d, m[5], 0, 0, m[8])) raf.writeInt(x)
    }

    /** A normalized point (0..1) of the frame after turning it [quarters] quarters clockwise. */
    fun point(x: Double, y: Double, quarters: Int): Pair<Double, Double> = when (Math.floorMod(quarters, 4)) {
        1 -> (1 - y) to x
        2 -> (1 - x) to (1 - y)
        3 -> y to (1 - x)
        else -> x to y
    }
}

/** The coach's marks on the frame turned [quarters] quarters clockwise. */
fun PitchTouches.rotated(quarters: Int): PitchTouches = copy(
    points = points.map { t -> VideoRotation.point(t.x, t.y, quarters).let { (x, y) -> t.copy(x = x, y = y) } },
    lines = lines.map { l -> l.copy(points = l.points.map { (x, y) -> VideoRotation.point(x, y, quarters) }) },
)

/** The play area on the frame turned [quarters] quarters clockwise. */
fun PlayArea.rotated(quarters: Int): PlayArea = copy(points = points.map { (x, y) -> VideoRotation.point(x, y, quarters) })
