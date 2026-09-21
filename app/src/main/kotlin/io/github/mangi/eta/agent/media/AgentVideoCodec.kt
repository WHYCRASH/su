package io.github.mangi.eta.agent.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import io.github.mangi.eta.agent.model.AgentModelClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

internal data class AgentVideoPreview(
    val thumbnail: AgentModelClient.ModelImage,
    val durationMs: Long,
    val width: Int?,
    val height: Int?,
    val mimeType: String,
)

internal data class AgentVideoAttachment(
    val file: File,
    val mimeType: String,
    val bytes: Int,
    val durationMs: Long,
    val width: Int?,
    val height: Int?,
    val thumbnail: AgentModelClient.ModelImage,
)

internal object AgentVideoCodec {
    val FILE_EXTENSIONS = setOf("mp4", "m4v", "mov", "webm", "mkv", "3gp", "avi")

    fun isVideoMime(mime: String): Boolean =
        mime.trim().startsWith("video/", ignoreCase = true)

    fun sniffMime(bytes: ByteArray): String? {
        if (bytes.size >= 12) {
            val brand = bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII)
            if (brand == "ftyp") return "video/mp4"
        }
        if (bytes.size >= 4 &&
            bytes[0] == 0x1A.toByte() &&
            bytes[1] == 0x45.toByte() &&
            bytes[2] == 0xDF.toByte() &&
            bytes[3] == 0xA3.toByte()
        ) {
            return "video/webm"
        }
        return null
    }

    fun looksLikeVideo(bytes: ByteArray): Boolean = sniffMime(bytes) != null

    fun sniffFile(file: File): String? {
        if (!file.isFile || file.length() <= 0L) return null
        val header = runCatching {
            file.inputStream().use { input ->
                val buffer = ByteArray(16)
                val read = input.read(buffer)
                if (read <= 0) ByteArray(0) else buffer.copyOf(read)
            }
        }.getOrNull() ?: return null
        return sniffMime(header)
    }

    fun fileFromSource(value: String): File? {
        val path = value.trim().removePrefix("file://")
        if (!path.startsWith("/")) return null
        return File(path).takeIf { it.isFile }
    }

    fun previewThumbnail(file: File, source: String): AgentModelClient.ModelImage =
        thumbnailFromFile(file, source) ?: placeholderThumbnail(source)

    fun previewFromFile(file: File, source: String): AgentVideoPreview? {
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching {
            val metadata = readMetadata(file)
            val mime = sniffFile(file) ?: when (extensionForMime("video/mp4", file.name)) {
                "webm" -> "video/webm"
                "mov" -> "video/quicktime"
                "mkv" -> "video/x-matroska"
                "3gp" -> "video/3gpp"
                else -> "video/mp4"
            }
            AgentVideoPreview(
                thumbnail = previewThumbnail(file, source),
                durationMs = metadata.durationMs,
                width = metadata.width,
                height = metadata.height,
                mimeType = mime,
            )
        }.getOrNull()
    }

    fun isVideoSource(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.startsWith("data:video/", ignoreCase = true)) return true
        val path = trimmed.removePrefix("file://")
        if (!path.startsWith("/")) return false
        val name = path.substringAfterLast('/').substringBefore('?').lowercase(Locale.US)
        val ext = name.substringAfterLast('.', "")
        return ext in FILE_EXTENSIONS
    }

    fun extensionForMime(mime: String, displayName: String = ""): String {
        val fromName = displayName.substringAfterLast('.', "").lowercase(Locale.US)
        if (fromName in FILE_EXTENSIONS) return fromName
        return when (mime.lowercase(Locale.US)) {
            "video/webm" -> "webm"
            "video/quicktime" -> "mov"
            "video/x-matroska" -> "mkv"
            "video/3gpp" -> "3gp"
            "video/x-msvideo" -> "avi"
            "video/mp4", "video/mpeg" -> "mp4"
            else -> "mp4"
        }
    }

    fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs.coerceAtLeast(0L) / 1000L).toInt()
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    fun importFromUri(context: Context, uri: Uri): AgentVideoAttachment? {
        val resolver = context.contentResolver
        val mimeHint = runCatching { resolver.getType(uri) }.getOrNull().orEmpty()
        if (mimeHint.isNotBlank() && !isVideoMime(mimeHint) && !mimeHint.equals("application/octet-stream", true)) {
            return null
        }
        val pendingDir = File(context.cacheDir, "${AgentChatImageCache.CACHE_DIRECTORY}/pending").apply { mkdirs() }
        if (!pendingDir.isDirectory) return null
        val displayName = queryDisplayName(context, uri).orEmpty()
        val mime = mimeHint.takeIf(::isVideoMime) ?: "video/mp4"
        val destination = File(pendingDir, "${UUID.randomUUID()}.${extensionForMime(mime, displayName)}")
        if (!copyUriToFile(context, uri, destination)) {
            destination.delete()
            return null
        }
        val length = destination.length()
        if (length !in 1..MAX_AGENT_VIDEO_BYTES.toLong()) {
            destination.delete()
            return null
        }
        val metadata = readMetadata(destination)
        val thumbnail = thumbnailFromFile(destination, source = "user_attach")
            ?: placeholderThumbnail("user_attach")
        return AgentVideoAttachment(
            file = destination,
            mimeType = mime,
            bytes = length.toInt(),
            durationMs = metadata.durationMs,
            width = metadata.width,
            height = metadata.height,
            thumbnail = thumbnail,
        )
    }

    fun fromVideoBytes(
        bytes: ByteArray,
        mimeType: String,
        source: String,
    ): AgentModelClient.ModelImage {
        require(bytes.isNotEmpty()) { "Video content is empty" }
        require(bytes.size <= MAX_AGENT_VIDEO_BYTES) { "Video data is too large: ${bytes.size}" }
        val mime = mimeType.takeIf(::isVideoMime) ?: "video/mp4"
        return AgentModelClient.ModelImage(
            reference = "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}",
            mimeType = mime,
            bytes = bytes.size,
            source = source,
        )
    }

    fun thumbnailFromFile(file: File, source: String): AgentModelClient.ModelImage? {
        if (!file.isFile) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
                ?: return null
            try {
                encodeJpeg(frame, source)
            } finally {
                if (!frame.isRecycled) frame.recycle()
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readMetadata(file: File): VideoMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            VideoMetadata(duration, width?.takeIf { it > 0 }, height?.takeIf { it > 0 })
        } catch (_: Throwable) {
            VideoMetadata(0L, null, null)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun copyUriToFile(context: Context, uri: Uri, destination: File): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_AGENT_VIDEO_BYTES) return@runCatching false
                    output.write(buffer, 0, read)
                }
            }
        } ?: return@runCatching false
        destination.isFile && destination.length() > 0L
    }.getOrDefault(false)

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        val name = uri.lastPathSegment?.substringAfterLast('/')
        if (!name.isNullOrBlank() && name.contains('.')) return name
        return runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index < 0) null else cursor.getString(index)
                }
        }.getOrNull()
    }

    private fun encodeJpeg(bitmap: Bitmap, source: String): AgentModelClient.ModelImage {
        val longEdge = max(bitmap.width, bitmap.height).coerceAtLeast(1)
        val scale = if (longEdge > THUMB_LONG_EDGE) THUMB_LONG_EDGE.toFloat() / longEdge else 1f
        val width = max(1, (bitmap.width * scale).roundToInt())
        val height = max(1, (bitmap.height * scale).roundToInt())
        val scaled = if (width == bitmap.width && height == bitmap.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        try {
            val output = ByteArrayOutputStream()
            check(scaled.compress(Bitmap.CompressFormat.JPEG, 80, output)) { "Failed to encode the video cover image" }
            val bytes = output.toByteArray()
            return AgentImageCodec.fromBytes(bytes, source, "image/jpeg")
        } finally {
            if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
        }
    }

    private fun placeholderThumbnail(source: String): AgentModelClient.ModelImage {
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(28, 28, 30))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            val cx = 320f
            val cy = 180f
            drawCircle(cx, cy, 48f, paint.apply { color = Color.argb(140, 0, 0, 0) })
            paint.color = Color.WHITE
            val path = android.graphics.Path().apply {
                moveTo(cx - 14f, cy - 22f)
                lineTo(cx - 14f, cy + 22f)
                lineTo(cx + 24f, cy)
                close()
            }
            drawPath(path, paint)
        }
        return try {
            encodeJpeg(bitmap, source)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private data class VideoMetadata(
        val durationMs: Long,
        val width: Int?,
        val height: Int?,
    )

    private const val THUMB_LONG_EDGE = 512
}

internal fun AgentModelClient.ModelImage.isVideoMedia(): Boolean =
    AgentVideoCodec.isVideoMime(mimeType) || reference.startsWith("data:video/", ignoreCase = true)
