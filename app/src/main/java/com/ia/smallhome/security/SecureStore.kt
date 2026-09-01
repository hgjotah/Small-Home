package com.ia.smallhome.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStore(context: Context) {
    private val preferences = context.getSharedPreferences("small_home_secure", Context.MODE_PRIVATE)

    fun put(key: SecretKeyName, value: String) {
        if (value.isBlank()) {
            remove(key)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + ciphertext
        preferences.edit { putString(key.storageName, Base64.encodeToString(packed, Base64.NO_WRAP)) }
    }

    fun get(key: SecretKeyName): String? {
        val encoded = preferences.getString(key.storageName, null) ?: return null
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > IV_SIZE)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, packed.copyOfRange(0, IV_SIZE)))
            String(cipher.doFinal(packed.copyOfRange(IV_SIZE, packed.size)), Charsets.UTF_8)
        }.getOrElse {
            preferences.edit { remove(key.storageName) }
            null
        }
    }

    fun contains(key: SecretKeyName): Boolean = get(key) != null

    fun remove(key: SecretKeyName) {
        preferences.edit { remove(key.storageName) }
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    enum class SecretKeyName(val storageName: String) {
        HOME_ASSISTANT_TOKEN("home_assistant_token"),
        COINMARKETCAP_KEY("coinmarketcap_key"),
        OPENROUTER_KEY("openrouter_key"),
    }

    private companion object {
        const val KEY_ALIAS = "small_home_aes_gcm_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
