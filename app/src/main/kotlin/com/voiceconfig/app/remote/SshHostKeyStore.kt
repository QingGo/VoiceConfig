package com.voiceconfig.app.remote

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.jcraft.jsch.HostKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SSH 主机信任存储。
 *
 * - 保留 fingerprint 用于界面提示。
 * - 新增 OpenSSH known_hosts 格式文件，保存完整 host key，
 *   后续连接由 JSch 在认证前完成主机校验。
 */
@Singleton
class SshHostKeyStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("voiceconfig_ssh_hostkeys", Context.MODE_PRIVATE)

    private val knownHostsFile = File(context.filesDir, "ssh_known_hosts")

    fun knownHostsPath(): String = knownHostsFile.absolutePath

    fun get(host: String, port: Int): String? {
        if (host.isBlank()) return null
        return prefs.getString(key(host, port), null)
    }

    fun save(host: String, port: Int, fingerprint: String) {
        prefs.edit().putString(key(host, port), fingerprint).apply()
    }

    /**
     * 保存完整 host key（known_hosts 格式）。
     * 同时保留 fingerprint，便于界面展示。
     */
    fun saveHostKey(
        host: String,
        port: Int,
        hostKeyType: String,
        hostKeyBase64: String,
        fingerprint: String? = null,
    ) {
        if (host.isBlank() || hostKeyType.isBlank() || hostKeyBase64.isBlank()) return
        val label = chost(host, port)
        val line = "$label $hostKeyType $hostKeyBase64 voiceconfig-android"
        val lines = runCatching { knownHostsFile.readLines().filter { it.isNotBlank() } }
            .getOrDefault(emptyList())
            .filterNot { it.startsWith("$label ") || it.startsWith("$label\t") }
        knownHostsFile.writeText((lines + line).joinToString("\n") + "\n")
        if (fingerprint != null) {
            save(host, port, fingerprint)
        }
    }

    fun getHostKey(host: String, port: Int): HostKey? {
        val label = chost(host, port)
        val line = runCatching {
            knownHostsFile.readLines().firstOrNull { it.startsWith("$label ") || it.startsWith("$label\t") }
        }.getOrNull() ?: return null
        val parts = line.split(Regex("\\s+"))
        if (parts.size < 3) return null
        val keyBytes = runCatching { Base64.decode(parts[2], Base64.NO_WRAP) }.getOrNull() ?: return null
        return runCatching {
            HostKey(parts[0], keyBytes)
        }.getOrNull()
    }

    fun clear(host: String, port: Int) {
        prefs.edit().remove(key(host, port)).apply()
        val label = chost(host, port)
        runCatching {
            val lines = knownHostsFile.readLines()
                .filterNot { it.startsWith("$label ") || it.startsWith("$label\t") }
            if (lines.isEmpty()) {
                knownHostsFile.delete()
            } else {
                knownHostsFile.writeText(lines.joinToString("\n") + "\n")
            }
        }
    }

    private fun key(host: String, port: Int) = "${host}:$port"
    private fun chost(host: String, port: Int): String =
        if (port == 22) host else "[$host]:$port"
}
