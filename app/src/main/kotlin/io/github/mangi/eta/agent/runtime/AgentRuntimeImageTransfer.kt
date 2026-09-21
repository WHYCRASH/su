package io.github.mangi.eta.agent.runtime

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Base64
import android.util.Base64InputStream
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.media.AgentVideoCodec
import io.github.mangi.eta.agent.media.MAX_AGENT_VIDEO_BYTES
import io.github.mangi.eta.agent.media.isVideoMedia
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Move model image bodies out of the Messenger bundle.
 *
 * The sender only stages images in its own cache directory and hands them to the runtime through a read-only file descriptor; the receiver reads them in the background,
 * then restores the data URLs the model protocol needs. Remote HTTP(S) URLs never touch disk and pass straight through.
 */
internal object AgentRuntimeImageTransfer {
    private const val MAX_IMAGE_COUNT = 8
    private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
    private const val MAX_VIDEO_BYTES = MAX_AGENT_VIDEO_BYTES
    private const val MAX_TOTAL_MEDIA_BYTES = 48 * 1024 * 1024
    private const val MAX_REMOTE_URL_CHARS = 16 * 1024
    private const val MAX_ENCODED_IMAGE_CHARS = MAX_IMAGE_BYTES * 2
    private const val CACHE_DIRECTORY = "agent-runtime-transfer"
    private const val STALE_FILE_AGE_MILLIS = 6 * 60 * 60 * 1_000L

    private fun AgentModelClient.ModelImage.itemLimit(): Int =
        if (isVideoMedia()) MAX_VIDEO_BYTES else MAX_IMAGE_BYTES

    private fun sizeExceededMessage(maxBytes: Int, video: Boolean): String =
        if (video) {
            "A single video must not exceed ${maxBytes / 1024 / 1024} MiB"
        } else {
            "A single image must not exceed ${maxBytes / 1024 / 1024} MiB"
        }

    class ImageTransferException(
        message: String,
        cause: Throwable? = null,
    ) : IllegalArgumentException(message, cause)

    class PreparedImages internal constructor(
        val images: List<AgentRuntimeWire.WireImage>,
        private val files: List<File>,
    ) : Closeable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            images.forEach { image -> runCatching { image.fileDescriptor?.close() } }
            files.forEach { file -> runCatching { file.delete() } }
        }
    }

    fun prepare(
        context: Context,
        images: List<AgentModelClient.ModelImage>,
    ): PreparedImages {
        if (images.size > MAX_IMAGE_COUNT) {
            throw ImageTransferException("At most $MAX_IMAGE_COUNT images per request")
        }

        val cacheDirectory = File(context.cacheDir, CACHE_DIRECTORY)
        if (!cacheDirectory.isDirectory && !cacheDirectory.mkdirs()) {
            throw ImageTransferException("Cannot create the image transfer cache")
        }
        cleanupStaleFiles(cacheDirectory)

        val files = mutableListOf<File>()
        val wireImages = mutableListOf<AgentRuntimeWire.WireImage>()
        var totalBytes = 0L
        try {
            images.forEach { image ->
                if (image.reference.isRemoteUrl()) {
                    if (image.reference.length > MAX_REMOTE_URL_CHARS) {
                        throw ImageTransferException("Remote image URL is too long")
                    }
                    wireImages += image.toWireImage(remoteUrl = image.reference)
                    return@forEach
                }

                val itemLimit = image.itemLimit()
                val video = image.isVideoMedia()
                val directDescriptor = openDirectDescriptor(context, image.reference)
                val directSize = directDescriptor?.statSize ?: -1L
                if (directSize > itemLimit) {
                    directDescriptor?.close()
                    throw ImageTransferException(sizeExceededMessage(itemLimit, video))
                }
                val descriptor: ParcelFileDescriptor
                val imageBytes: Long
                if (directDescriptor != null && directSize > 0L) {
                    descriptor = directDescriptor
                    imageBytes = directSize
                } else {
                    directDescriptor?.close()
                    val transferFile = File(
                        cacheDirectory,
                        "image-${UUID.randomUUID()}.bin",
                    )
                    files += transferFile
                    copyReferenceToFile(context, image.reference, transferFile, itemLimit)
                    imageBytes = transferFile.length()
                    descriptor = ParcelFileDescriptor.open(
                        transferFile,
                        ParcelFileDescriptor.MODE_READ_ONLY,
                    )
                }
                if (imageBytes <= 0L) {
                    descriptor.close()
                    throw ImageTransferException("Image content is empty")
                }
                if (imageBytes > itemLimit) {
                    descriptor.close()
                    throw ImageTransferException(sizeExceededMessage(itemLimit, video))
                }
                totalBytes += imageBytes
                if (totalBytes > MAX_TOTAL_MEDIA_BYTES) {
                    descriptor.close()
                    throw ImageTransferException("Total image size must not exceed ${MAX_TOTAL_MEDIA_BYTES / 1024 / 1024} MiB")
                }
                wireImages += image.toWireImage(
                    fileDescriptor = descriptor,
                    bytes = imageBytes.toInt(),
                )
            }
            return PreparedImages(wireImages, files)
        } catch (throwable: Throwable) {
            PreparedImages(wireImages, files).close()
            if (throwable is ImageTransferException) throw throwable
            throw ImageTransferException("Cannot read the image to send", throwable)
        }
    }

    /** Must be called on the runtime background thread; consumes and closes the file descriptor held by [incoming]. */
    fun materialize(
        incoming: AgentRuntimeWire.IncomingRunRequest,
    ): AgentRuntimeWire.RunRequest = incoming.use { request ->
        if (request.images.size > MAX_IMAGE_COUNT) {
            throw ImageTransferException("At most $MAX_IMAGE_COUNT images per request")
        }

        var totalBytes = 0L
        val images = request.images.mapIndexed { index, image ->
            image.fileDescriptor?.let { descriptor ->
                val startedAt = SystemClock.elapsedRealtime()
                val video = AgentVideoCodec.isVideoMime(image.mimeType)
                val itemLimit = if (video) MAX_VIDEO_BYTES else MAX_IMAGE_BYTES
                val statSize = descriptor.statSize
                if (statSize <= 0L) {
                    throw ImageTransferException("Image file descriptor has no valid size")
                }
                if (statSize > itemLimit) {
                    throw ImageTransferException(sizeExceededMessage(itemLimit, video))
                }
                val bytes = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    input.readBytesLimited(itemLimit)
                }
                if (bytes.size.toLong() != statSize) {
                    throw ImageTransferException("Image transfer is incomplete")
                }
                totalBytes += bytes.size
                if (totalBytes > MAX_TOTAL_MEDIA_BYTES) {
                    throw ImageTransferException("Total image size must not exceed ${MAX_TOTAL_MEDIA_BYTES / 1024 / 1024} MiB")
                }
                val materialized = if (video) {
                    AgentVideoCodec.fromVideoBytes(
                        bytes = bytes,
                        mimeType = image.mimeType,
                        source = image.source,
                    )
                } else {
                    AgentImageCodec.fromAttachmentBytes(
                        bytes = bytes,
                        source = image.source,
                        mimeHint = image.mimeType,
                    )
                }
                return@mapIndexed materialized.also { encoded ->
                    AndroidAgentLogger.debug {
                        "Agent image action=materialize index=$index " +
                            "input_bytes=${bytes.size} output_bytes=${encoded.bytes} " +
                            "output=${encoded.width}x${encoded.height} " +
                            "elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}"
                    }
                }
            }

            val reference = image.remoteUrl.orEmpty()
            if (reference.isRemoteUrl()) {
                if (reference.length > MAX_REMOTE_URL_CHARS) {
                    throw ImageTransferException("Remote image URL is too long")
                }
                return@mapIndexed AgentModelClient.ModelImage(
                    reference = reference,
                    mimeType = image.mimeType,
                    bytes = image.bytes,
                    width = image.width,
                    height = image.height,
                    source = image.source,
                )
            }
            if (reference.length > MAX_ENCODED_IMAGE_CHARS) {
                throw ImageTransferException("Inline image data is too large")
            }
            AgentImageCodec.fromReference(
                context = null,
                value = reference,
                source = image.source,
            ) ?: throw ImageTransferException("Cannot read the image in the legacy protocol")
        }
        request.request.copy(images = images)
    }

    private fun AgentModelClient.ModelImage.toWireImage(
        remoteUrl: String? = null,
        fileDescriptor: ParcelFileDescriptor? = null,
        bytes: Int = this.bytes,
    ): AgentRuntimeWire.WireImage = AgentRuntimeWire.WireImage(
        remoteUrl = remoteUrl,
        fileDescriptor = fileDescriptor,
        mimeType = mimeType,
        bytes = bytes,
        width = width,
        height = height,
        source = source,
    )

    private fun copyReferenceToFile(
        context: Context,
        reference: String,
        outputFile: File,
        maxBytes: Int,
    ) {
        val input = when {
            reference.startsWith("data:image/", ignoreCase = true) ||
                reference.startsWith("data:video/", ignoreCase = true) ->
                reference.openDataUrlStream()

            Uri.parse(reference).scheme in setOf("content", "file") ->
                context.contentResolver.openInputStream(Uri.parse(reference))
                    ?: throw ImageTransferException("Cannot open the local image")

            else -> {
                val file = File(reference)
                if (!file.isFile) throw ImageTransferException("Image reference is not readable")
                file.inputStream()
            }
        }
        input.use { source ->
            FileOutputStream(outputFile).use { target ->
                source.copyToLimited(target, maxBytes)
            }
        }
    }

    private fun openDirectDescriptor(
        context: Context,
        reference: String,
    ): ParcelFileDescriptor? = runCatching {
        val uri = Uri.parse(reference)
        when (uri.scheme) {
            "content" -> context.contentResolver.openFileDescriptor(uri, "r")
            "file" -> uri.path
                ?.let(::File)
                ?.takeIf(File::isFile)
                ?.let { file ->
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                }
            null, "" -> File(reference)
                .takeIf(File::isFile)
                ?.let { file ->
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                }
            else -> null
        }
    }.getOrNull()

    private fun String.openDataUrlStream(): InputStream {
        val separator = indexOf(',')
        if (separator <= 0 || !substring(0, separator).endsWith(";base64", ignoreCase = true)) {
            throw ImageTransferException("Unsupported inline image format")
        }
        val encoded = substring(separator + 1)
        if (encoded.length > MAX_ENCODED_IMAGE_CHARS) {
            throw ImageTransferException("Inline image data is too large")
        }
        return Base64InputStream(
            ByteArrayInputStream(encoded.toByteArray(Charsets.US_ASCII)),
            Base64.DEFAULT,
        )
    }

    private fun InputStream.copyToLimited(
        output: FileOutputStream,
        maxBytes: Int,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) return
            total += read
            if (total > maxBytes) {
                throw ImageTransferException("A single image must not exceed ${maxBytes / 1024 / 1024} MiB")
            }
            output.write(buffer, 0, read)
        }
    }

    private fun InputStream.readBytesLimited(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 256 * 1024))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) return output.toByteArray()
            total += read
            if (total > maxBytes) {
                throw ImageTransferException("A single image must not exceed ${maxBytes / 1024 / 1024} MiB")
            }
            output.write(buffer, 0, read)
        }
    }

    private fun String.isRemoteUrl(): Boolean =
        startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true)

    private fun cleanupStaleFiles(directory: File) {
        val cutoff = System.currentTimeMillis() - STALE_FILE_AGE_MILLIS
        runCatching {
            directory.listFiles().orEmpty()
                .asSequence()
                .filter { file -> file.isFile && file.lastModified() < cutoff }
                .forEach { file -> file.delete() }
        }
    }
}
