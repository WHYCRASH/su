package io.github.mangi.eta.core

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Size-rotated log file. Once the current file exceeds [maxBytes] it rolls through `name.1.ext`, `name.2.ext`, and so on.
 */
internal class FileLogSink(
    private val directory: File,
    private val fileName: String,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxRotatedFiles: Int = DEFAULT_ROTATED_FILES,
) {
    private val lock = ReentrantLock()
    private var output: OutputStream? = null
    private var currentSize = 0L
    private var acceptWrites = true

    init {
        require(fileName.isNotBlank()) { "Log file name must not be blank" }
        require(maxBytes > 0L) { "Log file size must be greater than 0" }
        require(maxRotatedFiles > 0) { "Rotated file count must be greater than 0" }
    }

    fun append(text: String) {
        if (text.isEmpty()) return
        val payload = if (text.endsWith('\n')) text else "$text\n"
        lock.withLock {
            if (!acceptWrites) return
            appendLocked(payload)
        }
    }

    fun flush() {
        lock.withLock { output?.flush() }
    }

    fun close() {
        lock.withLock { shutdownLocked(deleteFiles = false) }
    }

    fun clear() {
        lock.withLock { shutdownLocked(deleteFiles = true) }
    }

    fun files(): List<File> = lock.withLock { filesLocked(includeEmpty = false) }

    fun currentFile(): File = File(directory, fileName)

    private fun appendLocked(payload: String) {
        if (!directory.exists() && !directory.mkdirs()) {
            error("Cannot create log directory: ${directory.absolutePath}")
        }
        val incoming = payload.toByteArray(Charsets.UTF_8)
        if (output == null) {
            openCurrentLocked()
        }
        if (currentSize > 0L && currentSize + incoming.size > maxBytes) {
            rotateLocked()
        }
        val stream = output ?: error("Log file is not open")
        stream.write(incoming)
        stream.flush()
        currentSize += incoming.size
        if (currentSize > maxBytes) {
            rotateLocked()
        }
    }

    private fun openCurrentLocked() {
        closeLocked()
        val file = currentFile()
        output = BufferedOutputStream(FileOutputStream(file, true), BUFFER_SIZE)
        currentSize = file.length()
    }

    private fun rotateLocked() {
        closeLocked()
        val current = currentFile()
        if (current.exists()) {
            val oldest = rotatedFile(maxRotatedFiles)
            if (oldest.exists()) oldest.delete()
            for (index in maxRotatedFiles - 1 downTo 1) {
                val source = rotatedFile(index)
                if (source.exists()) {
                    source.renameTo(rotatedFile(index + 1))
                }
            }
            current.renameTo(rotatedFile(1))
        }
        openCurrentLocked()
    }

    private fun shutdownLocked(deleteFiles: Boolean) {
        acceptWrites = false
        closeLocked()
        if (deleteFiles) {
            filesLocked(includeEmpty = true).forEach { file -> file.delete() }
            currentSize = 0L
        }
    }

    private fun closeLocked() {
        runCatching {
            output?.flush()
            output?.close()
        }
        output = null
    }

    private fun filesLocked(includeEmpty: Boolean): List<File> {
        val files = ArrayList<File>(maxRotatedFiles + 1)
        val current = currentFile()
        if (current.exists() && (includeEmpty || current.length() > 0L)) files.add(current)
        for (index in 1..maxRotatedFiles) {
            val rotated = rotatedFile(index)
            if (rotated.exists() && (includeEmpty || rotated.length() > 0L)) files.add(rotated)
        }
        return files
    }

    private fun rotatedFile(index: Int): File = File(directory, rotatedName(index))

    private fun rotatedName(index: Int): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot <= 0) {
            "$fileName.$index"
        } else {
            "${fileName.substring(0, dot)}.$index${fileName.substring(dot)}"
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 2L * 1024L * 1024L
        const val DEFAULT_ROTATED_FILES = 2
        private const val BUFFER_SIZE = 8 * 1024
    }
}
