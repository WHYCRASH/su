package io.github.mangi.eta.agent.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Base64
import io.github.mangi.eta.agent.model.AgentModelClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.math.min
import kotlin.math.sqrt

internal const val MAX_AGENT_IMAGE_BYTES = 12 * 1024 * 1024
internal const val MAX_AGENT_VIDEO_BYTES = 32 * 1024 * 1024

/** Only generates independent image copies for tool screenshots and chat previews. */
internal object AgentModelImageEncoder {
    // In lossless WebP mode, quality means encoding effort; no pixel data is lost.
    private const val SCREEN_WEBP_EFFORT = 75
    private const val PREVIEW_JPEG_QUALITY = 80

    private data class EncodingProfile(
        val format: Bitmap.CompressFormat,
        val mimeType: String,
        val quality: Int,
        val maxLongEdge: Int? = null,
        val maxPixels: Long? = null,
    )

    private data class ImageBounds(
        val width: Int,
        val height: Int,
        val mimeType: String,
    )

    private data class TargetSize(val width: Int, val height: Int)

    private val screenProfile = EncodingProfile(
        format = Bitmap.CompressFormat.WEBP_LOSSLESS,
        mimeType = "image/webp",
        quality = SCREEN_WEBP_EFFORT,
    )
    private val previewProfile = EncodingProfile(
        maxLongEdge = 512,
        maxPixels = 256_000L,
        format = Bitmap.CompressFormat.JPEG,
        mimeType = "image/jpeg",
        quality = PREVIEW_JPEG_QUALITY,
    )
    private val toolVisionProfile = EncodingProfile(
        maxLongEdge = 1_600,
        maxPixels = 1_500_000L,
        format = Bitmap.CompressFormat.JPEG,
        mimeType = "image/jpeg",
        quality = 82,
    )

    fun screen(
        bytes: ByteArray,
        source: String,
        mimeHint: String,
    ): AgentModelClient.ModelImage? {
        if (!bytes.hasSupportedImageMagic()) return null
        val encoded = transcodeBytes(
            bytes = bytes,
            source = source,
            bounds = inspectBounds(bytes, mimeHint),
            profile = screenProfile,
        )
        // Root screencaps are already compressed images; only replace them when lossless WebP is actually smaller.
        return encoded?.takeIf { it.bytes < bytes.size }
    }

    fun screen(
        bitmap: Bitmap,
        source: String,
    ): AgentModelClient.ModelImage =
        encodeBitmap(bitmap, source, screenProfile, flattenAlpha = false)

    /** Assistant-entry screenshots go directly into network requests; vision-model sizing keeps full-screen lossless images from bloating the request body. */
    fun screenContext(
        bitmap: Bitmap,
        source: String,
    ): AgentModelClient.ModelImage =
        encodeBitmap(bitmap, source, toolVisionProfile, flattenAlpha = true)

    /** File-tool images are only downscaled and compressed right before sending to the model, keeping multi-image requests small. */
    fun toolVision(
        bytes: ByteArray,
        source: String,
        mimeHint: String = "image/jpeg",
    ): AgentModelClient.ModelImage? = runCatching {
        if (!bytes.hasSupportedImageMagic()) return@runCatching null
        transcodeBytes(
            bytes = bytes,
            source = source,
            bounds = inspectBounds(bytes, mimeHint),
            profile = toolVisionProfile,
        )
    }.getOrNull()

    fun preview(
        context: Context,
        image: AgentModelClient.ModelImage,
    ): AgentModelClient.ModelImage? = runCatching {
        val width = image.width ?: return@runCatching null
        val height = image.height ?: return@runCatching null
        val target = targetSize(width, height, previewProfile)
        val openStream = referenceStreamFactory(context, image.reference) ?: return@runCatching null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize(width, height, target)
        }
        val decoded = openStream().use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: return@runCatching null
        try {
            encodeBitmap(decoded, image.source, previewProfile, flattenAlpha = true)
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }.getOrNull()

    private fun transcodeBytes(
        bytes: ByteArray,
        source: String,
        bounds: ImageBounds,
        profile: EncodingProfile,
    ): AgentModelClient.ModelImage? {
        if (bounds.width <= 0 || bounds.height <= 0) return null
        val target = bounds.targetSize(profile)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize(bounds.width, bounds.height, target)
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return try {
            encodeBitmap(
                bitmap = bitmap,
                source = source,
                profile = profile,
                flattenAlpha = profile.format == Bitmap.CompressFormat.JPEG &&
                    !bounds.mimeType.equals("image/jpeg", ignoreCase = true),
            )
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun encodeBitmap(
        bitmap: Bitmap,
        source: String,
        profile: EncodingProfile,
        flattenAlpha: Boolean,
    ): AgentModelClient.ModelImage {
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) { "Image bitmap is unavailable" }
        val encodedBitmap = renderBitmap(
            bitmap = bitmap,
            target = targetSize(bitmap.width, bitmap.height, profile),
            flattenAlpha = flattenAlpha,
        )
        try {
            val initialCapacity = minOf(
                encodedBitmap.width * encodedBitmap.height / 4,
                2 * 1024 * 1024,
            ).coerceAtLeast(32 * 1024)
            val output = ByteArrayOutputStream(initialCapacity)
            check(encodedBitmap.compress(profile.format, profile.quality, output)) {
                "Image encoding failed"
            }
            val encoded = output.toByteArray()
            require(encoded.isNotEmpty() && encoded.size <= MAX_AGENT_IMAGE_BYTES) {
                "Image data too large: ${encoded.size}"
            }
            return AgentModelClient.ModelImage(
                reference = "data:${profile.mimeType};base64,${Base64.encodeToString(encoded, Base64.NO_WRAP)}",
                mimeType = profile.mimeType,
                bytes = encoded.size,
                width = encodedBitmap.width,
                height = encodedBitmap.height,
                source = source,
            )
        } finally {
            if (encodedBitmap !== bitmap && !encodedBitmap.isRecycled) {
                encodedBitmap.recycle()
            }
        }
    }

    private fun renderBitmap(
        bitmap: Bitmap,
        target: TargetSize,
        flattenAlpha: Boolean,
    ): Bitmap {
        if (
            target.width == bitmap.width &&
            target.height == bitmap.height &&
            !flattenAlpha
        ) {
            return bitmap
        }
        return Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888).also { output ->
            val canvas = Canvas(output)
            if (flattenAlpha) canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(
                bitmap,
                Rect(0, 0, bitmap.width, bitmap.height),
                Rect(0, 0, target.width, target.height),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG),
            )
            output.setHasAlpha(false)
        }
    }

    private fun inspectBounds(bytes: ByteArray, mimeHint: String): ImageBounds {
        require(bytes.isNotEmpty()) { "Image content is empty" }
        require(bytes.size <= MAX_AGENT_IMAGE_BYTES) { "Image data too large: ${bytes.size}" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return ImageBounds(
            width = options.outWidth,
            height = options.outHeight,
            mimeType = options.outMimeType
                ?.takeIf { it.isNotBlank() }
                ?: mimeHint.normalizedAgentImageMimeType(),
        )
    }

    private fun ImageBounds.targetSize(profile: EncodingProfile): TargetSize =
        targetSize(width, height, profile)

    private fun targetSize(
        width: Int,
        height: Int,
        profile: EncodingProfile,
    ): TargetSize {
        if (width <= 0 || height <= 0) return TargetSize(width, height)
        var scale = profile.maxLongEdge
            ?.let { limit -> min(1.0, limit.toDouble() / maxOf(width, height)) }
            ?: 1.0
        val scaledPixels = width.toDouble() * height * scale * scale
        profile.maxPixels?.let { limit ->
            if (scaledPixels > limit) {
                scale *= sqrt(limit / scaledPixels)
            }
        }
        return TargetSize(
            width = (width * scale).toInt().coerceIn(1, width),
            height = (height * scale).toInt().coerceIn(1, height),
        )
    }

    private fun sampleSize(
        width: Int,
        height: Int,
        target: TargetSize,
    ): Int {
        var sample = 1
        while (sample <= 64) {
            val next = sample * 2
            val nextWidth = width.toDouble() / next
            val nextHeight = height.toDouble() / next
            if (
                nextWidth < target.width * 0.9 ||
                nextHeight < target.height * 0.9
            ) {
                break
            }
            sample = next
        }
        return sample
    }

    private fun referenceStreamFactory(
        context: Context,
        reference: String,
    ): (() -> InputStream)? {
        val trimmed = reference.trim()
        if (trimmed.startsWith("data:image/", ignoreCase = true)) {
            val marker = trimmed.indexOf("base64,", ignoreCase = true)
            if (marker < 0) return null
            val decoded = Base64.decode(trimmed.substring(marker + "base64,".length), Base64.DEFAULT)
            if (decoded.isEmpty() || decoded.size > MAX_AGENT_IMAGE_BYTES) return null
            return { ByteArrayInputStream(decoded) }
        }
        val uri = Uri.parse(trimmed)
        if (uri.scheme == "content") {
            return {
                context.contentResolver.openInputStream(uri)
                    ?: error("Unable to open the local image")
            }
        }
        val file = when (uri.scheme) {
            "file" -> uri.path?.let(::File)
            null, "" -> File(trimmed)
            else -> null
        } ?: return null
        if (!file.isFile) return null
        return { file.inputStream() }
    }
}

internal fun String.normalizedAgentImageMimeType(): String =
    substringBefore(';')
        .trim()
        .takeIf {
            it.startsWith("image/", ignoreCase = true) &&
                !it.equals("image/*", ignoreCase = true)
        }
        ?: "image/jpeg"

internal fun ByteArray.sniffAgentImageMimeType(): String {
    if (size >= 2 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()) return "image/jpeg"
    if (
        size >= 4 &&
        this[0] == 0x89.toByte() && this[1] == 0x50.toByte() &&
        this[2] == 0x4E.toByte() && this[3] == 0x47.toByte()
    ) {
        return "image/png"
    }
    if (
        size >= 3 &&
        this[0] == 'G'.code.toByte() && this[1] == 'I'.code.toByte() &&
        this[2] == 'F'.code.toByte()
    ) {
        return "image/gif"
    }
    if (
        size >= 12 &&
        this[0] == 'R'.code.toByte() && this[1] == 'I'.code.toByte() &&
        this[2] == 'F'.code.toByte() && this[3] == 'F'.code.toByte() &&
        this[8] == 'W'.code.toByte() && this[9] == 'E'.code.toByte() &&
        this[10] == 'B'.code.toByte() && this[11] == 'P'.code.toByte()
    ) {
        return "image/webp"
    }
    if (
        size >= 12 &&
        this[4] == 'f'.code.toByte() && this[5] == 't'.code.toByte() &&
        this[6] == 'y'.code.toByte() && this[7] == 'p'.code.toByte()
    ) {
        val brand = String(this, 8, 4, Charsets.US_ASCII).lowercase()
        return if (brand in setOf("avif", "avis")) "image/avif" else "image/heic"
    }
    return "image/png"
}

internal fun ByteArray.hasSupportedImageMagic(): Boolean {
    if (size < 8) return false
    if (this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()) return true
    if (
        this[0] == 0x89.toByte() && this[1] == 0x50.toByte() &&
        this[2] == 0x4E.toByte() && this[3] == 0x47.toByte()
    ) {
        return true
    }
    if (
        this[0] == 'G'.code.toByte() && this[1] == 'I'.code.toByte() &&
        this[2] == 'F'.code.toByte()
    ) {
        return true
    }
    if (
        size >= 12 &&
        this[0] == 'R'.code.toByte() && this[1] == 'I'.code.toByte() &&
        this[2] == 'F'.code.toByte() && this[3] == 'F'.code.toByte() &&
        this[8] == 'W'.code.toByte() && this[9] == 'E'.code.toByte() &&
        this[10] == 'B'.code.toByte() && this[11] == 'P'.code.toByte()
    ) {
        return true
    }
    if (
        size >= 12 &&
        this[4] == 'f'.code.toByte() && this[5] == 't'.code.toByte() &&
        this[6] == 'y'.code.toByte() && this[7] == 'p'.code.toByte()
    ) {
        val brand = String(this, 8, 4, Charsets.US_ASCII)
        return brand in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "avif", "avis")
    }
    return false
}
