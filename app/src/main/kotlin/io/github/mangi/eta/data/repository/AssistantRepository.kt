package io.github.mangi.eta.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.data.model.AssistantDefaults
import io.github.mangi.eta.data.model.AssistantProfile
import io.github.mangi.eta.data.model.AssistantPrompt
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object AssistantRepository {
    private const val DIRECTORY_NAME = "eta-assistants"
    private const val INDEX_NAME = "index.json"
    private const val AVATAR_DIR = "avatars"
    private const val AVATAR_SIZE = 512

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private lateinit var applicationContext: Context

    private val _profiles = MutableStateFlow<List<AssistantProfile>>(emptyList())
    val profiles: StateFlow<List<AssistantProfile>> = _profiles.asStateFlow()

    private val _activeId = MutableStateFlow(AssistantPrompt.DEFAULT_ID)
    val activeId: StateFlow<String> = _activeId.asStateFlow()

    fun isReady(): Boolean = ::applicationContext.isInitialized

    @Synchronized
    fun init(context: Context) {
        applicationContext = context.applicationContext
        directory().mkdirs()
        avatarsDirectory().mkdirs()
        val active = withIndexLock {
            val snapshot = migrateDefaultPrompt(readIndex() ?: seedDefault())
            publish(snapshot)
            snapshot.activeId
        }
        runCatching { refreshAssistantSkills(active, publishVisible = true) }.onFailure { error ->
            io.github.mangi.eta.core.AndroidAgentLogger.error("Assistant skill startup publication failed: ${error.javaClass.simpleName}")
        }
    }

    fun active(): AssistantProfile =
        profiles.value.firstOrNull { it.id == activeId.value }
            ?: profiles.value.firstOrNull()
            ?: defaultProfile()

    fun profile(id: String): AssistantProfile? =
        profiles.value.firstOrNull { it.id == id }

    /** Read the published index as well in the runtime process; never substitute the active assistant. */
    fun currentProfile(id: String): AssistantProfile? =
        if (isReady()) withIndexLock { profiles.value.firstOrNull { it.id == id } } else profile(id)

    private fun validateProfiles(profiles: List<AssistantProfile>) {
        require(profiles.map { io.github.mangi.eta.data.model.AssistantStorage.id(it.id) }.toSet().size == profiles.size) { "Duplicate assistant ID" }
    }

    fun systemPrompt(): String {
        val assistant = if (::applicationContext.isInitialized) active() else defaultProfile()
        return AssistantPrompt.build(assistant.name, assistant.prompt)
    }

    fun avatarFile(fileName: String?): File? {
        if (!::applicationContext.isInitialized || fileName.isNullOrBlank()) return null
        val file = File(avatarsDirectory(), fileName)
        return file.takeIf { it.isFile }
    }

    fun avatarBitmap(fileName: String?): Bitmap? {
        val file = avatarFile(fileName) ?: return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    @Synchronized
    fun create(
        name: String = "New assistant",
        prompt: String = "",
        avatarFileName: String? = null,
        memoryEnabled: Boolean = true,
        enabledSkillIds: List<String> = AssistantDefaults.ENABLED_SKILL_IDS,
    ): AssistantProfile {
        return withIndexLock {
            ensureReady()
            val id = UUID.randomUUID().toString()
            val created = AssistantProfile(
                id = id,
                name = name.trim().ifBlank { "New assistant" },
                prompt = prompt,
                avatarFileName = avatarFileName,
                createdAt = System.currentTimeMillis(),
                memoryEnabled = memoryEnabled,
                enabledSkillIds = enabledSkillIds,
            )
            val snapshot = Snapshot(activeId.value, profiles.value + created)
            writeIndex(snapshot)
            publish(snapshot)
            created
        }.also { created ->
            if (::applicationContext.isInitialized) {
                runCatching { refreshAssistantSkills(created.id, publishVisible = created.id == activeId.value) }
            }
        }
    }

    @Synchronized
    fun duplicate(id: String): AssistantProfile {
        ensureReady()
        val source = requireNotNull(currentProfile(id)) { "Assistant does not exist" }
        val created = create(
            name = source.name.trim().ifBlank { AssistantPrompt.DEFAULT_NAME } + " copy",
            prompt = source.prompt,
            memoryEnabled = source.memoryEnabled,
            enabledSkillIds = source.enabledSkillIds,
        )
        AgentMemoryRepository.copy(source.id, created.id)
        if (::applicationContext.isInitialized) {
            SkillRuntime.copyAssistantSkills(applicationContext, source.id, created.id)
        }
        return source.avatarFileName?.let { copyAvatar(it, created.id) }?.let { fileName ->
            update(created.copy(avatarFileName = fileName))
        } ?: created
    }

    @Synchronized
    fun enableSkills(skillIds: Collection<String>, assistantId: String = active().id) {
        if (!isReady() || skillIds.isEmpty()) return
        val current = requireNotNull(currentProfile(assistantId)) { "Assistant does not exist" }
        val merged = (current.enabledSkillIds + skillIds).distinct()
        if (merged == current.enabledSkillIds) return
        update(current.copy(enabledSkillIds = merged))
    }

    @Synchronized
    fun update(profile: AssistantProfile): AssistantProfile {
        val (updated, publishVisible) = withIndexLock {
            ensureReady()
            require(profiles.value.any { it.id == profile.id }) { "Assistant does not exist" }
            val next = profile.copy(name = profile.name.trim().ifBlank { AssistantPrompt.DEFAULT_NAME })
            val snapshot = Snapshot(
                activeId = activeId.value,
                profiles = profiles.value.map { if (it.id == next.id) next else it },
            )
            writeIndex(snapshot)
            publish(snapshot)
            next to (next.id == snapshot.activeId)
        }
        if (::applicationContext.isInitialized) {
            refreshAssistantSkills(updated.id, publishVisible = publishVisible)
        }
        return updated
    }

    @Synchronized
    fun delete(id: String) {
        val nextActive = withIndexLock {
            ensureReady()
            val remaining = profiles.value.filterNot { it.id == id }
            require(remaining.isNotEmpty()) { "At least one assistant must be kept" }
            val next = if (activeId.value == id) remaining.first().id else activeId.value
            val avatar = avatarFile(profile(id)?.avatarFileName)
            val snapshot = Snapshot(next, remaining)
            writeIndex(snapshot)
            publish(snapshot)
            avatar?.delete()
            next
        }
        AgentMemoryRepository.delete(id)
        if (::applicationContext.isInitialized) {
            SkillRuntime.deleteAssistantSkills(applicationContext, id)
            if (nextActive != id) {
                refreshAssistantSkills(nextActive, publishVisible = true)
            }
        }
    }

    @Synchronized
    fun exportSnapshot(): AssistantBackupSnapshot = withIndexLock {
        ensureReady()
        AssistantBackupSnapshot(activeId = activeId.value, profiles = profiles.value)
    }

    @Synchronized
    fun importSnapshot(snapshot: AssistantBackupSnapshot) {
        val active = withIndexLock {
            ensureReady()
            val profiles = snapshot.profiles.ifEmpty { listOf(defaultProfile(System.currentTimeMillis())) }
            validateProfiles(profiles)
            val selected = snapshot.activeId.takeIf { id -> profiles.any { it.id == id } } ?: profiles.first().id
            val next = migrateDefaultPrompt(Snapshot(selected, profiles))
            writeIndex(next)
            publish(next)
            next.activeId
        }
        refreshAssistantSkills(active, publishVisible = true)
    }

    fun exportAvatars(): Map<String, ByteArray> {
        if (!::applicationContext.isInitialized) return emptyMap()
        val dir = avatarsDirectory()
        if (!dir.isDirectory) return emptyMap()
        require(!java.nio.file.Files.isSymbolicLink(dir.toPath())) { "Avatar directory must not be a symlink" }
        val budget = BackupBlobBudget(maxTotalBytes = 4L * 1024 * 1024, maxFiles = 1_000)
        val result = linkedMapOf<String, ByteArray>()
        java.nio.file.Files.newDirectoryStream(dir.toPath()).use { entries ->
            for (entry in entries) {
                val file = entry.toFile()
                require(file.isFile) { "Avatar directory contains a non-file entry" }
                result[file.name] = budget.read(file)
            }
        }
        return result
    }

    fun importAvatars(files: Map<String, ByteArray>) {
        if (!::applicationContext.isInitialized) return
        val dir = avatarsDirectory()
        require(files.keys.all { it.isNotBlank() && File(it).name == it && !it.contains('\\') }) { "Invalid avatar file name" }
        check(dir.mkdirs() || dir.isDirectory) { "Could not create the avatar directory" }
        dir.listFiles().orEmpty().forEach { check(it.delete()) { "Could not clear old avatars" } }
        files.forEach { (name, bytes) ->
            val safe = File(name).name
            File(dir, safe).writeBytes(bytes)
        }
    }

    @Synchronized
    fun select(id: String) {
        val changed = withIndexLock {
            ensureReady()
            require(profiles.value.any { it.id == id }) { "Assistant does not exist" }
            if (activeId.value == id) return@withIndexLock false
            val snapshot = Snapshot(id, profiles.value)
            writeIndex(snapshot)
            publish(snapshot)
            true
        }
        if (changed) refreshAssistantSkills(id, publishVisible = true)
    }

    private fun refreshAssistantSkills(assistantId: String, publishVisible: Boolean) {
        if (!::applicationContext.isInitialized) return
        run {
            val enabled = profile(assistantId)?.enabledSkillIds?.toSet().orEmpty()
            val entries = SkillRuntime.createIndexService(applicationContext)
                .listSkillsForManagement()
                .filter { it.installed && it.id in enabled }
            if (publishVisible) {
                SkillRuntime.publishVisibleSkills(applicationContext, assistantId, entries)
            } else {
                SkillRuntime.bindSkillsToAssistant(applicationContext, assistantId, entries)
            }
        }
    }

    @Synchronized
    fun saveAvatar(id: String, bitmap: Bitmap): AssistantProfile {
        ensureReady()
        val current = requireNotNull(currentProfile(id)) { "Assistant does not exist" }
        val fileName = "$id.png"
        val target = File(avatarsDirectory(), fileName)
        val scaled = scaleAvatar(bitmap)
        target.outputStream().use { output ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        if (scaled !== bitmap) scaled.recycle()
        return update(current.copy(avatarFileName = fileName))
    }

    @Synchronized
    fun clearAvatar(id: String): AssistantProfile {
        ensureReady()
        val current = requireNotNull(currentProfile(id)) { "Assistant does not exist" }
        avatarFile(current.avatarFileName)?.delete()
        return update(current.copy(avatarFileName = null))
    }

    private fun seedDefault(): Snapshot {
        val seeded = Snapshot(
            activeId = AssistantPrompt.DEFAULT_ID,
            profiles = listOf(defaultProfile(System.currentTimeMillis())),
        )
        writeIndex(seeded)
        return seeded
    }

    private fun defaultProfile(createdAt: Long = 0L) = AssistantProfile(
        id = AssistantPrompt.DEFAULT_ID,
        name = AssistantPrompt.DEFAULT_NAME,
        prompt = "",
        createdAt = createdAt,
    )

    /**
     * Names earlier versions shipped for the default profile. They are persisted user data, so the
     * former Chinese default name is kept as an escaped literal (U+4EE3 U+9C7C) rather than as text.
     */
    private val LEGACY_DEFAULT_NAMES = setOf("Eta", "\u4ee3\u9c7c")

    /**
     * Persona bodies earlier versions seeded into a profile, kept as escaped literals because they are
     * persisted user data rather than source text. The pre-translation Chinese body reads
     * "Get the work done, skip the pleasantries. / Have a stance. / Act first, ask later." A stored copy is
     * cleared so the shipped identity takes over instead of a language the app no longer ships.
     *
     * A duplicated assistant keeps the body of the profile it was copied from, so the match applies to every
     * profile; only the name rewrite below stays scoped to the default profile.
     */
    private val LEGACY_DEFAULT_BODIES = setOf(
        "**\u5148\u505a\u4e8b\uff0c\u5c11\u5ba2\u5957\u3002** \u4e0d\u8981\u7528\u300c\u597d\u7684\uff01\u300d\u300c\u5f88\u9ad8\u5174\u4e3a\u4f60\u6548\u52b3\u300d\u5f00\u5934\uff0c\u76f4\u63a5\u5e2e\u7528\u6237\u628a\u4e8b\u60c5\u505a\u5b8c\u3002" + "\n\n" +
            "**\u8981\u6709\u7acb\u573a\u3002** \u53ef\u4ee5\u4e0d\u540c\u610f\u3001\u53ef\u4ee5\u6709\u504f\u597d\uff0c\u4e5f\u53ef\u4ee5\u89c9\u5f97\u6709\u4e9b\u4e8b\u6709\u8da3\u3001\u6709\u4e9b\u4e8b\u65e0\u804a\u3002" + "\n\n" +
            "**\u5148\u884c\u52a8\uff0c\u518d\u63d0\u95ee\u3002** \u80fd\u67e5\u7684\u5148\u67e5\uff0c\u5e26\u7b54\u6848\u56de\u6765\uff0c\u800c\u4e0d\u662f\u5148\u629b\u4e00\u5806\u95ee\u9898\u3002",
    )

    private fun migrateDefaultPrompt(snapshot: Snapshot): Snapshot {
        val profiles = snapshot.profiles.map { profile ->
            var next = profile
            if (next.prompt == AssistantPrompt.DEFAULT_BODY || next.prompt in LEGACY_DEFAULT_BODIES) {
                next = next.copy(prompt = "")
            }
            if (next.id == AssistantPrompt.DEFAULT_ID && next.name in LEGACY_DEFAULT_NAMES) {
                next = next.copy(name = AssistantPrompt.DEFAULT_NAME)
            }
            next
        }
        if (profiles == snapshot.profiles) return snapshot
        val migrated = snapshot.copy(profiles = profiles)
        writeIndex(migrated)
        return migrated
    }

    private fun publish(snapshot: Snapshot) {
        validateProfiles(snapshot.profiles)
        _profiles.value = snapshot.profiles
        _activeId.value = snapshot.activeId.takeIf { id -> snapshot.profiles.any { it.id == id } }
            ?: snapshot.profiles.first().id
    }

    private fun readIndex(): Snapshot? {
        val file = android.util.AtomicFile(indexFile())
        if (!file.baseFile.isFile && !File(file.baseFile.path + ".bak").isFile) return null
        return file.openRead().bufferedReader().use { json.decodeFromString<Snapshot>(it.readText()) }
            .also { require(it.profiles.isNotEmpty()); validateProfiles(it.profiles) }
    }

    private fun writeIndex(snapshot: Snapshot) {
        validateProfiles(snapshot.profiles)
        val file = android.util.AtomicFile(indexFile())
        file.baseFile.parentFile?.mkdirs()
        val output = file.startWrite()
        try {
            output.write(json.encodeToString(snapshot).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    private val indexLockHeld = ThreadLocal<Boolean>()

    private inline fun <T> withIndexLock(block: () -> T): T = synchronized(this) {
        if (indexLockHeld.get() == true) return@synchronized block()
        check(directory().mkdirs() || directory().isDirectory)
        java.io.RandomAccessFile(File(directory(), "index.lock"), "rw").use { file ->
            file.channel.lock().use {
                indexLockHeld.set(true)
                try {
                    readIndex()?.let(::publish)
                    block()
                } finally { indexLockHeld.remove() }
            }
        }
    }

    private fun copyAvatar(sourceFileName: String, targetId: String): String? {
        val source = avatarFile(sourceFileName) ?: return null
        val fileName = "$targetId.png"
        val target = File(avatarsDirectory(), fileName)
        return runCatching {
            source.copyTo(target, overwrite = true)
            fileName
        }.getOrNull()
    }

    private fun scaleAvatar(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        if (longest <= AVATAR_SIZE) return bitmap
        val scale = AVATAR_SIZE.toFloat() / longest
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun ensureReady() {
        check(::applicationContext.isInitialized) { "AssistantRepository is not initialized" }
        if (profiles.value.isEmpty()) {
            publish(readIndex() ?: seedDefault())
        }
    }

    private fun directory(): File = File(applicationContext.filesDir, DIRECTORY_NAME)

    private fun avatarsDirectory(): File = File(directory(), AVATAR_DIR)

    private fun indexFile(): File = File(directory(), INDEX_NAME)

    @Serializable
    private data class Snapshot(
        val activeId: String,
        val profiles: List<AssistantProfile>,
    )
}

@Serializable
internal data class AssistantBackupSnapshot(
    val activeId: String,
    val profiles: List<AssistantProfile> = emptyList(),
)
