package com.ppcoach.tracker

import android.content.Context
import android.os.Environment
import com.ppcoach.tracker.data.Library
import com.ppcoach.tracker.data.LibraryStore
import com.ppcoach.tracker.data.SettingsStore
import com.ppcoach.tracker.data.StorageKind
import com.ppcoach.tracker.data.StorageLocations
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * One library for the whole process: the ViewModel and the WorkManager
 * workers change it through [commit], so their writes never race. Every
 * change is in memory at once and written to library.json in order.
 */
object AppGraph {
    private lateinit var store: LibraryStore
    lateinit var settings: SettingsStore
        private set
    private val state = MutableStateFlow(Library())
    val library: StateFlow<Library> get() = state.asStateFlow()
    private var ready = false

    @Synchronized
    fun init(context: Context) {
        if (ready) return
        val app = context.applicationContext
        store = LibraryStore(app.filesDir)
        settings = SettingsStore(app, BuildConfig.DEFAULT_CHUNK_PROFILE)
        state.value = store.load()
        ready = true
    }

    @Synchronized
    fun commit(change: (Library) -> Library): Library {
        val next = change(state.value)
        state.value = next
        store.save(next)
        return next
    }

    fun storage(context: Context): StorageLocations {
        val app = context.applicationContext
        // A removable volume after the primary one is the SD card.
        val sd = app.getExternalFilesDirs(null).drop(1).firstOrNull { it != null && Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
        return StorageLocations(
            mapOf(
                StorageKind.PHONE to app.getExternalFilesDir(null),
                StorageKind.PRIVATE to app.filesDir,
                StorageKind.SD to sd,
            ),
        )
    }

    fun proxyDir(context: Context, relative: String): File = File(context.applicationContext.cacheDir, relative)
}
