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
 * Move the final result transcript out of the Messenger Bundle.
 *
 * The sender serializes the transcript and writes it to a temporary file, then hands it to the entry process via a read-only file descriptor;
 * the receiver reads the file and restores it into a message list. The file itself is held by the sender and cleaned up only after the entry process ACKs.
 * Older clients can still fall back to reading the inline JSON from the Bundle.
 */
internal object AgentRuntimeTranscriptTransfer {
    private const val TRANSCRIPT_TRANSFER_DIRECTORY = "agent-runtime-transcript"
    private const val MAX_TRANSCRIPT_FILE_BYTES = 16 * 1024 * 1024L
    private const val STALE_FILE_AGE_MILLIS = 6 * 60 * 60 * 1_000L

    class PreparedTranscript internal constructor(
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
        transcript: List<AgentModelClient.ConversationMessage>,
    ): PreparedTranscript {
        val cacheDirectory = File(context.cacheDir, TRANSCRIPT_TRANSFER_DIRECTORY).apply {
            if (!isDirectory && !mkdirs()) {
                throw IllegalStateException("Unable to create transcript transport cache")
            }
        }
        cleanupStaleFiles(cacheDirectory)

        val file = File(cacheDirectory, "transcript-${UUID.randomUUID()}.json")
        val encoded = AgentConversationCodec.encodeTranscriptForIpc(transcript)
        val bytes = encoded.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_TRANSCRIPT_FILE_BYTES) {
            throw AgentRuntimeWire.PayloadTooLargeException(encoded.length)
        }
        file.writeBytes(bytes)
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            ?: throw IllegalStateException("Unable to open transcript file descriptor")
        return PreparedTranscript(descriptor, file)
    }

    fun readFromBundle(bundle: Bundle): List<AgentModelClient.ConversationMessage> {
        val fromFd = runCatching {
            val descriptor = bundle.getParcelable(
                AgentRuntimeWire.KEY_TRANSCRIPT_FD,
                ParcelFileDescriptor::class.java,
            ) ?: return@runCatching null
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val bytes = input.readNBytes(MAX_TRANSCRIPT_FILE_BYTES.toInt() + 1)
                if (bytes.size > MAX_TRANSCRIPT_FILE_BYTES) {
                    throw AgentRuntimeWire.PayloadTooLargeException(bytes.size)
                }
                AgentConversationCodec.decodeTranscript(String(bytes, Charsets.UTF_8))
            }
        }.getOrNull()
        if (fromFd != null) return fromFd
        // For compatibility with the old Runtime: transcript is still inlined in the Bundle.
        return AgentConversationCodec.decodeTranscript(
            bundle.getString(AgentRuntimeWire.KEY_TRANSCRIPT_JSON),
        )
    }

    private fun cleanupStaleFiles(directory: File) {
        val cutoff = System.currentTimeMillis() - STALE_FILE_AGE_MILLIS
        directory.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) runCatching { file.delete() }
        }
    }
}
