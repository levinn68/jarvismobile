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

/** Encrypts the device bearer token with an Android Keystore AES-GCM key. */
class DeviceCredentialVault(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_device_secure", Context.MODE_PRIVATE)
    private val alias = "jarvis_device_token_v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
    fun save(deviceId: String, token: String) {
        require(deviceId.matches(Regex("[A-Za-z0-9_-]{8,80}")) && token.isNotBlank())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("device_id", deviceId)
            .putString("device_token_cipher", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("device_token_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).apply()
    }
    fun deviceId(): String? = prefs.getString("device_id", null)
    fun token(): String? = try {
        val data = prefs.getString("device_token_cipher", null) ?: return null
        val iv = prefs.getString("device_token_iv", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (_: Exception) { null }
    fun clear() {
        prefs.edit().remove("device_id").remove("device_token_cipher").remove("device_token_iv").apply()
        try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) } catch (_: Exception) {}
    }
}
