package io.github.mangi.eta.agent.runtime

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Move the history body out of the Messenger Bundle.
 *
 * The sender serializes history into a temp file and hands it to Runtime via a read-only file descriptor;
 * the receiver reads the file back into a message list. The sender owns the file and cleans it up.
 */
internal object AgentRuntimeHistoryTransfer {
    private const val HISTORY_TRANSFER_DIRECTORY = "agent-runtime-history"
    private const val MAX_HISTORY_FILE_BYTES = 16 * 1024 * 1024L
    private const val STALE_FILE_AGE_MILLIS = 6 * 60 * 60 * 1_000L

    class PreparedHistory internal constructor(
        val descriptor: ParcelFileDescriptor,
        private val file: File,
    ) : Closeable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { descriptor.close() }
            runCatching { file.delete() }
        }
    }


    fun prepare(
        context: Context,
        history: List<AgentModelClient.ConversationMessage>,
    ): PreparedHistory {
        val cacheDirectory = File(context.cacheDir, HISTORY_TRANSFER_DIRECTORY).apply {
            if (!isDirectory && !mkdirs()) {
                throw IllegalStateException("Could not create the history transfer cache")
            }
        }
        cleanupStaleFiles(cacheDirectory)

        val file = File(cacheDirectory, "history-${UUID.randomUUID()}.json")
        val encoded = AgentConversationCodec.encodeTranscriptForStorage(history)
        if (encoded.length > MAX_HISTORY_FILE_BYTES) {
            throw AgentRuntimeWire.PayloadTooLargeException(encoded.length)
        }
        file.writeText(encoded, Charsets.UTF_8)
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            ?: throw IllegalStateException("Could not open the history file descriptor")
        return PreparedHistory(descriptor, file)
    }

    fun readFromBundle(bundle: Bundle): List<AgentModelClient.ConversationMessage> {
        bundle.getParcelable(AgentRuntimeWire.KEY_HISTORY_FD, ParcelFileDescriptor::class.java)
            ?.use { descriptor ->
                val bytes = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readNBytes(MAX_HISTORY_FILE_BYTES.toInt() + 1) }
                if (bytes.size > MAX_HISTORY_FILE_BYTES) {
                    throw AgentRuntimeWire.PayloadTooLargeException(bytes.size)
                }
                val raw = String(bytes, Charsets.UTF_8)
                return AgentConversationCodec.decodeTranscript(raw)
            }
        // Compatibility with old clients: history is still inlined in the Bundle.
        return bundle.getParcelableArrayList(AgentRuntimeWire.KEY_HISTORY, Bundle::class.java)
            .orEmpty()
            .map { message ->
                AgentModelClient.ConversationMessage(
                    role = message.getString(AgentRuntimeWire.KEY_ROLE).orEmpty(),
                    content = message.getString(AgentRuntimeWire.KEY_CONTENT).orEmpty(),
                    contentJson = message.getString(AgentRuntimeWire.KEY_CONTENT_JSON).orEmpty(),
                    toolCallId = message.getString(AgentRuntimeWire.KEY_TOOL_CALL_ID).orEmpty(),
                    reasoningContent = message.getString(AgentRuntimeWire.KEY_REASONING_CONTENT).orEmpty(),
                    toolCallsJson = message.getString(AgentRuntimeWire.KEY_TOOL_CALLS_JSON).orEmpty(),
                )
            }
    }

    private fun cleanupStaleFiles(directory: File) {
        val cutoff = System.currentTimeMillis() - STALE_FILE_AGE_MILLIS
        directory.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) runCatching { file.delete() }
        }
    }
}
