package com.actme.app.data.remote

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.actme.app.BuildConfig
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class BundledAuthManager(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getApiKey(): String {
        val cached = prefs.getString(KEY_API, "").orEmpty()
        if (cached.isNotBlank()) return cached

        val encrypted = context.assets.open("secure/bundled_auth.enc").use { it.readBytes() }
        val decrypted = decrypt(encrypted, BuildConfig.BUNDLE_KEY_PASSPHRASE)
        val root = json.parseToJsonElement(decrypted).jsonObject
        val apiKey = root["OPENAI_API_KEY"]?.jsonPrimitive?.content.orEmpty()

        prefs.edit().putString(KEY_API, apiKey).apply()
        return apiKey
    }

    private fun decrypt(cipherText: ByteArray, passphrase: String): String {
        if (cipherText.size < 16) return ""
        val iv = cipherText.sliceArray(0 until 16)
        val payload = cipherText.sliceArray(16 until cipherText.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase), IvParameterSpec(iv))
        return cipher.doFinal(payload).toString(Charsets.UTF_8)
    }

    private fun deriveKey(passphrase: String): SecretKeySpec {
        val salt = "actme-pack-salt-v1".toByteArray(Charsets.UTF_8)
        val spec: KeySpec = PBEKeySpec(passphrase.toCharArray(), salt, 120_000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    companion object {
        private const val PREF_NAME = "actme_secure_pref"
        private const val KEY_API = "openai_api_key"
    }
}
