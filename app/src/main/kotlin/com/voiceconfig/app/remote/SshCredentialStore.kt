package com.voiceconfig.app.remote

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

data class StoredSshCredential(
    val host: String,
    val port: Int,
    val username: String,
    val password: String?,
    val privateKey: String?,
    val privateKeyPassphrase: String?,
)

/**
 * SSH 凭据加密存储：
 * - 密码、私钥、私钥口令使用 Android Keystore + AES/GCM 加密。
 * - 不把明文写入 SharedPreferences。
 */
@Singleton
class SshCredentialStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("voiceconfig_ssh", Context.MODE_PRIVATE)

    fun save(config: SshConfig) {
        val key = "cred_${config.host}_${config.port}"
        val json = buildString {
            append("${config.username}\n")
            append("${config.port}\n")
            append(encrypt(config.password ?: "") ?: "").append("\n")
            append(encrypt(config.privateKey ?: "") ?: "").append("\n")
            append(encrypt(config.privateKeyPassphrase ?: "") ?: "").append("\n")
        }
        prefs.edit().putString(key, json).apply()
    }

    fun load(host: String, port: Int = 22): StoredSshCredential? {
        val key = "cred_${host}_${port}"
        val raw = prefs.getString(key, null) ?: return null
        val lines = raw.split("\n")
        if (lines.size < 5) return null
        return StoredSshCredential(
            host = host,
            port = lines[1].toIntOrNull() ?: port,
            username = lines[0],
            password = decrypt(lines[2])?.ifBlank { null },
            privateKey = decrypt(lines[3])?.ifBlank { null },
            privateKeyPassphrase = decrypt(lines[4])?.ifBlank { null },
        )
    }

    fun delete(host: String, port: Int = 22) {
        prefs.edit().remove("cred_${host}_${port}").apply()
    }

    private fun getOrCreateKey(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        generator.generateKey()
    }.getOrNull()

    private fun encrypt(plain: String): String? = runCatching {
        val key = getOrCreateKey() ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipherText, Base64.NO_WRAP)
    }.getOrNull()

    private fun decrypt(data: String): String? = runCatching {
        if (data.isBlank()) return ""
        val parts = data.split(":", limit = 2)
        if (parts.size != 2) return null
        val key = getOrCreateKey() ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    companion object {
        private const val KEY_ALIAS = "voiceconfig_ssh_credential"
    }
}
