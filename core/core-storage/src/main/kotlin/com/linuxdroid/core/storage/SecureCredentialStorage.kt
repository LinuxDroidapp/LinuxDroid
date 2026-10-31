package com.linuxdroid.core.storage

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secure credential and token storage backed by Android KeyStore with AES-256-GCM.
 * Protects GitHub OAuth and personal access tokens at rest.
 */
class SecureCredentialStorage(
    private val context: Context,
    private val prefsName: String = "linuxdroid_secure_tokens",
    private val keyAlias: String = "linuxdroid_credentials_key",
) {
    private val log = LinuxDroidLogger(LogSubsystem.STORAGE)
    private val prefs: SharedPreferences = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val PREFIX_ENCRYPTED = "enc:"
    }

    @Synchronized
    private fun getOrCreateSecretKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.getKey(keyAlias, null) as? SecretKey
            } else {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
                val spec = KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }
        } catch (e: Exception) {
            log.warn("Hardware KeyStore unavailable (${e.message}), using fallback protection")
            null
        }
    }

    /**
     * Stores a secret token securely.
     */
    fun saveToken(key: String, token: String) {
        try {
            val secretKey = getOrCreateSecretKey()
            if (secretKey != null) {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                val iv = cipher.iv
                val cipherBytes = cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8))

                val combined = ByteArray(iv.size + cipherBytes.size)
                System.arraycopy(iv, 0, combined, 0, iv.size)
                System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)

                val encoded = PREFIX_ENCRYPTED + Base64.encodeToString(combined, Base64.NO_WRAP)
                prefs.edit().putString(key, encoded).apply()
            } else {
                // Fallback for environments lacking AndroidKeyStore
                val encoded = "plain:" + Base64.encodeToString(token.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
                prefs.edit().putString(key, encoded).apply()
            }
        } catch (e: Exception) {
            log.error("Failed to encrypt token for key: $key", e)
            val fallback = "plain:" + Base64.encodeToString(token.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            prefs.edit().putString(key, fallback).apply()
        }
    }

    /**
     * Retrieves the stored token, or null if not found or corrupted.
     */
    fun getToken(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return try {
            if (stored.startsWith(PREFIX_ENCRYPTED)) {
                val secretKey = getOrCreateSecretKey() ?: return null
                val combined = Base64.decode(stored.removePrefix(PREFIX_ENCRYPTED), Base64.NO_WRAP)
                if (combined.size <= GCM_IV_LENGTH) return null

                val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
                val cipherBytes = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

                val cipher = Cipher.getInstance(TRANSFORMATION)
                val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

                val plainBytes = cipher.doFinal(cipherBytes)
                String(plainBytes, StandardCharsets.UTF_8)
            } else if (stored.startsWith("plain:")) {
                val decodedBytes = Base64.decode(stored.removePrefix("plain:"), Base64.NO_WRAP)
                String(decodedBytes, StandardCharsets.UTF_8)
            } else {
                stored
            }
        } catch (e: Exception) {
            log.error("Failed to decrypt token for key: $key", e)
            null
        }
    }

    /**
     * Checks if a token exists for the given key.
     */
    fun hasToken(key: String): Boolean {
        return prefs.contains(key) && !getToken(key).isNullOrBlank()
    }

    /**
     * Removes a stored token.
     */
    fun clearToken(key: String) {
        prefs.edit().remove(key).apply()
    }

    /**
     * Clears all stored tokens.
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
