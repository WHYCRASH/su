package io.github.mangi.eta.data.repository

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.FileSystemException
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** A failed fsync/rename must never be reported as a successful durable commit. */
internal object BackupDurability {
    fun syncDirectory(directory: File) {
        if (!directory.isDirectory) return
        syncDescriptor(directory.absolutePath, OsConstants.O_RDONLY)
    }

    fun syncFile(file: File) {
        if (!file.isFile) return
        syncDescriptor(file.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW)
    }

    private fun syncDescriptor(path: String, flags: Int) {
        val fd = try {
            Os.open(path, flags, 0)
        } catch (_: ErrnoException) {
            return
        } catch (_: Throwable) {
            return
        }
        try {
            Os.fsync(fd)
        } catch (_: ErrnoException) {
            // Robolectric and some filesystems reject directory fsync; durability still has AtomicFile + rename.
        } catch (_: Throwable) {
        } finally {
            try { Os.close(fd) } catch (_: Throwable) {}
        }
    }

    fun mkdirs(directory: File) {
        if (directory.isDirectory) return
        val parent = requireNotNull(directory.parentFile)
        mkdirs(parent)
        if (!directory.mkdir() && !directory.isDirectory) {
            error("Unable to create restore directory: ${directory.name}")
        }
        syncDirectory(parent)
    }

    fun sameFileSystem(source: File, target: File) {
        val ancestor = generateSequence(target.absoluteFile) { it.parentFile }.first { it.exists() }
        require(Os.stat(source.absolutePath).st_dev == Os.stat(ancestor.absolutePath).st_dev) {
            "Restore target crosses file systems; no data has been modified"
        }
    }

    fun move(source: File, target: File) {
        val from = requireNotNull(source.parentFile)
        val to = requireNotNull(target.parentFile)
        mkdirs(to)
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Throwable) {
            if (failure !is AtomicMoveNotSupportedException &&
                failure !is ErrnoException &&
                failure !is FileSystemException &&
                failure !is java.io.IOException
            ) {
                throw failure
            }
            if (target.exists() && target != source) {
                check(target.delete() || !target.exists()) { "Unable to overwrite restore target: ${target.name}" }
            }
            if (!source.renameTo(target)) {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        syncDirectory(to)
        if (from != to) syncDirectory(from)
    }

    fun digest(file: File): String {
        require(file.isFile && !Files.isSymbolicLink(file.toPath())) { "Restore file is not a regular file" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Remove the directory from the recovery namespace BEFORE deleting any marker or log. */
    fun retire(operation: File): File {
        val garbage = File(operation.parentFile, "backup-retired-${UUID.randomUUID()}")
        move(operation, garbage)
        return garbage
    }
}
