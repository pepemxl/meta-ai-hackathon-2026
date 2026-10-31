package com.ppcoach.tracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunksTest {
    @Test
    fun devProfileCutsWithTwoSecondsOfOverlap() {
        assertEquals(listOf(Cut(0, 60_000), Cut(58_000, 75_000)), Chunks.plan(75_000, ChunkProfile.DEV))
        assertEquals(listOf(Cut(0, 60_000), Cut(58_000, 120_000), Cut(118_000, 150_000)), Chunks.plan(150_000, ChunkProfile.DEV))
    }

    @Test
    fun demoProfileCutsFiveMinutes() {
        assertEquals(listOf(Cut(0, 300_000), Cut(297_000, 492_012)), Chunks.plan(492_012, ChunkProfile.DEMO))
        val five = ChunkProfile.of("demo", 5000)
        assertEquals(listOf(Cut(0, 300_000), Cut(295_000, 600_000), Cut(595_000, 700_000)), Chunks.plan(700_000, five))
    }

    @Test
    fun shortLastPieceJoinsThePreviousChunk() {
        // 5:04 in demo: 4 s after the cut < 2·3 s → one chunk of 5:04.
        assertEquals(listOf(Cut(0, 304_000)), Chunks.plan(304_000, ChunkProfile.DEMO))
        // 1:03 in dev: 3 s < 2·2 s → one chunk.
        assertEquals(listOf(Cut(0, 63_000)), Chunks.plan(63_000, ChunkProfile.DEV))
        // A short clip is one chunk.
        assertEquals(listOf(Cut(0, 12_000)), Chunks.plan(12_000, ChunkProfile.DEMO))
    }

    @Test
    fun invalidProfilesAreRejected() {
        assertThrows { ChunkProfile("x", 30, 2000) }
        assertThrows { ChunkProfile("x", 60, 1000) }
        assertThrows { ChunkProfile("x", 301, 2000) }
        assertEquals(5000, ChunkProfile.of("demo", 9000).overlapMs)
        assertEquals(ChunkProfile.DEV, ChunkProfile.of("unknown"))
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }

    @Test
    fun frameMapPairsTheNearestOriginalFrame() {
        val out = listOf(297_000L, 297_100L, 297_200L)
        assertEquals(listOf(FrameRow(0, 8901, 297_000), FrameRow(1, 8904, 297_100), FrameRow(2, 8907, 297_200)), Chunks.frameMap(out, 29.97))
        // Variable frame rate: the original's own timestamps decide.
        val vfr = listOf(0L, 30L, 70L, 95L, 140L, 210L)
        assertEquals(listOf(0, 3, 4), Chunks.frameMap(listOf(0L, 100L, 160L), 30.0, vfr).map { it.sourceFrame })
    }

    @Test
    fun alignmentAndOverlapFrames() {
        val prev = (0 until 600).map { FrameRow(it, it * 3, it * 100L + 3) }
        assertEquals(58_003L, Chunks.alignedStart(prev, 58_000))
        val next = (0 until 30).map { FrameRow(it, 0, 58_003L + it * 100) }
        assertEquals(20, Chunks.overlapFrames(next, prev.last().ms + 1))
        assertEquals(0, Chunks.overlapFrames(next, null))
    }

    @Test
    fun yamlIsWrittenAndReadBack() {
        val map = (0 until 5).map { FrameRow(it, 8901 + it * 3, 297_000L + it * 100) }
        val meta = ChunkMeta(2, 2, "video_part_002.mp4", 300, 297_000, 492_012, 3000, 1280, 720, 1_500_000, map, 3, "a".repeat(64))
        val text = Chunks.chunkYaml(ClipInfo(3, 1, "match_0001/clip_0003.mp4", 29.97, 14745, 492_012), meta)
        assertTrue(text.startsWith("%YAML:1.0\n---\nversion: 1\n"))
        assertTrue(text.contains("  source_fps: 29.97\n"))
        assertTrue(text.contains("    - [1, 8904, 297100]\n"))
        assertTrue(text.contains("  overlap_frames: 3\n"))
        assertTrue(text.trimEnd().endsWith("sha256: \"${"a".repeat(64)}\""))
        val back = Chunks.parseChunkYaml(text)
        assertNotNull(back)
        assertEquals(map, back!!.map)
        assertEquals(297_000L, back.startMs)
        assertEquals(492_012L, back.endMs)
        assertEquals(720, back.height)
        // A YAML cut in the middle (the app died while writing) is not valid.
        assertNull(Chunks.parseChunkYaml(text.substring(0, text.length / 2)))

        val index = Chunks.indexYaml(3, ChunkProfile.DEMO, 2, listOf(Chunks.IndexRow(1, "video_part_001.mp4", 0, 300_000, 3000)))
        assertTrue(index.contains("profile: \"demo\"\nchunk_seconds: 300\noverlap_ms: 3000\ncount: 2\ncomplete: 0\n"))
        assertTrue(index.contains("  - { number: 1, file: \"video_part_001.mp4\", start_ms: 0, end_ms: 300000, frames: 3000 }\n"))
    }

    @Test
    fun samCost() {
        // 8-min demo clip: 4,830 frames with overlap × 3 concepts ≈ US$2.90.
        assertEquals(2.898, Chunks.samCostUsd(4830), 0.001)
        assertEquals(0.24, Chunks.samCostUsd(1200, prompts = 1), 0.001)
    }

    @Test
    fun proxySizeKeepsTheAspectRatio() {
        assertEquals(1280 to 720, ProxySpec.outputSize(1920, 1080, 720))
        assertEquals(1920 to 1080, ProxySpec.outputSize(1920, 1080, 1080))
        assertEquals(640 to 360, ProxySpec.outputSize(640, 360, 720))
        assertEquals(11.25, ProxySpec.STANDARD.mbPerMinute, 0.01)
        assertEquals(22.5, ProxySpec.HIGH.mbPerMinute, 0.01)
        // The original: its own size up to 4K, at 1080p's bits per pixel, between 1.5 and 12 Mbps.
        assertEquals(ProxySpec(2160, 12_000_000), ProxySpec.of(ProxyResolution.ORIGINAL, 3840, 2160))
        assertEquals(ProxySpec(2160, 12_000_000), ProxySpec.of(ProxyResolution.ORIGINAL, 7680, 4320))
        assertEquals(ProxySpec(800, 4_444_444), ProxySpec.of(ProxyResolution.ORIGINAL, 3840, 800))
        assertEquals(ProxySpec(360, 1_500_000), ProxySpec.of(ProxyResolution.ORIGINAL, 640, 360))
        assertEquals(ProxySpec.HIGH, ProxySpec.of(ProxyResolution.ORIGINAL, 0, 0))
        assertEquals(ProxySpec.HIGH, ProxySpec.of(ProxyResolution.HIGH, 3840, 2160))
        assertEquals(ProxySpec.STANDARD, ProxySpec.of("anything", 3840, 2160))
        // Chunks short enough for the API's 160 MB: 4K in 1-minute chunks, a panorama in 4, 720p untouched.
        assertEquals(60, ChunkProfile.forSpec(ChunkProfile.DEMO, ProxySpec.of(ProxyResolution.ORIGINAL, 3840, 2160)).chunkSeconds)
        assertEquals(240, ChunkProfile.forSpec(ChunkProfile.DEMO, ProxySpec.of(ProxyResolution.ORIGINAL, 3840, 800)).chunkSeconds)
        assertEquals(300, ChunkProfile.forSpec(ChunkProfile.DEMO, ProxySpec.HIGH).chunkSeconds)
        assertEquals(60, ChunkProfile.forSpec(ChunkProfile.DEV, ProxySpec.STANDARD).chunkSeconds)
    }
}
