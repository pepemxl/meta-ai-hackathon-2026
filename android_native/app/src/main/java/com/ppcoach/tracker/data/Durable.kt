package com.ppcoach.tracker.data

import java.io.File
import java.io.FileOutputStream

/** Writes that survive the phone turning off halfway: temp file + fsync + rename. */
object Durable {
    fun writeText(file: File, text: String) = writeBytes(file, text.toByteArray(Charsets.UTF_8))

    fun writeBytes(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            // Windows-like filesystems refuse to rename over an existing file.
            file.delete()
            if (!tmp.renameTo(file)) throw java.io.IOException("cannot replace ${file.name}")
        }
    }
}

/**
 * library.json in files/: one writer thread, in order. A file that cannot be
 * read is set aside as library.json.bad (the videos are untouched) and the
 * app starts empty.
 */
class LibraryStore(private val dir: File) {
    private val file = File(dir, "library.json")
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "library-writer") }

    fun load(): Library {
        if (!file.exists()) return Library()
        return try {
            LibraryCodec.decode(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            file.renameTo(File(dir, "library.json.bad"))
            Library()
        }
    }

    fun save(lib: Library) {
        val text = LibraryCodec.encode(lib)
        executor.execute { Durable.writeText(file, text) }
    }

    /** Waits for the pending writes (tests). */
    fun flush() {
        executor.submit {}.get()
    }
}
