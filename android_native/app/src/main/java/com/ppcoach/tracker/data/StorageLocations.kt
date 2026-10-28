package com.ppcoach.tracker.data

import java.io.File

/**
 * Where a clip's video lives: Movies/match_NNNN/clip_NNNN.mp4 under the root
 * of its storage location. The roots come from Android (getExternalFilesDir,
 * filesDir, the SD card); this part is plain Kotlin so it is tested on the JVM.
 */
class StorageLocations(private val roots: Map<StorageKind, File?>) {
    fun root(kind: StorageKind): File? = roots[kind]
    fun available(): List<StorageKind> = StorageKind.entries.filter { roots[it] != null }

    fun moviesDir(kind: StorageKind): File? = roots[kind]?.let { File(it, "Movies") }

    fun videoFile(kind: StorageKind, match: Match, clip: Clip): File? =
        moviesDir(kind)?.let { File(File(it, match.folder), clip.fileName) }

    fun videoFile(match: Match, clip: Clip): File? = videoFile(clip.storage, match, clip)

    /**
     * Copies a clip to another location, verifies the size, then deletes the
     * original. Returns false (and leaves the original) if anything fails.
     */
    fun move(match: Match, clip: Clip, to: StorageKind): Boolean {
        val from = videoFile(match, clip) ?: return false
        val dest = videoFile(to, match, clip) ?: return false
        if (from == dest) return true
        if (!from.exists()) return false
        return try {
            dest.parentFile?.mkdirs()
            from.copyTo(dest, overwrite = true)
            if (dest.length() != from.length()) {
                dest.delete()
                false
            } else {
                from.delete()
                true
            }
        } catch (e: Exception) {
            dest.delete()
            false
        }
    }
}
