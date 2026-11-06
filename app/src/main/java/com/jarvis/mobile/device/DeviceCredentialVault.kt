package com.jarvis.mobile.device

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the device bearer token and Google-linked account token with an Android Keystore AES-GCM key.
 * All operations are synchronized for multi-thread safety across UI, SyncService, and ApprovalActivity,
 * and recover cleanly if the Keystore entry is invalidated by an OS lockscreen credential change.
 */
class DeviceCredentialVault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jarvis_device_secure", Context.MODE_PRIVATE)
    private val alias = "jarvis_device_token_v1"

    private fun key(recreateIfBroken: Boolean = false): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (recreateIfBroken) {
            runCatching { store.deleteEntry(alias) }
        } else {
            (store.getKey(alias, null) as? SecretKey)?.let { return it }
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encryptBytes(plainText: String): Pair<ByteArray, ByteArray> {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(recreateIfBroken = false))
            cipher.doFinal(plainText.toByteArray(Charsets.UTF_8)) to cipher.iv
        } catch (_: Exception) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(recreateIfBroken = true))
            cipher.doFinal(plainText.toByteArray(Charsets.UTF_8)) to cipher.iv
        }    }

    @Synchronized
    fun save(deviceId: String, token: String) {
        require(deviceId.matches(Regex("[A-Za-z0-9_-]{8,80}")) && token.isNotBlank())
        val (encrypted, iv) = encryptBytes(token)
        prefs.edit()
            .putString("device_id", deviceId)
            .putString("device_token_cipher", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("device_token_iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .commit()
    }

    @Synchronized
    fun deviceId(): String? = prefs.getString("device_id", null)

    @Synchronized
    fun saveAccountToken(token: String, supabaseUserId: String) {
        require(token.isNotBlank() && token.length <= 4096)
        val normalizedUserId = supabaseUserId.trim().removePrefix("acct-")
        require(normalizedUserId.matches(Regex("[A-Fa-f0-9-]{36}"))) { "A valid Supabase user ID is required" }
        val (encrypted, iv) = encryptBytes(token)
        prefs.edit()
            .putString("account_token_cipher", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("account_token_iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString("account_supabase_user_id", normalizedUserId)
            .commit()
    }

    @Synchronized
    fun accountUserId(): String? = prefs.getString("account_supabase_user_id", null)

    @Synchronized
    fun accountToken(expectedSupabaseUserId: String? = null): String? {
        return try {
            val storedUserId = accountUserId()
            val cleanExpected = expectedSupabaseUserId?.trim()?.removePrefix("acct-")
            if (!cleanExpected.isNullOrBlank() && storedUserId != cleanExpected) return null
            val data = prefs.getString("account_token_cipher", null) ?: return null
            val iv = prefs.getString("account_token_iv", null) ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(recreateIfBroken = false), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun token(): String? {
        return try {
            val data = prefs.getString("device_token_cipher", null) ?: return null
            val iv = prefs.getString("device_token_iv", null) ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(recreateIfBroken = false), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun clearAccountToken() {
        prefs.edit()
            .remove("account_token_cipher")
            .remove("account_token_iv")
            .remove("account_supabase_user_id")
            .commit()
    }

    @Synchronized
    fun clear() {
        prefs.edit()
            .remove("device_id")
            .remove("device_token_cipher")
            .remove("device_token_iv")
            .remove("account_token_cipher")
            .remove("account_token_iv")
            .remove("account_supabase_user_id")
            .commit()
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        } catch (_: Exception) {}
    }
}