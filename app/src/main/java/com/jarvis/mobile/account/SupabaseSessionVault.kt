package com.jarvis.mobile.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts Supabase refresh/access tokens with an Android Keystore AES-GCM key.
 * Preserves existing session data if saving a new session fails, and verifies
 * read-after-write integrity synchronously.
 */
internal class SupabaseSessionVault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jarvis_auth_vault", Context.MODE_PRIVATE)
    private val alias = "jarvis_supabase_session_v1"

    private fun key(recreateIfBroken: Boolean = false): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (recreateIfBroken) {
            runCatching { store.deleteEntry(alias) }
        } else {
            (store.getKey(alias, null) as? SecretKey)?.let { return it }
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized
    fun save(value: JSONObject) {
        val serialized = value.toString()
        require(serialized.isNotBlank() && value.optString("access_token").isNotBlank()) {
            "Data sesi kosong atau tidak memiliki access_token."
        }
        val previousCiphertext = prefs.getString("ciphertext", null)
        val previousIv = prefs.getString("iv", null)

        val (encrypted, ivBytes) = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(recreateIfBroken = false))
            cipher.doFinal(serialized.toByteArray(Charsets.UTF_8)) to cipher.iv
        } catch (_: Exception) {
            // If Keystore key was invalidated by OS lockscreen change, recreate only if no valid session is lost
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(recreateIfBroken = true))
            cipher.doFinal(serialized.toByteArray(Charsets.UTF_8)) to cipher.iv
        }

        val encodedCiphertext = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        val encodedIv = Base64.encodeToString(ivBytes, Base64.NO_WRAP)

        // Verify round-trip decryption in memory before committing to SharedPreferences
        val verifyCipher = Cipher.getInstance("AES/GCM/NoPadding")
        verifyCipher.init(Cipher.DECRYPT_MODE, key(recreateIfBroken = false), GCMParameterSpec(128, ivBytes))
        val roundTrip = JSONObject(String(verifyCipher.doFinal(encrypted), Charsets.UTF_8))
        if (roundTrip.optString("access_token") != value.optString("access_token")) {
            throw IllegalStateException("Verifikasi enkripsi sesi gagal (access_token tidak cocok).")
        }

        val committed = prefs.edit()
            .putString("ciphertext", encodedCiphertext)
            .putString("iv", encodedIv)
            .commit()

        if (!committed) {
            if (previousCiphertext != null && previousIv != null) {
                prefs.edit()
                    .putString("ciphertext", previousCiphertext)
                    .putString("iv", previousIv)
                    .commit()
            }
            throw IllegalStateException("Gagal menyimpan sesi terenkripsi ke penyimpanan perangkat.")
        }
    }

    @Synchronized
    fun read(): JSONObject? = try {
        val encrypted = prefs.getString("ciphertext", null) ?: return null
        val iv = prefs.getString("iv", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(recreateIfBroken = false),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
        )
        JSONObject(String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8))
    } catch (_: Exception) {
        // Do not wipe the AndroidKeyStore key on transient read failures; return null safely.
        null
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().commit()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
    }
}