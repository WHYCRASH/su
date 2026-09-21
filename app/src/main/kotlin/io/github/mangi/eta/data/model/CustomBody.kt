package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Provider- or model-level custom request body fields.
 *
 * The network layer recursively merges them into the final request JSON; model-level overrides Provider-level.
 */
@Serializable
data class CustomBody(
    val key: String,
    val value: JsonElement
)
