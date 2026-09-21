package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import java.io.File
import java.util.UUID

/** Single-conversation import is always a copy. No existing IDs or shared attachment paths are reused. */
internal object ConversationArchiveImport {
    data class Plan(val document: EtaConversationExport, val files: Map<File, File>)

    fun prepare(context: Context, exported: EtaConversationExport, staged: Map<String, File>): Plan {
        val id = "conv-${UUID.randomUUID()}"
        val root = BackupArchiveSafety.target(File(TerminalPrivateStorage.workspace(context.filesDir), "imports"), id)
        require(!root.exists()) { "Import directory already exists" }
        val files = linkedMapOf<File, File>()
        val mapping = linkedMapOf<String, String>()
        val legacyTargets = mutableMapOf<String, String>()
        if (exported.schemaVersion >= 2) {
            require(exported.attachments.size <= 10_000) { "Too many attachments" }
            require(exported.attachmentCount == exported.attachments.size) { "Attachment count does not match the manifest" }
            val entries = exported.attachments.map { it.entry }
            val refs = exported.attachments.map { it.reference }
            require(entries.distinct().size == entries.size && refs.distinct().size == refs.size) { "Duplicate entries in the attachment manifest" }
            require(staged.keys == entries.toSet() + EtaConversationExport.MANIFEST_NAME) { "Archive attachments do not match the manifest" }
            exported.attachments.forEach { attachment ->
                require(attachment.reference.startsWith("/eta-attachments/") &&
                    attachment.sha256.matches(Regex("[0-9a-f]{64}")) &&
                    attachment.size in 0..BackupArchiveSafety.TOTAL_LIMIT) { "Invalid attachment reference, size, or checksum" }
                val relative = attachment.reference.removePrefix("/eta-attachments/")
                BackupArchiveSafety.relativePath(relative)
                require(attachment.entry == "attachments/imports/$relative") { "Attachment archive path mismatch" }
                val source = staged.getValue(attachment.entry)
                require(source.length() == attachment.size && BackupDurability.digest(source) == attachment.sha256) { "Attachment size or checksum mismatch" }
                val target = BackupArchiveSafety.target(root, relative)
                mapping[attachment.reference] = target.absolutePath
                files[target] = source
            }
        } else {
            require(staged.keys.all { it == EtaConversationExport.MANIFEST_NAME ||
                it.startsWith("attachments/imports/") || it.startsWith("attachments/chat-images/${exported.conversation.id}/") }) {
                "Legacy conversation archive contains other conversations or workspace data"
            }
        }
        val used = hashSetOf<String>()
        val rewritten = ConversationArchiveMedia.transform(exported) { reference ->
            when {
                !ConversationArchiveMedia.isLocal(reference) -> reference
                exported.schemaVersion >= 2 -> {
                    used += reference
                    mapping[reference] ?: error("Message references a local attachment missing from the manifest")
                }
                else -> mapping.getOrPut(reference) {
                    val entry = legacyEntry(reference, exported.conversation.id)
                        ?: error("Legacy archive has a local attachment reference that cannot be migrated")
                    legacyTargets.getOrPut(entry) {
                        val source = staged[entry] ?: error("Legacy archive is missing an attachment: ${entry.substringAfterLast('/')}")
                        val relative = "${UUID.randomUUID()}/${entry.substringAfterLast('/')}"
                        val target = BackupArchiveSafety.target(root, relative)
                        files[target] = source
                        target.absolutePath
                    }
                }
            }
        }
        if (exported.schemaVersion >= 2) require(used == mapping.keys) { "Attachment manifest contains unreferenced files" }
        val copied = rewritten.copy(
            conversation = rewritten.conversation.copy(id = id, folderId = "",
                appliedRuntimeRunIdsJson = "[]", isPinned = false),
            messages = rewritten.messages.mapIndexed { index, message -> message.copy(
                id = "import-${UUID.randomUUID()}", conversationId = id, sortIndex = index,
            ) },
            contextCheckpoint = rewritten.contextCheckpoint?.copy(conversationId = id),
            attachmentCount = files.size,
        )
        return Plan(copied, files)
    }

    internal fun legacyEntry(reference: String, conversationId: String): String? {
        val path = reference.removePrefix("file://")
        val suffix = Regex("^/data/(?:user/\\d+|data)/[^/]+/(.*)$").matchEntire(path)?.groupValues?.get(1) ?: return null
        val entry = when {
            suffix.startsWith("cache/eta-chat-images/$conversationId/") -> "attachments/chat-images/" + suffix.removePrefix("cache/eta-chat-images/")
            suffix.startsWith("files/terminal-user/workspace/imports/") -> "attachments/imports/" + suffix.removePrefix("files/terminal-user/workspace/imports/")
            suffix.startsWith("files/terminal/workspace/imports/") -> "attachments/imports/" + suffix.removePrefix("files/terminal/workspace/imports/")
            else -> return null
        }
        BackupArchiveSafety.relativePath(entry)
        return entry
    }
}
