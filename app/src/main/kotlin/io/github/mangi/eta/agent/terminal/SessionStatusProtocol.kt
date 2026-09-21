package io.github.mangi.eta.agent.terminal

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Persistent-shell session status-line protocol: after each command, append a printf with a random marker to the same stdin,
 * reporting the exit code and the post-execution PWD. The host splits output and tracks cwd from it; the marker embeds a random UUID,
 * so normal command output never collides with it.
 */
internal object SessionStatusProtocol {

    fun newMarker(): String = "__ETA_STATUS_${UUID.randomUUID().toString().replace("-", "")}"

    fun statusCommand(marker: String): String =
        "printf '\\n$marker:%s:%s\\n' \"\$?\" \"\$PWD\""

    /**
     * Single-logical-line protocol: the command runs via eval with the status printf on the same line, printed by the shell itself after the command exits.
     * The status line never enters stdin as its own line, so interactive commands (read, REPLs, etc.) reading stdin never swallow the marker,
     * and user input written during the session is left intact for the foreground process.
     */
    fun commandLine(marker: String, command: String): String =
        "eval ${shellQuote(command)}; eta_ec=\$?; printf '\\n$marker:%s:%s\\n' \"\$eta_ec\" \"\$PWD\""

    fun isStatusLine(line: String, marker: String): Boolean = line.startsWith("$marker:")

    /** Parse a status line; a blank cwd (empty segment) returns null so the caller falls back to the session's current cwd. */
    fun parseStatusLine(line: String, marker: String): Status? {
        if (!isStatusLine(line, marker)) return null
        val status = line.removePrefix("$marker:")
        val separator = status.indexOf(':')
        if (separator <= 0) return Status(exitCode = -1, cwd = null)
        return Status(
            exitCode = status.take(separator).toIntOrNull() ?: -1,
            cwd = status.drop(separator + 1).ifBlank { null },
        )
    }

    data class Status(val exitCode: Int, val cwd: String?)
}

/** Bounded output collector: the reader thread keeps draining the pipe, dropping further content and flagging truncation past the cap. */
internal class ByteArrayOutputCollector {
    private val output = ByteArrayOutputStream()
    private var totalBytesRead = 0L
    private var truncated = false

    fun readFrom(input: java.io.InputStream, maxBytes: Int = Int.MAX_VALUE) {
        runCatching {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                synchronized(this) {
                    totalBytesRead += read.toLong()
                    val allowed = (maxBytes - output.size()).coerceAtLeast(0)
                    if (allowed > 0) {
                        output.write(buffer, 0, read.coerceAtMost(allowed))
                    }
                    if (read > allowed) {
                        truncated = true
                    }
                }
            }
        }.onFailure { throwable ->
            if (throwable !is IOException) throw throwable
        }
    }

    fun bytes(): ByteArray = synchronized(this) { output.toByteArray() }

    fun text(): String = bytes().decodeToString()

    fun totalBytesRead(): Long = synchronized(this) { totalBytesRead }

    fun isTruncated(): Boolean = synchronized(this) { truncated }

    fun clear() {
        synchronized(this) {
            output.reset()
            totalBytesRead = 0
            truncated = false
        }
    }
}
