package com.voiceconfig.app.remote

import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.OutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Vector
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class SshConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String? = null,
    val privateKey: String? = null,
    val privateKeyPassphrase: String? = null,
    val hostKeyFingerprint: String? = null,
)

data class SshHostKeyInfo(
    val fingerprint: String,
    val type: String,
    val keyBase64: String,
)

data class SshExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val hostKeyFingerprint: String? = null,
    val hostKeyType: String? = null,
    val hostKeyBase64: String? = null,
)

data class SshRemoteFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean = false,
    val size: Long = 0,
    val permissions: String = "",
    val modified: Long = 0,
)

/**
 * SSH 交互式 shell 会话句柄。
 * UI 持有该对象用于发送命令、调整 PTY 大小和关闭会话。
 */
class SshShellHandle(
    private val session: Session,
    private val channel: ChannelShell,
    private val output: OutputStream,
) {
    var lastSendError: String? = null

    fun send(command: String): Boolean {
        return try {
            output.write((command.trimEnd() + "\n").toByteArray(Charsets.UTF_8))
            output.flush()
            true
        } catch (e: Exception) {
            lastSendError = e.toString()
            false
        }
    }

    fun resize(width: Int, height: Int) {
        runCatching { channel.setPtySize(width, height, 0, 0) }
    }

    fun close() {
        runCatching { channel.disconnect() }
        runCatching { session.disconnect() }
    }
}

private class ConnectedSession(
    val session: Session,
    val jsch: JSch,
    val info: SshHostKeyInfo?,
    val strict: Boolean,
)

/**
 * 应用内嵌 SSH 客户端（JSch mwiede 维护版）。
 *
 * 信任模型：
 * - 首次连接：StrictHostKeyChecking=no，仅获取完整 host key 供用户 TOFU。
 * - 用户确认后：保存到 OpenSSH known_hosts 文件。
 * - 后续连接：StrictHostKeyChecking=yes，JSch 在认证前校验主机 key。
 * - 对旧版本只保存 fingerprint 的记录：自动迁移为完整 key 后同样进入严格校验。
 */
@Singleton
class SshClient @Inject constructor(
    private val hostKeyStore: SshHostKeyStore,
) {

    // ---------- 连接基础设施 ----------

    private fun createJSch(config: SshConfig): JSch {
        // 兼容旧版本保存的 MD5 指纹；后续可由用户升级为 SHA256。
        JSch.setConfig("FingerprintHash", "md5")
        val jsch = JSch()
        runCatching { jsch.setKnownHosts(hostKeyStore.knownHostsPath()) }
        if (!config.privateKey.isNullOrBlank()) {
            jsch.addIdentity(
                "voiceconfig-key",
                config.privateKey!!.toByteArray(Charsets.UTF_8),
                null,
                config.privateKeyPassphrase?.toByteArray(Charsets.UTF_8),
            )
        }
        return jsch
    }

    private fun hostKeyInfo(session: Session, jsch: JSch): SshHostKeyInfo? {
        val key = runCatching { session.hostKey }.getOrNull() ?: return null
        val rawFingerprint = runCatching { key.getFingerPrint(jsch) }.getOrNull() ?: return null
        return SshHostKeyInfo(
            // JSch 输出可能带 "MD5:"/"SHA256:" 前缀，去掉后与历史存储格式兼容。
            fingerprint = rawFingerprint.substringAfter(':'),
            type = key.getType(),
            keyBase64 = key.getKey(),
        )
    }

    private fun connect(config: SshConfig): ConnectedSession? {
        return try {
            val jsch = createJSch(config)
            val session = jsch.getSession(config.username, config.host, config.port)
            config.password?.let { session.setPassword(it.toByteArray(Charsets.UTF_8)) }
            session.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
            // 优先 ECDSA 主机 key，兼容旧版本只保存 ECDSA fingerprint 的迁移场景。
            session.setConfig(
                "server_host_key",
                "ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,ssh-ed25519,ssh-rsa",
            )

            val hasFull = hostKeyStore.getHostKey(config.host, config.port) != null
            val strict = config.hostKeyFingerprint != null && hasFull
            session.setConfig("StrictHostKeyChecking", if (strict) "yes" else "no")
            session.connect(15_000)

            val info = hostKeyInfo(session, jsch)
            // 首次探测时不把未确认的主机 key 持久化到 known_hosts；
            // JSch 在 StrictHostKeyChecking=no 时会自动 add，这里立即撤回，
            // 等用户确认后再由上层保存。
            if (config.hostKeyFingerprint == null) {
                hostKeyStore.clear(config.host, config.port)
            }
            if (config.hostKeyFingerprint != null) {
                if (info == null || info.fingerprint != config.hostKeyFingerprint) {
                    Log.e(
                        "SshClient",
                        "host key mismatch host=${config.host}:${config.port} stored=${config.hostKeyFingerprint} actual=${info?.fingerprint} type=${info?.type}",
                    )
                    session.disconnect()
                    return null
                }
                // 迁移旧版本：只有 fingerprint、没有完整 key 的记录。
                if (!hasFull) {
                    hostKeyStore.saveHostKey(
                        config.host, config.port, info.type, info.keyBase64, info.fingerprint,
                    )
                }
            }
            ConnectedSession(session, jsch, info, strict)
        } catch (e: Exception) {
            Log.e("SshClient", "connect failed: ${config.host}:${config.port}", e)
            null
        }
    }

    // ---------- 执行命令 ----------

    suspend fun execute(config: SshConfig, command: String, timeoutMs: Long = 60_000): SshExecResult =
        withContext(Dispatchers.IO) {
            var channel: ChannelExec? = null
            try {
                val connected = connect(config) ?: return@withContext SshExecResult(
                    -1, "", "SSH 连接失败或主机指纹不匹配"
                )
                val info = connected.info
                channel = connected.session.openChannel("exec") as ChannelExec
                channel!!.setCommand(command)
                val stdout = ByteArrayOutputStream()
                val stderr = ByteArrayOutputStream()
                channel!!.setOutputStream(stdout)
                channel!!.setErrStream(stderr)
                channel!!.connect(15_000)

                val deadline = System.currentTimeMillis() + timeoutMs
                while (!channel!!.isClosed) {
                    if (System.currentTimeMillis() > deadline) {
                        channel!!.disconnect()
                        return@withContext SshExecResult(
                            -1, stdout.toString(Charsets.UTF_8), "SSH command timed out",
                            info?.fingerprint, info?.type, info?.keyBase64,
                        )
                    }
                    delay(50)
                }
                SshExecResult(
                    exitCode = channel!!.exitStatus,
                    stdout = stdout.toString(Charsets.UTF_8),
                    stderr = stderr.toString(Charsets.UTF_8),
                    hostKeyFingerprint = info?.fingerprint,
                    hostKeyType = info?.type,
                    hostKeyBase64 = info?.keyBase64,
                )
            } catch (e: Exception) {
                SshExecResult(-1, "", e.message ?: "SSH failed", null, null, null)
            } finally {
                channel?.disconnect()
            }
        }

    // ---------- SFTP ----------

    suspend fun upload(config: SshConfig, remotePath: String, content: String): Boolean =
        withContext(Dispatchers.IO) {
            var sftp: ChannelSftp? = null
            try {
                val connected = connect(config) ?: return@withContext false
                sftp = connected.session.openChannel("sftp") as ChannelSftp
                sftp!!.connect(15_000)
                sftp!!.put(ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)), remotePath)
                true
            } catch (e: Exception) {
                false
            } finally {
                sftp?.disconnect()
            }
        }

    suspend fun download(config: SshConfig, remotePath: String, maxBytes: Int = 2 * 1024 * 1024): String? =
        withContext(Dispatchers.IO) {
            var sftp: ChannelSftp? = null
            try {
                val connected = connect(config) ?: return@withContext null
                sftp = connected.session.openChannel("sftp") as ChannelSftp
                sftp!!.connect(15_000)
                val attrs = sftp!!.stat(remotePath)
                if (attrs.isDir) return@withContext null
                if (attrs.size > maxBytes) return@withContext null
                val stream = sftp!!.get(remotePath)
                val bytes = stream.readBytes()
                if (bytes.size > maxBytes) return@withContext null
                // 简单二进制保护：NUL 字节较多时拒绝显示为文本
                if (bytes.count { it == 0.toByte() } > bytes.size / 10) return@withContext null
                bytes.toString(Charsets.UTF_8)
            } catch (e: Exception) {
                null
            } finally {
                sftp?.disconnect()
            }
        }

    suspend fun listFiles(config: SshConfig, remotePath: String): List<SshRemoteFile>? =
        withContext(Dispatchers.IO) {
            var sftp: ChannelSftp? = null
            try {
                val connected = connect(config) ?: return@withContext null
                sftp = connected.session.openChannel("sftp") as ChannelSftp
                sftp!!.connect(15_000)
                @Suppress("UNCHECKED_CAST")
                val entries = sftp!!.ls(remotePath) as? Vector<ChannelSftp.LsEntry> ?: return@withContext null
                val base = if (remotePath.endsWith("/")) remotePath else remotePath + "/"
                entries.mapNotNull { entry ->
                    val name = entry.filename
                    if (name == "." || name == "..") return@mapNotNull null
                    val attrs = entry.attrs
                    SshRemoteFile(
                        name = name,
                        path = base + name,
                        isDirectory = attrs.isDir,
                        isSymlink = attrs.isLink,
                        size = attrs.size,
                        permissions = runCatching { attrs.permissionsString ?: "" }.getOrDefault(""),
                        modified = attrs.mTime.toLong(),
                    )
                }.sortedWith(compareByDescending<SshRemoteFile> { it.isDirectory }.thenBy { it.name })
            } catch (e: Exception) {
                null
            } finally {
                sftp?.disconnect()
            }
        }

    suspend fun mkdir(config: SshConfig, remotePath: String): Boolean =
        withContext(Dispatchers.IO) {
            var sftp: ChannelSftp? = null
            try {
                val connected = connect(config) ?: return@withContext false
                sftp = connected.session.openChannel("sftp") as ChannelSftp
                sftp!!.connect(15_000)
                sftp!!.mkdir(remotePath)
                true
            } catch (e: Exception) {
                false
            } finally {
                sftp?.disconnect()
            }
        }

    suspend fun delete(config: SshConfig, remotePath: String): Boolean =
        withContext(Dispatchers.IO) {
            var sftp: ChannelSftp? = null
            try {
                val connected = connect(config) ?: return@withContext false
                sftp = connected.session.openChannel("sftp") as ChannelSftp
                sftp!!.connect(15_000)
                val attrs = sftp!!.stat(remotePath)
                if (attrs.isDir) sftp!!.rmdir(remotePath) else sftp!!.rm(remotePath)
                true
            } catch (e: Exception) {
                false
            } finally {
                sftp?.disconnect()
            }
        }

    suspend fun rename(config: SshConfig, oldPath: String, newPath: String): Boolean =
        withContext(Dispatchers.IO) {
            var sftp: ChannelSftp? = null
            try {
                val connected = connect(config) ?: return@withContext false
                sftp = connected.session.openChannel("sftp") as ChannelSftp
                sftp!!.connect(15_000)
                sftp!!.rename(oldPath, newPath)
                true
            } catch (e: Exception) {
                false
            } finally {
                sftp?.disconnect()
            }
        }

    // ---------- 交互终端 ----------

    suspend fun openShell(
        config: SshConfig,
        onOutput: (String) -> Unit,
        onClosed: () -> Unit,
    ): SshShellHandle? = withContext(Dispatchers.IO) {
        var channel: ChannelShell? = null
        try {
            val connected = connect(config) ?: run {
                onClosed()
                return@withContext null
            }
            channel = connected.session.openChannel("shell") as ChannelShell
            channel!!.setPty(true)
            channel!!.setPtySize(120, 40, 0, 0)
            channel!!.connect(15_000)
            val input = channel!!.inputStream
            val output = channel!!.outputStream
            val handle = SshShellHandle(connected.session, channel!!, output)

            Thread {
                try {
                    val buf = ByteArray(8192)
                    while (!channel!!.isClosed) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) {
                            onOutput(String(buf, 0, n, Charsets.UTF_8))
                        }
                    }
                } catch (_: Exception) {
                    // 端关闭时正常退出
                } finally {
                    onClosed()
                }
            }.apply {
                isDaemon = true
                start()
            }
            handle
        } catch (e: Exception) {
            channel?.disconnect()
            onClosed()
            null
        }
    }
}
