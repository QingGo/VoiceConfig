package com.voiceconfig.app.remote

/**
 * 等待用户确认的主机信任信息。
 *
 * 首次连接时先执行一个无害命令取得完整 host key，
 * 再由用户确认指纹；确认后保存完整 key 到 known_hosts。
 */
data class SshPendingTrust(
    val config: SshConfig,
    val fingerprint: String,
    val hostKeyType: String? = null,
    val hostKeyBase64: String? = null,
    val command: String? = null,
    val installBindMode: String? = null,
)
