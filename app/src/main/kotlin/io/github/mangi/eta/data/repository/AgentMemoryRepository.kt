package io.github.mangi.eta.data.repository

import android.content.Context
import android.util.AtomicFile
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AssistantPrompt
import io.github.mangi.eta.data.model.AssistantStorage
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow

internal data class AgentMemorySnapshot(
    val content: String,
    val revision: String,
    val byteSize: Int,
    val lineCount: Int,
)

internal data class AgentMemoryReadResult(
    val snapshot: AgentMemorySnapshot,
    val content: String,
    val startLine: Int?,
    val endLine: Int?,
    val hasMore: Boolean,
    val matchedLines: Int,
)

internal sealed interface AgentMemoryMutation {
    val revision: String

    data class ReplaceRange(
        override val revision: String,
        val startLine: Int,
        val endLine: Int,
        val content: String,
    ) : AgentMemoryMutation

    data class Append(
        override val revision: String,
        val content: String,
    ) : AgentMemoryMutation

    data class Clear(
        override val revision: String,
    ) : AgentMemoryMutation
}

internal sealed interface AgentMemoryWriteResult {
    data class Success(val snapshot: AgentMemorySnapshot) : AgentMemoryWriteResult
    data class Conflict(val snapshot: AgentMemorySnapshot) : AgentMemoryWriteResult
}

internal class AgentMemoryException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** Bounded, atomic file storage for a single MEMORY.md. */
internal class AgentMemoryStore(
    private val memoryDir: File,
) {
    private val atomicFile = AtomicFile(File(memoryDir, FILE_NAME))

    fun snapshot(): AgentMemorySnapshot = withStorageLock {
        snapshotLocked()
    }

    fun read(
        query: String? = null,
        startLine: Int = DEFAULT_START_LINE,
        maxChars: Int = DEFAULT_READ_CHARS,
    ): AgentMemoryReadResult = withStorageLock {
        val snapshot = snapshotLocked()
        val boundedChars = maxChars.coerceIn(MIN_READ_CHARS, MAX_READ_CHARS)
        if (query.isNullOrBlank()) {
            page(snapshot, startLine, boundedChars)
        } else {
            search(snapshot, query.trim(), boundedChars)
        }
    }

    fun mutate(mutation: AgentMemoryMutation): AgentMemoryWriteResult = withStorageLock {
        val current = snapshotLocked()
        if (mutation.revision != current.revision) {
            return@withStorageLock AgentMemoryWriteResult.Conflict(current)
        }
        val updated = when (mutation) {
            is AgentMemoryMutation.ReplaceRange -> replaceRange(current, mutation)
            is AgentMemoryMutation.Append -> append(current, mutation.content)
            is AgentMemoryMutation.Clear -> ""
        }
        writeLocked(updated)
        AgentMemoryWriteResult.Success(snapshotOf(updated))
    }

    fun replaceAll(content: String): AgentMemorySnapshot = withStorageLock {
        writeLocked(content)
        snapshotOf(content)
    }

    fun replaceAll(content: String, revision: String): AgentMemorySnapshot = withStorageLock {
        if (snapshotLocked().revision != revision) throw AgentMemoryException("MEMORY_CONFLICT", "Memory was updated by another operation and the draft was kept; re-read and merge first.")
        writeLocked(content)
        snapshotOf(content)
    }

    fun delete() = withStorageLock(allowDeleted = true) {
        deletedMarker().writeText("deleted")
        check(!memoryDir.exists() || memoryDir.deleteRecursively()) { "Cannot delete the assistant memory" }
    }

    fun restore(content: String): AgentMemorySnapshot = withStorageLock(allowDeleted = true) {
        writeLocked(content)
        check(!deletedMarker().exists() || deletedMarker().delete()) { "Cannot restore the assistant memory" }
        snapshotOf(content)
    }

    private fun deletedMarker() = File(memoryDir.parentFile, ".memory-locks/${memoryDir.name}.deleted")

    private fun <T> withStorageLock(allowDeleted: Boolean = false, block: () -> T): T = synchronized(FILE_LOCK) {
        val directory = File(memoryDir.parentFile, ".memory-locks")
        check(directory.mkdirs() || directory.isDirectory) { "Cannot create the memory lock directory" }
        java.io.RandomAccessFile(File(directory, "${memoryDir.name}.lock"), "rw").use { file ->
            file.channel.lock().use {
                if (!allowDeleted && deletedMarker().exists()) throw AgentMemoryException("ASSISTANT_DELETED", "Assistant is deleted; memory operation not performed")
                block()
            }
        }
    }

    private fun snapshotLocked(): AgentMemorySnapshot {
        val file = atomicFile.baseFile
        if (!file.exists() && !File(file.path + ".bak").exists()) return snapshotOf("")
        val bytes = try {
            atomicFile.openRead().use { it.readNBytes(MAX_FILE_BYTES + 1) }
        } catch (throwable: IOException) {
            throw AgentMemoryException(
                code = "MEMORY_READ_FAILED",
                message = "Cannot read the memory file",
                cause = throwable,
            )
        }
        if (bytes.size > MAX_FILE_BYTES) {
            throw AgentMemoryException(
                code = "MEMORY_TOO_LARGE",
                message = "Memory file exceeds the 1 MiB safety cap",
            )
        }
        val content = bytes.toString(Charsets.UTF_8)
        return snapshotOf(content, bytes)
    }

    private fun writeLocked(content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) {
            throw AgentMemoryException(
                code = "MEMORY_TOO_LARGE",
                message = "Memory file must not exceed 1 MiB of UTF-8 bytes",
            )
        }
        if (!memoryDir.exists() && !memoryDir.mkdirs() && !memoryDir.isDirectory) {
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "Cannot create the memory directory",
            )
        }
        val output = try {
            atomicFile.startWrite()
        } catch (throwable: IOException) {
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "Cannot start writing the memory file",
                cause = throwable,
            )
        }
        try {
            output.write(bytes)
            atomicFile.finishWrite(output)
        } catch (throwable: Throwable) {
            atomicFile.failWrite(output)
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "Cannot save the memory file",
                cause = throwable,
            )
        }
    }

    private fun replaceRange(
        snapshot: AgentMemorySnapshot,
        mutation: AgentMemoryMutation.ReplaceRange,
    ): String {
        val lines = snapshot.content.memoryLines().toMutableList()
        if (
            mutation.startLine < 1 ||
            mutation.endLine < mutation.startLine ||
            mutation.endLine > lines.size
        ) {
            throw AgentMemoryException(
                code = "MEMORY_RANGE_INVALID",
                message = "Invalid replacement line range; re-read the memory and retry",
            )
        }
        val replacement = mutation.content.memoryLines()
        lines.subList(mutation.startLine - 1, mutation.endLine).clear()
        if (replacement.isNotEmpty()) {
            lines.addAll(mutation.startLine - 1, replacement)
        }
        return lines.joinToString("\n")
    }

    private fun append(snapshot: AgentMemorySnapshot, content: String): String {
        if (content.isEmpty()) return snapshot.content
        if (snapshot.content.isEmpty()) return content
        return snapshot.content.trimEnd('\n') + "\n" + content
    }

    private fun page(
        snapshot: AgentMemorySnapshot,
        requestedStartLine: Int,
        maxChars: Int,
    ): AgentMemoryReadResult {
        val lines = snapshot.content.memoryLines()
        if (lines.isEmpty()) {
            return AgentMemoryReadResult(snapshot, "", null, null, false, 0)
        }
        val startIndex = (requestedStartLine - 1).coerceIn(0, lines.size)
        if (startIndex >= lines.size) {
            return AgentMemoryReadResult(snapshot, "", null, null, false, 0)
        }
        val output = StringBuilder()
        var endIndex = startIndex
        while (endIndex < lines.size) {
            val rendered = "${endIndex + 1}: ${lines[endIndex]}"
            val separatorLength = if (output.isEmpty()) 0 else 1
            if (output.isNotEmpty() && output.length + separatorLength + rendered.length > maxChars) break
            if (output.isNotEmpty()) output.append('\n')
            output.append(rendered.take(maxChars - output.length))
            endIndex++
            if (output.length >= maxChars) break
        }
        return AgentMemoryReadResult(
            snapshot = snapshot,
            content = output.toString(),
            startLine = startIndex + 1,
            endLine = endIndex,
            hasMore = endIndex < lines.size,
            matchedLines = endIndex - startIndex,
        )
    }

    private fun search(
        snapshot: AgentMemorySnapshot,
        query: String,
        maxChars: Int,
    ): AgentMemoryReadResult {
        val lines = snapshot.content.memoryLines()
        val matched = lines.indices.filter { index ->
            lines[index].contains(query, ignoreCase = true)
        }
        val included = linkedSetOf<Int>()
        matched.forEach { index ->
            for (candidate in (index - SEARCH_CONTEXT_LINES)..(index + SEARCH_CONTEXT_LINES)) {
                if (candidate in lines.indices) included += candidate
            }
        }
        val output = StringBuilder()
        var lastIncluded: Int? = null
        var renderedCount = 0
        for (index in included) {
            val rendered = "${index + 1}: ${lines[index]}"
            val gap = when {
                output.isEmpty() -> ""
                lastIncluded != null && index > lastIncluded + 1 -> "\n…\n"
                else -> "\n"
            }
            if (output.isNotEmpty() && output.length + gap.length + rendered.length > maxChars) break
            output.append(gap).append(rendered.take(maxChars - output.length - gap.length))
            lastIncluded = index
            renderedCount++
            if (output.length >= maxChars) break
        }
        return AgentMemoryReadResult(
            snapshot = snapshot,
            content = output.toString(),
            startLine = included.firstOrNull()?.plus(1),
            endLine = lastIncluded?.plus(1),
            hasMore = renderedCount < included.size,
            matchedLines = matched.size,
        )
    }

    private fun snapshotOf(
        content: String,
        bytes: ByteArray = content.toByteArray(Charsets.UTF_8),
    ): AgentMemorySnapshot = AgentMemorySnapshot(
        content = content,
        revision = sha256(bytes),
        byteSize = bytes.size,
        lineCount = content.memoryLines().size,
    )

    private fun String.memoryLines(): List<String> =
        if (isEmpty()) emptyList() else split('\n')

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }

    companion object {
        private val FILE_LOCK = Any()
        const val MAX_FILE_BYTES = 1024 * 1024
        const val DEFAULT_READ_CHARS = 12_000
        const val MAX_READ_CHARS = 32_000
        const val MIN_READ_CHARS = 1
        const val MAX_WRITE_CONTENT_CHARS = 3_500
        const val FILE_NAME = "MEMORY.md"
        private const val DEFAULT_START_LINE = 1
        private const val SEARCH_CONTEXT_LINES = 1
    }
}

internal object AgentMemoryRepository {
    private const val ROOT_NAME = "memory"

    @Volatile
    private lateinit var rootDir: File
    private val lock = Any()
    private val stores = mutableMapOf<String, AgentMemoryStore>()

    fun init(context: Context) {
        if (!::rootDir.isInitialized) {
            rootDir = context.applicationContext.filesDir
            migrateLegacyMemory()
        }
    }

    fun snapshot(assistantId: String = currentAssistantId()): AgentMemorySnapshot =
        storeFor(assistantId).snapshot()

    fun read(
        query: String? = null,
        startLine: Int = 1,
        maxChars: Int = AgentMemoryStore.DEFAULT_READ_CHARS,
        assistantId: String = currentAssistantId(),
    ): AgentMemoryReadResult = storeFor(assistantId).read(query, startLine, maxChars)

    fun mutate(
        mutation: AgentMemoryMutation,
        assistantId: String = currentAssistantId(),
    ): AgentMemoryWriteResult = storeFor(assistantId).mutate(mutation)

    fun replaceAll(
        content: String,
        assistantId: String = currentAssistantId(),
        revision: String? = null,
    ): AgentMemorySnapshot = if (revision == null) storeFor(assistantId).replaceAll(content)
        else storeFor(assistantId).replaceAll(content, revision)

    fun exportAll(): Map<String, String> {
        ensureInitialized()
        val exported = linkedMapOf<String, String>()
        val memoryRoot = File(rootDir, ROOT_NAME)
        val ids = linkedSetOf(currentAssistantId())
        AssistantRepository.profiles.value.forEach {
            require(ids.size < 1_000) { "Assistant memory count exceeds the backup limit" }
            ids += it.id
        }
        if (memoryRoot.isDirectory) {
            require(!java.nio.file.Files.isSymbolicLink(memoryRoot.toPath())) { "Memory root must not be a symlink" }
            java.nio.file.Files.newDirectoryStream(memoryRoot.toPath()).use { entries ->
                var visited = 0
                for (entry in entries) {
                    require(++visited <= 2_000 && ids.size < 1_000) { "Assistant memory directory exceeds the backup limit" }
                    require(!java.nio.file.Files.isSymbolicLink(entry)) { "Memory directories must not contain symlinks" }
                    if (java.nio.file.Files.isDirectory(entry) && !entry.fileName.toString().startsWith(".")) ids += entry.fileName.toString()
                }
            }
        }
        var total = 0L
        ids.forEach { id ->
            val snapshot = storeFor(id).snapshot()
            total += snapshot.byteSize
            require(total <= 4L * 1024 * 1024) { "All assistant memories exceed the 4 MiB backup limit" }
            if (snapshot.content.isNotEmpty()) exported[AssistantStorage.id(id)] = snapshot.content
        }
        return exported
    }

    fun importAll(memories: Map<String, String>, fallback: String = "") {
        ensureInitialized()
        val retained = if (memories.isEmpty()) setOf(AssistantPrompt.DEFAULT_ID) else memories.keys.map { AssistantStorage.id(it) }.toSet()
        File(rootDir, ROOT_NAME).listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") && it.name !in retained }
            .forEach { delete(it.name) }
        if (memories.isNotEmpty()) {
            memories.forEach { (id, content) -> storeFor(id).restore(content) }
            return
        }
        storeFor(AssistantPrompt.DEFAULT_ID).restore(fallback)
    }

    fun copy(fromId: String, toId: String) {
        val content = snapshot(fromId).content
        if (content.isEmpty()) return
        replaceAll(content, toId)
    }

    fun delete(assistantId: String) {
        storeFor(assistantId).delete()
    }

    fun enabledFlow(): Flow<Boolean> = SettingsDataStore.memoryEnabledFlow()

    fun isEnabled(assistantId: String = currentAssistantId()): Boolean =
        AssistantRepository.currentProfile(assistantId)?.memoryEnabled ?: false

    fun setEnabled(enabled: Boolean, assistantId: String = currentAssistantId()) {
        val current = requireNotNull(AssistantRepository.currentProfile(assistantId)) { "Assistant does not exist" }
        AssistantRepository.update(current.copy(memoryEnabled = enabled))
    }

    private fun storeFor(assistantId: String): AgentMemoryStore {
        ensureInitialized()
        val id = AssistantStorage.id(assistantId)
        synchronized(lock) {
            return stores.getOrPut(id) {
                AgentMemoryStore(File(rootDir, "$ROOT_NAME/$id"))
            }
        }
    }

    private fun currentAssistantId(): String =
        if (AssistantRepository.isReady()) AssistantRepository.active().id else AssistantPrompt.DEFAULT_ID

    private fun migrateLegacyMemory() {
        val legacy = File(rootDir, "$ROOT_NAME/${AgentMemoryStore.FILE_NAME}")
        val migrated = File(rootDir, "$ROOT_NAME/${AssistantPrompt.DEFAULT_ID}/${AgentMemoryStore.FILE_NAME}")
        if (legacy.isFile && !migrated.exists()) {
            migrated.parentFile?.mkdirs()
            if (!legacy.renameTo(migrated)) {
                migrated.writeBytes(legacy.readBytes())
                legacy.delete()
            }
        }
    }

    private fun ensureInitialized() {
        check(::rootDir.isInitialized) {
            "AgentMemoryRepository.init(context) must be called in Application.onCreate()"
        }
    }
}
