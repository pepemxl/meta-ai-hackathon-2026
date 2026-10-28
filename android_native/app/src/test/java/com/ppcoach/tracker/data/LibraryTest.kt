package com.ppcoach.tracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LibraryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun sample(): Library {
        var lib = Library()
        lib = lib.addMatch { Match(it, "Tigres", "Halcones", JerseyColor.RED, JerseyColor.LIGHT_BLUE, "2026-10-25", "Cancha 3", createdAt = 1) }.first
        lib = lib.addClip { Clip(it, 1, "Jugada 1", ClipKind.HALF, ClipSource.IMPORT, 2, 75_000, 1920, 1080, 29.97, 12345, "nota", true, ClipStatus.READY, StorageKind.SD, 42, 60, 2000, 720, 2, "") }.first
        return lib
    }

    @Test
    fun roundTrip() {
        val lib = sample()
        assertEquals(lib, LibraryCodec.decode(LibraryCodec.encode(lib)))
    }

    @Test
    fun unknownFieldsAndValuesFallBackToDefaults() {
        val text = """
            {"version": 9, "future": true, "matches": [{"id": 3, "home": "A", "away": "B", "homeColor": "magenta", "date": "2026-01-01", "x": 1}],
             "clips": [{"id": 5, "matchId": 3, "kind": "penalty", "status": "teleported", "storage": "cloud", "fps": "abc"}, {"id": 0, "matchId": 3}]}
        """.trimIndent()
        val lib = LibraryCodec.decode(text)
        assertEquals(JerseyColor.RED, lib.matches[0].homeColor)
        assertEquals(JerseyColor.BLUE, lib.matches[0].awayColor)
        val c = lib.clips.single()
        assertEquals(ClipKind.PLAY, c.kind)
        assertEquals(ClipStatus.LOCAL, c.status)
        assertEquals(StorageKind.PHONE, c.storage)
        assertEquals(0.0, c.fps, 0.0)
        // Counters never reuse ids.
        assertEquals(4, lib.nextMatchId)
        assertEquals(6, lib.nextClipId)
        assertEquals("clip_0005.mp4", c.fileName)
        assertEquals("match_0003", lib.matches[0].folder)
    }

    @Test
    fun corruptFileIsSetAsideAndTheAppStartsEmpty() {
        val dir = tmp.newFolder()
        File(dir, "library.json").writeText("{not json")
        val store = LibraryStore(dir)
        assertEquals(Library(), store.load())
        assertTrue(File(dir, "library.json.bad").exists())
        store.save(sample())
        store.flush()
        assertEquals(sample(), LibraryStore(dir).load())
        assertFalse(File(dir, "library.json.tmp").exists())
    }

    @Test
    fun clipKindFollowsTheDuration() {
        assertEquals(ClipKind.PLAY, ClipKind.forDuration(4 * 60_000L))
        assertEquals(ClipKind.HALF, ClipKind.forDuration(5 * 60_000L))
        assertEquals(ClipKind.FULL, ClipKind.forDuration(70 * 60_000L))
    }

    @Test
    fun storagePathsAndMove() {
        val phone = tmp.newFolder("phone")
        val sd = tmp.newFolder("sd")
        val st = StorageLocations(mapOf(StorageKind.PHONE to phone, StorageKind.PRIVATE to null, StorageKind.SD to sd))
        assertEquals(listOf(StorageKind.PHONE, StorageKind.SD), st.available())
        val lib = sample()
        val match = lib.matches[0]
        val clip = lib.clips[0].copy(storage = StorageKind.PHONE)
        val f = st.videoFile(match, clip)!!
        assertEquals(File(phone, "Movies/match_0001/clip_0001.mp4"), f)
        assertNull(st.videoFile(StorageKind.PRIVATE, match, clip))
        f.parentFile!!.mkdirs()
        f.writeBytes(ByteArray(1000) { it.toByte() })
        assertTrue(st.move(match, clip, StorageKind.SD))
        assertFalse(f.exists())
        assertEquals(1000, File(sd, "Movies/match_0001/clip_0001.mp4").length())
        // Nothing to move: the original is not there.
        assertFalse(st.move(match, clip, StorageKind.SD))
    }
}
