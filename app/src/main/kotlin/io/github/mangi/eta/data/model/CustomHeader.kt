package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

/**
 * Custom HTTP header at the provider or model level.
 *
 * Note: dangerous headers such as host / content-length / connection / transfer-encoding
 * are filtered at the network layer to avoid breaking HTTP.
 */
@Serializable
data class CustomHeader(
    val name: String,
    val value: String
)
