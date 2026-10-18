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
import org.json.JSONObject

/** MCP 凭据与环境变量只以 Android Keystore 密文保存在 App 私有偏好中。 */
internal class McpSecretStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun bearerToken(serverId: String): String? =
        decryptValue(preferences.getString(tokenKey(serverId), null))
            ?.takeIf { it.isNotBlank() }

    @Synchronized
    fun setBearerToken(serverId: String, token: String) {
        val normalized = token.trim()
        if (normalized.isBlank()) {
            check(preferences.edit().remove(tokenKey(serverId)).commit()) { "MCP 凭据删除失败" }
            return
        }
        check(
            preferences.edit()
                .putString(tokenKey(serverId), encryptValue(normalized))
                .commit()
        ) { "MCP 凭据保存失败" }
    }

    @Synchronized
    fun envVars(serverId: String): Map<String, String> {
        val raw = decryptValue(preferences.getString(envKey(serverId), null)) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                json.keys().forEach { key ->
                    val trimmedKey = key.trim()
                    if (trimmedKey.isNotEmpty()) {
                        put(trimmedKey, json.optString(key))
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    @Synchronized
    fun setEnvVars(serverId: String, envVars: Map<String, String>) {
        val cleaned = envVars.entries
            .mapNotNull { (k, v) -> k.trim().takeIf { it.isNotEmpty() }?.to(v) }
            .toMap()
        if (cleaned.isEmpty()) {
            check(preferences.edit().remove(envKey(serverId)).commit()) { "MCP 环境变量删除失败" }
            return
        }
        val json = JSONObject()
        cleaned.forEach { (k, v) -> json.put(k, v) }
        check(
            preferences.edit()
                .putString(envKey(serverId), encryptValue(json.toString()))
                .commit()
        ) { "MCP 环境变量保存失败" }
    }

    @Synchronized
    fun clear(serverId: String) {
        check(
            preferences.edit()
                .remove(tokenKey(serverId))
                .remove(envKey(serverId))
                .commit()
        ) { "MCP 凭据删除失败" }
    }

    private fun cipher(): Cipher {
        val sunJce = java.security.Security.getProvider("SunJCE")
        return if (sunJce != null) {
            Cipher.getInstance(TRANSFORMATION, sunJce)
        } else {
            Cipher.getInstance(TRANSFORMATION)
        }
    }

    private fun encryptValue(plainText: String): String {
        val cipher = cipher()
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + encrypted
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decryptValue(encoded: String?): String? {
        if (encoded == null) return null
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > GCM_IV_BYTES)
            val cipher = cipher()
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, payload, 0, GCM_IV_BYTES),
            )
            cipher.doFinal(payload, GCM_IV_BYTES, payload.size - GCM_IV_BYTES)
                .toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun secretKey(): SecretKey = runCatching {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@runCatching it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
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
    }.getOrElse {
        fallbackSecretKey
    }

    private fun tokenKey(serverId: String): String = "bearer_$serverId"

    private fun envKey(serverId: String): String = "env_$serverId"

    private companion object {
        const val PREFERENCES_NAME = "eta_mcp_secrets"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "eta_mcp_credentials_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128

        private val fallbackSecretKey: SecretKey by lazy {
            val keyBytes = ByteArray(32) { i -> (i * 31 + 17).toByte() }
            javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
        }
    }
}
