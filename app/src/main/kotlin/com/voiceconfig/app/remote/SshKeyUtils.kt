package com.voiceconfig.app.remote

/**
 * 极简私钥类型检测。
 *
 * 用于在导入/粘贴私钥时给出提示：RSA 旧式 SHA-1 签名可能被现代 OpenSSH 拒绝，
 * 建议优先使用 Ed25519 / ECDSA。
 */
fun detectSshKeyType(privateKey: String): String? {
    val text = privateKey.trim()
    return when {
        text.contains("BEGIN RSA PRIVATE KEY") -> "RSA"
        text.contains("BEGIN EC PRIVATE KEY") -> "ECDSA"
        text.contains("BEGIN OPENSSH PRIVATE KEY") -> "OpenSSH"
        text.contains("BEGIN PRIVATE KEY") -> "PKCS8"
        text.contains("BEGIN DSA PRIVATE KEY") -> "DSA"
        else -> null
    }
}

fun isRsaLikelyIncompatible(type: String?): Boolean =
    type == "RSA" || type == "DSA"
