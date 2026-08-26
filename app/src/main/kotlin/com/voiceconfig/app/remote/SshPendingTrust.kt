package com.voiceconfig.app.remote

/**
 * 等待用户确认的主机指纹。
 *
 * 首次连接时先执行一个无害命令取得指纹，再在界面上请用户确认；
 * 确认后才执行真正的目标命令 / 安装动作，并把指纹写入信任存储。
 */
data class SshPendingTrust(
    val config: SshConfig,
    val fingerprint: String,
    val command: String? = null,
    val installBindMode: String? = null,
)
