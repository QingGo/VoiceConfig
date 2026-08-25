package com.voiceconfig.data.local.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class EncryptedToken(
    val ciphertext: String,
    val iv: String,
)

interface RemoteNodeTokenCipher {
    fun encrypt(plain: String): EncryptedToken
    fun decrypt(ciphertext: String, iv: String): String
}

class KeystoreRemoteNodeTokenCipher : RemoteNodeTokenCipher {

    private val keyAlias = "voiceconfig_remote_node_token_key"
    private val transformation = "AES/GCM/NoPadding"

    override fun encrypt(plain: String): EncryptedToken {
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val bytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return EncryptedToken(
            ciphertext = Base64.encodeToString(bytes, Base64.NO_WRAP),
            iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
        )
    }

    override fun decrypt(ciphertext: String, iv: String): String {
        val cipher = Cipher.getInstance(transformation)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
        )
        val bytes = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
        return String(bytes, Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
