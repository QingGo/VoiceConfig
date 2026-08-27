package com.voiceconfig.app.remote

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 生成可用于 JSch 的 SSH 密钥对。
 *
 * 优先使用 Ed25519 / ECDSA；如果设备不支持 Ed25519，自动回退到 ECDSA。
 */
@Singleton
class SshKeyManager @Inject constructor(
    private val keyStore: SshKeyStore,
) {
    fun generate(type: String, name: String): SshManagedKey? {
        if (type == "ED25519") {
            return generateInternal("ED25519", name)
                ?: generateInternal("ECDSA256", name)
        }
        return generateInternal(type, name)
    }

    private fun generateInternal(type: String, name: String): SshManagedKey? {
        return try {
            val jsch = JSch()
            val keyPair = when (type) {
                "ED25519" -> KeyPair.genKeyPair(jsch, KeyPair.ED25519, 256)
                "ECDSA256" -> KeyPair.genKeyPair(jsch, KeyPair.ECDSA, 256)
                "ECDSA521" -> KeyPair.genKeyPair(jsch, KeyPair.ECDSA, 521)
                "RSA" -> KeyPair.genKeyPair(jsch, KeyPair.RSA, 3072)
                else -> return null
            }
            val privOut = ByteArrayOutputStream()
            keyPair.writePrivateKey(privOut)
            val pubOut = ByteArrayOutputStream()
            keyPair.writePublicKey(pubOut, "voiceconfig-android")
            keyStore.save(
                name = name,
                type = keyPair.getKeyTypeString(),
                privateKey = privOut.toString(Charsets.UTF_8),
                publicKey = pubOut.toString(Charsets.UTF_8).trim(),
            )
        } catch (e: Exception) {
            null
        }
    }
}
