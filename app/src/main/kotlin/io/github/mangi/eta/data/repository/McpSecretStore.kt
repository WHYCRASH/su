package io.github.mangi.eta.data.repository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** MCP credentials are stored only as Android Keystore ciphertext in the app's private preferences. */
internal class McpSecretStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun bearerToken(serverId: String): String? {
        val encoded = preferences.getString(tokenKey(serverId), null) ?: return null
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > GCM_IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, payload, 0, GCM_IV_BYTES),
            )
            cipher.doFinal(payload, GCM_IV_BYTES, payload.size - GCM_IV_BYTES)
                .toString(Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    @Synchronized
    fun setBearerToken(serverId: String, token: String) {
        val normalized = token.trim()
        if (normalized.isBlank()) {
            clear(serverId)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + encrypted
        check(
            preferences.edit()
            .putString(tokenKey(serverId), Base64.encodeToString(payload, Base64.NO_WRAP))
            .commit()
        ) { "Failed to save MCP credentials" }
    }

    @Synchronized
    fun clear(serverId: String) {
        check(preferences.edit().remove(tokenKey(serverId)).commit()) { "Failed to delete MCP credentials" }
    }

    @Synchronized
    fun exportTokens(serverIds: Collection<String>): Map<String, String> =
        serverIds.mapNotNull { id -> bearerToken(id)?.let { id to it } }.toMap()

    @Synchronized
    fun replaceAll(tokens: Map<String, String>) {
        check(preferences.edit().clear().commit()) { "Failed to clear MCP credentials" }
        tokens.forEach { (id, token) ->
            if (id.isNotBlank() && token.isNotBlank()) setBearerToken(id, token)
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private fun tokenKey(serverId: String): String = "bearer_$serverId"

    private companion object {
        const val PREFERENCES_NAME = "eta_mcp_secrets"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "eta_mcp_credentials_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
