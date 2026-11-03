package com.ppcoach.tracker.data

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoRotationTest {
    private val one = 0x10000

    private fun box(type: String, body: ByteArray, large: Boolean = false): ByteArray = ByteArrayOutputStream().also { b ->
        DataOutputStream(b).apply {
            if (large) {
                writeInt(1); writeBytes(type); writeLong(16L + body.size)
            } else {
                writeInt(8 + body.size); writeBytes(type)
            }
            write(body)
        }
    }.toByteArray()

    private fun tkhd(version: Int, matrix: IntArray, trackId: Int): ByteArray = ByteArrayOutputStream().also { b ->
        DataOutputStream(b).apply {
            writeInt(version shl 24 or 7)
            if (version == 1) { writeLong(0); writeLong(0); writeInt(trackId); writeInt(0); writeLong(6000) }
            else { writeInt(0); writeInt(0); writeInt(trackId); writeInt(0); writeInt(6000) }
            writeLong(0); writeShort(0); writeShort(0); writeShort(0); writeShort(0)
            matrix.forEach { writeInt(it) }
            writeInt(1920 shl 16); writeInt(1080 shl 16)
        }
    }.toByteArray().let { box("tkhd", it) }

    private fun trak(handler: String, version: Int, matrix: IntArray, id: Int): ByteArray {
        val hdlr = box("hdlr", ByteArray(4) + ByteArray(4) + handler.toByteArray() + ByteArray(12) + byteArrayOf(0))
        return box("trak", tkhd(version, matrix, id) + box("mdia", box("mdhd", ByteArray(24)) + hdlr))
    }

    private fun matrix(a: Int, b: Int, c: Int, d: Int) = intArrayOf(a, b, 0, c, d, 0, 0, 0, 0x40000000)
    private val identity = matrix(one, 0, 0, one)

    /** ftyp, the media first (as a phone writes it, with a 64-bit size), then moov with sound before video. */
    private fun mp4(videoMatrix: IntArray = identity, version: Int = 0, soundMatrix: IntArray = identity): File {
        val bytes = box("ftyp", "isom".toByteArray() + ByteArray(4)) + box("mdat", ByteArray(64) { it.toByte() }, large = true) +
            box("moov", box("mvhd", ByteArray(100)) + trak("soun", 0, soundMatrix, 1) + trak("vide", version, videoMatrix, 2))
        return File.createTempFile("clip", ".mp4").apply { deleteOnExit(); writeBytes(bytes) }
    }

    private fun matrixOf(file: File, track: Int): IntArray {
        val bytes = file.readBytes()
        // The n-th tkhd (1 = sound, 2 = video), version 0: the matrix 40 bytes after its header.
        var at = -1
        repeat(track) { at = String(bytes, Charsets.ISO_8859_1).indexOf("tkhd", at + 1) }
        val start = at + 4 + if (bytes[at + 4].toInt() == 1) 52 else 40
        return IntArray(9) { i -> java.nio.ByteBuffer.wrap(bytes, start + 4 * i, 4).int }
    }

    @Test
    fun turnsTheVideoTrackAQuarterEachWayInPlace() {
        val f = mp4()
        val size = f.length()
        assertEquals(0, VideoRotation.degrees(f))
        assertEquals(90, VideoRotation.rotate(f, 1))
        // The same matrix as MediaMuxer.setOrientationHint(90), and the sound track is left as it was.
        assertArrayEquals(matrix(0, one, -one, 0), matrixOf(f, 2))
        assertArrayEquals(identity, matrixOf(f, 1))
        assertEquals(size, f.length())
        assertEquals(180, VideoRotation.rotate(f, 1))
        assertEquals(90, VideoRotation.rotate(f, -1))
        assertEquals(0, VideoRotation.rotate(f, -1))
        assertEquals(270, VideoRotation.rotate(f, -1))
        assertEquals(270, VideoRotation.degrees(f))
    }

    @Test
    fun readsTheRotationAPhoneWroteAndTheLongHeader() {
        assertEquals(90, VideoRotation.degrees(mp4(matrix(0, one, -one, 0))))
        val v1 = mp4(matrix(-one, 0, 0, -one), version = 1)
        assertEquals(180, VideoRotation.degrees(v1))
        assertEquals(270, VideoRotation.rotate(v1, 1))
        assertArrayEquals(matrix(0, -one, one, 0), matrixOf(v1, 2))
    }

    @Test
    fun leavesAloneWhatIsNotARotationOrNotAnMp4() {
        val mirrored = mp4(matrix(-one, 0, 0, one))
        val before = mirrored.readBytes()
        assertNull(VideoRotation.rotate(mirrored, 1))
        assertArrayEquals(before, mirrored.readBytes())
        val text = File.createTempFile("clip", ".mp4").apply { deleteOnExit(); writeText("not a video at all") }
        assertNull(VideoRotation.rotate(text, 1))
        assertNull(VideoRotation.degrees(File("does-not-exist.mp4")))
    }

    @Test
    fun theMarksTurnWithTheFrame() {
        // Turned right, the top left corner goes to the top right; turned left, to the bottom left.
        assertEquals(1.0 to 0.0, VideoRotation.point(0.0, 0.0, 1))
        assertEquals(0.0 to 1.0, VideoRotation.point(0.0, 0.0, -1))
        assertEquals(0.75 to 0.75, VideoRotation.point(0.25, 0.25, 2))
        val touches = PitchTouches(points = listOf(Touch("center", 0.3, 0.6)), lines = listOf(PaintedLine("halfway", listOf(0.1 to 0.2, 0.9 to 0.2))))
        val right = touches.rotated(1)
        assertEquals(0.4, right.points.single().x, 1e-9)
        assertEquals(0.3, right.points.single().y, 1e-9)
        assertEquals("center", right.points.single().name)
        right.lines.single().points.zip(listOf(0.8 to 0.1, 0.8 to 0.9)).forEach { (got, want) ->
            assertEquals(want.first, got.first, 1e-9)
            assertEquals(want.second, got.second, 1e-9)
        }
        val back = right.rotated(-1).points.single()
        assertEquals(0.3, back.x, 1e-9)
        assertEquals(0.6, back.y, 1e-9)
        val area = PlayArea(listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0))
        assertEquals(area.points, area.rotated(1).rotated(1).rotated(1).rotated(1).points)
        assertEquals(listOf(1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0, 0.0 to 0.0), area.rotated(1).points)
    }
}
