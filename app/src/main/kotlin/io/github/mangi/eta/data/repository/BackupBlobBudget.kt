package io.github.mangi.eta.data.repository

import java.io.File
import java.nio.file.Files

/** Limits raw embedded blobs before allocation, including growth while the file is being read. */
internal class BackupBlobBudget(
    private val maxFileBytes: Long = 4L * 1024 * 1024,
    private val maxTotalBytes: Long = 8L * 1024 * 1024,
    private val maxFiles: Int = 2_000,
) {
    private var used = 0L
    private var count = 0

    fun readTree(root: File, skip: (File) -> Boolean = { false }): Map<String, ByteArray> {
        val output = linkedMapOf<String, ByteArray>()
        var visited = 0
        fun visit(file: File, depth: Int) {
            require(++visited <= 10_000 && depth <= 32) { "Backup tree is too deep or has too many entries" }
            if (file != root && skip(file)) return
            require(!Files.isSymbolicLink(file.toPath())) { "Backup embeds do not support symbolic links" }
            if (file.isDirectory) {
                Files.newDirectoryStream(file.toPath()).use { entries ->
                    for (entry in entries) visit(entry.toFile(), depth + 1)
                }
            } else {
                output[file.relativeTo(root).invariantSeparatorsPath] = read(file)
            }
        }
        visit(root, 0)
        return output
    }

    fun read(file: File): ByteArray {
        require(count < maxFiles) { "Backup embed file count exceeded the limit" }
        require(file.isFile && !Files.isSymbolicLink(file.toPath())) { "Backup embeds must be regular files" }
        val limit = minOf(maxFileBytes, maxTotalBytes - used)
        val expected = file.length()
        require(expected in 0..limit) { "Backup embed exceeds the read budget: ${file.name}" }
        val output = java.io.ByteArrayOutputStream(minOf(expected, 64 * 1024L).toInt())
        val actual = file.inputStream().use { input -> BackupArchiveSafety.copyLimited(input, output, limit) }
        require(actual == expected && file.length() == expected) { "Backup embed changed while being read" }
        used += actual
        count++
        return output.toByteArray()
    }
}
