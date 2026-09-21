package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.serialization.Serializable
import org.json.JSONArray
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

@Serializable
internal data class ConversationArchiveAttachment(
    val reference: String,
    val entry: String,
    val size: Long,
    val sha256: String,
)

/** Only structured attachment fields, Eta's file-reference block and parsed Markdown destinations. */
internal object ConversationArchiveMedia {
    data class Prepared(val document: EtaConversationExport, val files: Map<String, File>)

    fun prepare(context: Context, document: EtaConversationExport): Prepared {
        val roots = listOf(
            File(context.cacheDir, "eta-chat-images"),
            File(TerminalPrivateStorage.workspace(context.filesDir), "imports"),
            File(context.filesDir, "terminal/workspace/imports"),
        ).map { it.canonicalFile }
        val mapped = linkedMapOf<String, String>()
        val files = linkedMapOf<String, File>()
        val attachments = mutableListOf<ConversationArchiveAttachment>()
        var total = 0L
        val transformed = transform(document) { original ->
            if (!isLocal(original)) original else mapped.getOrPut(original) {
                require(!original.startsWith("content:")) { "Attachment has not been saved to the app directory; cannot export completely" }
                val path = original.removePrefix("file://")
                val file = File(path)
                val root = roots.firstOrNull { file.canonicalFile.toPath().startsWith(it.toPath()) }
                    ?: error("Conversation references a local file outside the app attachment directory; import the attachment first, then export")
                val relative = file.canonicalFile.relativeTo(root).invariantSeparatorsPath
                BackupArchiveSafety.target(root, relative)
                require(file.isFile && !Files.isSymbolicLink(file.toPath())) { "Missing conversation attachment: ${file.name}" }
                require(attachments.size < 10_000) { "Too many conversation attachments" }
                val id = UUID.randomUUID().toString()
                val name = file.name
                BackupArchiveSafety.relativePath(name)
                require(name.none { it.isISOControl() || it == '<' || it == '>' }) { "Attachment file name contains unsupported characters; rename it before exporting" }
                val token = "/eta-attachments/$id/$name"
                val entry = "attachments/imports/$id/$name"
                val size = file.length()
                require(size >= 0 && size <= BackupArchiveSafety.TOTAL_LIMIT - total) { "Conversation attachments exceed the size limit" }
                total += size
                val sha = BackupDurability.digest(file)
                attachments += ConversationArchiveAttachment(token, entry, size, sha)
                files[entry] = file
                token
            }
        }
        return Prepared(transformed.copy(schemaVersion = 2, attachments = attachments,
            attachmentCount = attachments.size), files)
    }

    fun isLocal(value: String): Boolean = value.startsWith('/') || value.startsWith("file://") || value.startsWith("content:")

    fun transform(document: EtaConversationExport, path: (String) -> String): EtaConversationExport = document.copy(
        conversation = document.conversation.copy(historyJson = history(document.conversation.historyJson, path)),
        messages = document.messages.map { message ->
            message.copy(
                content = if (message.type in setOf("user", "assistant")) text(message.content, path) else message.content,
                imagesJson = images(message.imagesJson, path),
            )
        },
        contextCheckpoint = document.contextCheckpoint?.let { it.copy(historyJson = history(it.historyJson, path)) },
    )

    internal fun text(content: String, path: (String) -> String): String {
        val block = AgentFileReferencePromptCodec.parse(content)
        val request = markdown(block.request, path)
        if (block.references.isEmpty() && block.conversations.isEmpty()) return request
        return AgentFileReferencePromptCodec.format(request, block.references.map { reference ->
            require(reference.kind != AgentFileReferenceKind.Directory) { "Single-conversation backup does not support directory attachments yet; pack them into a file first" }
            reference.copy(absolutePath = path(reference.absolutePath))
        }, block.conversations)
    }

    internal fun markdown(content: String, path: (String) -> String): String {
        if (!content.contains('[')) return content
        val edits = mutableListOf<Triple<Int, Int, String>>()
        fun walk(node: ASTNode) {
            if (node.type == MarkdownElementTypes.CODE_FENCE || node.type == MarkdownElementTypes.CODE_BLOCK ||
                node.type == MarkdownElementTypes.CODE_SPAN) return
            if (node.type == MarkdownElementTypes.LINK_DESTINATION) {
                val raw = content.substring(node.startOffset, node.endOffset)
                val angle = raw.startsWith('<') && raw.endsWith('>')
                val value = if (angle) raw.substring(1, raw.length - 1) else raw
                val decoded = value.replace("\\(", "(").replace("\\)", ")").replace("\\ ", " ")
                val updated = if (isLocal(decoded)) path(decoded) else decoded
                if (updated != decoded) {
                    val encoded = if (angle || updated.contains(' ')) "<$updated>" else updated.replace("(", "\\(").replace(")", "\\)")
                    edits += Triple(node.startOffset, node.endOffset, encoded)
                }
                return
            }
            node.children.forEach(::walk)
        }
        walk(MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content))
        val result = StringBuilder(content)
        edits.sortedByDescending { it.first }.forEach { (start, end, replacement) -> result.replace(start, end, replacement) }
        return result.toString()
    }

    private fun images(raw: String, path: (String) -> String): String =
        BackupAttachmentPaths.transform(JSONArray(raw.ifBlank { "[]" }), path, true).toString()

    private fun history(raw: String, path: (String) -> String): String {
        val array = JSONArray(raw.ifBlank { "[]" })
        for (i in 0 until array.length()) {
            val message = array.getJSONObject(i)
            val role = message.optString("role")
            val isProse = role in setOf("user", "assistant")
            if (isProse && message.has("content")) message.put("content", text(message.optString("content"), path))
            val contentJson = message.optString("contentJson")
            if (contentJson.isNotBlank()) {
                val parts = JSONArray(contentJson)
                for (j in 0 until parts.length()) {
                    val part = parts.optJSONObject(j) ?: continue
                    when (part.optString("type")) {
                        "text" -> if (isProse) part.put("text", text(part.optString("text"), path))
                        "image_file", "video_file" -> part.put("path", path(part.getString("path")))
                        "image_url", "video_url" -> {
                            val type = part.getString("type")
                            val media = part.optJSONObject(type)
                            if (media != null) media.put("url", path(media.getString("url")))
                        }
                    }
                }
                message.put("contentJson", parts.toString())
            }
        }
        return array.toString()
    }
}
