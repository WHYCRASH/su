package io.github.mangi.eta.agent.terminal

import java.io.File
import java.io.RandomAccessFile
import org.json.JSONObject

/** Unprivileged identity only reaches the terminal workspace, the rootless environment, and Android-authorized shared storage. */
internal object UserFileAccess {
    fun resolve(path: String): File {
        val workspace = File(TerminalRuntime.userWorkspacePath)
        val raw = path.trim().ifBlank { workspace.absolutePath }
        val file = when {
            raw == "~" -> workspace
            raw.startsWith("~/") -> File(workspace, raw.removePrefix("~/"))
            raw.startsWith('/') -> File(raw)
            else -> File(workspace, raw)
        }.canonicalFile
        val roots = listOf(workspace, File(workspace.parentFile, "proot"), File("/storage/emulated/0"))
        require(roots.any { root -> file.toPath().startsWith(root.canonicalFile.toPath()) }) { "Path is outside the range reachable by the unprivileged terminal" }
        return file
    }

    fun read(path: String, offsetBytes: Int, maxBytes: Int): String = operation {
        val file = resolve(path)
        require(file.isFile && file.canRead()) { "File is not readable" }
        val offset = offsetBytes.coerceAtLeast(0)
        val limit = maxBytes.coerceIn(1, 16_000)
        val bytes = RandomAccessFile(file, "r").use { input ->
            input.seek(offset.toLong())
            ByteArray(minOf(limit.toLong(), (input.length() - offset).coerceAtLeast(0)).toInt()).also { input.readFully(it) }
        }
        val content = bytes.decodeToString()
        JSONObject().put("ok", true).put("tool", "read_file").put("path", file.absolutePath)
            .put("offset_bytes", offset).put("bytes_read", bytes.size).put("truncated", file.length() > offset.toLong() + bytes.size || content.length > 16_000)
            .put("content", content.take(16_000))
    }

    fun write(path: String, content: String, append: Boolean): String = operation {
        val file = resolve(path)
        val bytes = content.toByteArray()
        require(bytes.size <= 512 * 1024) { "Write content is too large" }
        require(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory) { "Directory cannot be created" }
        require(!file.exists() || file.isFile) { "Target is not a regular file" }
        java.io.FileOutputStream(file, append).use { it.write(bytes) }
        JSONObject().put("ok", true).put("tool", "write_file").put("path", file.absolutePath)
            .put("mode", if (append) "append" else "overwrite").put("bytes_written", bytes.size)
    }

    fun list(path: String, showHidden: Boolean, limit: Int): String = operation {
        val directory = resolve(path)
        val entries = requireNotNull(directory.listFiles()) { "Directory is not readable" }
            .filter { showHidden || !it.name.startsWith('.') }.sortedBy { it.name }
        val selected = entries.take(limit.coerceIn(1, 200))
        val text = selected.joinToString("\n") { (if (it.isDirectory) "d " else "- ") + it.name }
        JSONObject().put("ok", true).put("tool", "list_directory").put("path", directory.absolutePath)
            .put("exit_code", 0).put("stderr", "").put("truncated", selected.size < entries.size || text.length > 16_000)
            .put("entries_text", text.take(16_000))
    }

    private inline fun operation(block: () -> JSONObject): String = try {
        block().toString()
    } catch (_: java.io.IOException) {
        error("FILE_ACCESS_DENIED", "File is not accessible; check the path and file permissions")
    } catch (_: SecurityException) {
        error("FILE_ACCESS_DENIED", "File access not authorized")
    } catch (_: IllegalArgumentException) {
        error("INVALID_PATH", "Path or file argument is outside the allowed range")
    }

    private fun error(code: String, message: String): String = JSONObject().put("ok", false).put("code", code).put("message", message).toString()
}
