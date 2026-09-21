package io.github.mangi.eta.agent.skill

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Cross-process mutex for the skills file tree and registry.
 *
 * The UI and the agent runtime may live in different processes; a JVM monitor alone cannot stop two install transactions from passing
 * the conflict check together. The lock file lives outside the index scan directory; reentry is allowed for the same root directory within a thread.
 */
internal object SkillMutationLock {
    private val processLock = Any()
    private val heldRoots = ThreadLocal<MutableSet<String>>()

    fun <T> withLock(
        skillsRoot: File,
        recoveryHandler: ((List<RecoveredSkillOperation>) -> Unit)? = null,
        block: () -> T,
    ): T {
        val canonicalRoot = skillsRoot.canonicalFile
        val key = canonicalRoot.absolutePath
        val held = heldRoots.get() ?: linkedSetOf<String>().also(heldRoots::set)
        if (key in held) return block()

        return synchronized(processLock) {
            val lockDirectory = prepareSkillInstallerWorkRoot(canonicalRoot)
            val lockPath = File(lockDirectory, "install.lock")
            if (
                Files.exists(lockPath.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                (Files.isSymbolicLink(lockPath.toPath()) ||
                    !Files.isRegularFile(lockPath.toPath(), LinkOption.NOFOLLOW_LINKS))
            ) {
                throw IOException("Skill install lock file is unsafe")
            }
            RandomAccessFile(lockPath, "rw").use { lockFile ->
                if (Files.isSymbolicLink(lockPath.toPath())) {
                    throw IOException("Skill install lock file is unsafe")
                }
                lockFile.channel.use { channel ->
                    channel.lock().use {
                        held += key
                        try {
                            val recovered = recoverPendingSkillOperations(canonicalRoot)
                            if (recovered.isNotEmpty()) {
                                val handler = recoveryHandler
                                    ?: throw SkillRecoveryRequiredException(
                                        "Skill files recovered; waiting for registry recovery"
                                    )
                                try {
                                    handler(recovered)
                                    completeRecoveredSkillOperations(canonicalRoot, recovered)
                                } catch (error: SkillRecoveryRequiredException) {
                                    throw error
                                } catch (error: Exception) {
                                    throw SkillRecoveryRequiredException(
                                        "Automatic skill registry recovery failed",
                                        error,
                                    )
                                }
                            }
                            block()
                        } finally {
                            held -= key
                            if (held.isEmpty()) heldRoots.remove()
                        }
                    }
                }
            }
        }
    }
}

/** Create or validate an install workspace that lives only in a private directory next to Skills; symlinks and special files are rejected. */
internal fun prepareSkillInstallerWorkRoot(skillsRoot: File): File {
    val canonicalRoot = skillsRoot.canonicalFile
    val parent = requireNotNull(canonicalRoot.parentFile) { "Skills directory must have a parent directory" }
    if (!Files.isDirectory(parent.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        throw IOException("Skills parent directory is unavailable")
    }
    val workRoot = File(parent, ".eta-skill-installer")
    val path = workRoot.toPath()
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        try {
            Files.createDirectory(path)
        } catch (error: java.nio.file.FileAlreadyExistsException) {
            // When racing another process to create it, the NOFOLLOW check below decides whether it is usable.
        }
    }
    if (
        Files.isSymbolicLink(path) ||
        !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ||
        workRoot.canonicalFile.parentFile != parent.canonicalFile ||
        workRoot.canonicalFile != workRoot.absoluteFile
    ) {
        throw IOException("Skill install work directory is unsafe")
    }
    return workRoot
}

/** A replace transaction only accepts an ordinary directory tree that can be fully copied back for recovery. */
internal fun isRecoverableSkillDirectoryTree(skillsRoot: File, target: File): Boolean {
    val canonicalRoot = runCatching { skillsRoot.canonicalFile.toPath() }.getOrNull() ?: return false
    if (Files.isSymbolicLink(target.toPath())) return false
    val canonicalTarget = runCatching { target.canonicalFile.toPath() }.getOrNull() ?: return false
    if (!canonicalTarget.startsWith(canonicalRoot) || canonicalTarget == canonicalRoot) return false
    return isRegularDirectoryTreeWithoutLinks(target.toPath())
}

private fun isRegularDirectoryTreeWithoutLinks(path: Path): Boolean {
    if (Files.isSymbolicLink(path)) return false
    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return true
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return false
    return runCatching {
        Files.newDirectoryStream(path).use { children ->
            children.all(::isRegularDirectoryTreeWithoutLinks)
        }
    }.getOrDefault(false)
}

internal fun moveSkillDirectoryAtomically(source: File, target: File) {
    target.parentFile?.let { parent ->
        if (!parent.mkdirs() && !parent.isDirectory) throw IOException("Cannot create the target parent directory")
    }
    try {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), target.toPath())
    }
}

/** Delete a path inside the Skills root, removing only the link itself whenever a symlink is encountered. */
internal fun deleteSkillPathWithoutFollowingLinks(skillsRoot: File, target: File): Boolean {
    val lexicalRoot = skillsRoot.absoluteFile.toPath().normalize()
    val lexicalTarget = target.absoluteFile.toPath().normalize()
    if (!lexicalTarget.startsWith(lexicalRoot) || lexicalTarget == lexicalRoot) return false
    if (!Files.exists(lexicalTarget, LinkOption.NOFOLLOW_LINKS)) return true
    if (!Files.isSymbolicLink(lexicalTarget)) {
        val canonicalRoot = skillsRoot.canonicalFile.toPath()
        val canonicalTarget = target.canonicalFile.toPath()
        if (!canonicalTarget.startsWith(canonicalRoot) || canonicalTarget == canonicalRoot) return false
    }
    return runCatching {
        deletePathTreeWithoutFollowingLinks(lexicalTarget)
        true
    }.getOrDefault(false)
}

private fun deletePathTreeWithoutFollowingLinks(path: Path) {
    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
        Files.newDirectoryStream(path).use { children ->
            children.forEach(::deletePathTreeWithoutFollowingLinks)
        }
    }
    Files.deleteIfExists(path)
}
