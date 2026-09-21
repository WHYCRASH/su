package io.github.mangi.eta.core

private const val UNKNOWN_LOG_TOKEN = "unknown"

/** Returns a stable exception type that does not carry exception messages or runtime data. */
internal fun Throwable.safeLogType(): String =
    javaClass.simpleName.takeIf { it.isNotBlank() } ?: Throwable::class.java.simpleName

/**
 * Constrains external or model-generated identifiers to low-cardinality, single-line log tokens.
 */
internal fun String?.toSafeLogToken(maxLength: Int = 64): String {
    require(maxLength > 0) { "maxLength must be greater than 0" }
    val value = this ?: return UNKNOWN_LOG_TOKEN
    if (value.isEmpty() || value.length > maxLength) return UNKNOWN_LOG_TOKEN
    return value.takeIf { token ->
        token.all { character ->
            character in 'a'..'z' ||
                character in 'A'..'Z' ||
                character in '0'..'9' ||
                character == '.' ||
                character == '_' ||
                character == '-'
        }
    } ?: UNKNOWN_LOG_TOKEN
}
