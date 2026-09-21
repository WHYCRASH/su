package io.github.mangi.eta.agent.terminal

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import kotlin.coroutines.coroutineContext

internal class RootlessInstallFailure(val code: String, override val message: String) : IOException(message)

/** The rootfs is always owned by the app UID; extraction never creates device nodes and never writes files through links in the archive. */
internal object RootlessLinuxInstaller {
    private const val MAX_EXPANDED_BYTES = 3L * 1024 * 1024 * 1024
    private const val MAX_ENTRIES = 200_000

    suspend fun extract(archive: File, destination: File, xz: Boolean, stripComponents: Int = 0) {
        require(destination.mkdirs() || destination.isDirectory)
        val root = destination.canonicalFile
        val links = mutableListOf<Triple<File, String, Boolean>>()
        var bytes = 0L
        var entries = 0
        archive.inputStream().buffered().use { raw ->
            val compressed = if (xz) XZInputStream(raw, 128 * 1024) else GZIPInputStream(raw)
            TarArchiveInputStream(compressed).use { tar ->
                while (true) {
                    coroutineContext.ensureActive()
                    val entry = tar.nextEntry ?: break
                    require(++entries <= MAX_ENTRIES) { "Too many archive entries" }
                    val path = normalizedArchivePath(entry.name, stripComponents) ?: continue
                    val target = safePath(root, path)
                    require(entry.size >= 0 && entry.size <= MAX_EXPANDED_BYTES - bytes) { "Expanded archive size exceeds the limit" }
                    bytes += entry.size
                    when {
                        entry.isDirectory -> require(target.mkdirs() || target.isDirectory)
                        entry.isSymbolicLink || entry.isLink -> links += Triple(target, entry.linkName, entry.isLink)
                        entry.isFile -> {
                            require(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                            require(!Files.isSymbolicLink(target.toPath()))
                            val available = root.usableSpace
                            if (available > 0 && entry.size + 16L * 1024 * 1024 > available) {
                                throw RootlessInstallFailure("INSUFFICIENT_STORAGE", "Insufficient storage; free up internal storage and try again")
                            }
                            target.outputStream().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var remaining = entry.size
                                while (remaining > 0) {
                                    coroutineContext.ensureActive()
                                    val count = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                    if (count < 0) throw IOException("Archive content is incomplete")
                                    output.write(buffer, 0, count)
                                    remaining -= count
                                }
                            }
                            require(target.setReadable(true, true) && target.setWritable(true, true))
                            if (entry.mode and 0b001001001 != 0) require(target.setExecutable(true, false))
                        }
                        entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO -> Unit
                        else -> throw IOException("Unsupported archive entry")
                    }
                }
            }
        }
        // Links land last; later archive entries cannot use a link to overwrite files outside the environment.
        val pendingHardlinks = links.filter { it.third }.toMutableList()
        while (pendingHardlinks.isNotEmpty()) {
            var resolved = false
            val iterator = pendingHardlinks.iterator()
            while (iterator.hasNext()) {
                coroutineContext.ensureActive()
                val (target, linkName) = iterator.next()
                val source = safePath(root, requireNotNull(normalizedArchivePath(linkName, stripComponents)))
                if (!source.isFile || Files.isSymbolicLink(source.toPath())) continue
                require(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                require(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Duplicate archive path" }
                Files.createLink(target.toPath(), source.toPath())
                iterator.remove()
                resolved = true
            }
            require(resolved) { "Archive hardlink target is missing or forms a loop" }
        }
        links.filterNot { it.third }.forEach { (target, linkName) ->
            coroutineContext.ensureActive()
            require('\u0000' !in linkName && linkName.isNotEmpty())
            val source = if (linkName.startsWith('/')) File(root, linkName.removePrefix("/")) else File(target.parentFile, linkName)
            val normalized = source.toPath().normalize()
            require(normalized.startsWith(root.toPath())) { "Archive link escapes the root" }
            require(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
            require(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Duplicate archive path" }
            Files.createSymbolicLink(target.toPath(), target.parentFile!!.toPath().relativize(normalized))
        }
    }

    private fun normalizedArchivePath(raw: String, strip: Int): String? {
        require(!raw.startsWith('/') && '\u0000' !in raw) { "Invalid archive path" }
        val segments = raw.split('/').filter { it.isNotEmpty() && it != "." }
        require(segments.none { it == ".." }) { "Archive path escapes the root" }
        return segments.drop(strip).joinToString("/").takeIf { it.isNotEmpty() }
    }

    private fun safePath(root: File, relative: String): File {
        val path = File(root, relative).canonicalFile
        require(path.toPath().startsWith(root.toPath()) && path != root) { "Archive path escapes the root" }
        return path
    }

    suspend fun installBase(artifact: VerifiedArtifact, archive: File, rootfs: File, distribution: LinuxDistribution): Boolean {
        val staging = File(rootfs.parentFile, "rootfs.installing")
        try {
            if (!staging.parentFile!!.mkdirs() && !staging.parentFile!!.isDirectory) throw RootlessInstallFailure("INSTALL_DIRECTORY_UNAVAILABLE", "Cannot create the environment directory; check internal storage")
            val available = staging.parentFile!!.usableSpace
            if (available in 1 until 512L * 1024 * 1024) throw RootlessInstallFailure("INSUFFICIENT_STORAGE", "Installing Linux needs at least 512 MB of free internal storage; free up space and try again")
            if (staging.exists() && !staging.deleteRecursively()) throw RootlessInstallFailure("STAGING_CLEANUP_FAILED", "Cannot clear the incomplete install; restart su and try again")
            extract(archive, staging, xz = distribution == LinuxDistribution.DEBIAN, stripComponents = if (distribution == LinuxDistribution.DEBIAN) 1 else 0)
            listOf("proc", "sys", "dev", "dev/shm", "workspace", "storage/emulated/0", "tmp", "usr/local/bin", "root").forEach { File(staging, it).mkdirs() }
            File(staging, "etc/resolv.conf").apply {
                Files.deleteIfExists(toPath())
                writeText("nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 1.1.1.1\n")
            }
            val helper = when (distribution) {
                LinuxDistribution.ALPINE -> {
                    File(staging, "etc/apk/repositories").writeText(AlpineEnvironmentInstaller.APK_MIRROR_BASE_URLS.first().let { "$it/v3.24/main\n$it/v3.24/community\n" })
                    "eta-apk" to AlpineEnvironmentInstaller.apkMirrorScript()
                }
                LinuxDistribution.DEBIAN -> {
                    val mirror = DebianEnvironmentInstaller.APT_MIRRORS.first()
                    File(staging, "etc/apt/sources.list").writeText("deb ${mirror.archiveBaseUrl} trixie main\ndeb ${mirror.archiveBaseUrl} trixie-updates main\ndeb ${mirror.securityBaseUrl} trixie-security main\n")
                    File(staging, "etc/apt/apt.conf.d").mkdirs()
                    File(staging, "etc/apt/apt.conf.d/99eta-rootless").writeText("APT::Sandbox::User \"root\";\nAcquire::Retries \"2\";\n")
                    File(staging, "usr/sbin/policy-rc.d").apply { writeText("#!/bin/sh\nexit 101\n"); setExecutable(true, false) }
                    "eta-apt" to DebianEnvironmentInstaller.aptMirrorScript()
                }
            }
            File(staging, "usr/local/bin/${helper.first}").apply { writeText(helper.second + "\n"); setExecutable(true, false) }
            val result = InstallerShellRunner.run("/bin/sh -c ':'", 20, distribution.terminalEnvironment, staging.absolutePath)
            if (result.exitCode != 0) throw RootlessInstallFailure("PROOT_START_FAILED", "Rootless Linux failed to start (exit code ${result.exitCode}); confirm you are on a supported 64-bit device and try again")
            File(staging, LinuxEnvironmentPaths.READY_MARKER).writeText("version=${artifact.version}\nsha256=${artifact.sha256}\nbackend=proot\n")
            if (rootfs.exists()) throw RootlessInstallFailure("ENVIRONMENT_ALREADY_EXISTS", "The existing environment directory was kept and cannot be overwritten; export the files you need before handling the incomplete environment")
            if (!staging.renameTo(rootfs)) throw RootlessInstallFailure("ENVIRONMENT_ACTIVATION_FAILED", "Cannot activate the new environment; check internal storage space and try again")
            return true
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }
}
