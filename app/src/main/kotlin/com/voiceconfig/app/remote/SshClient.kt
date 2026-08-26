package com.voiceconfig.app.remote

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.OutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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

data class SshExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val hostKeyFingerprint: String? = null,
)

/**
 * App 内嵌 SSH 客户端（JSch）。
 *
 * 用于：
 * - 首次向树莓派安装/引导 VoiceConfig 节点
 * - 直接执行远程 shell 命令
 * - 后续可扩展文件读写、交互式终端等
 */
/**
 * SSH 交互式 shell 会话句柄。
 * UI 持有该对象用于发送命令和关闭会话；后台线程持续读取远端输出。
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

    fun close() {
        runCatching { channel.disconnect() }
        runCatching { session.disconnect() }
    }
}

@Singleton
class SshClient @Inject constructor() {

    suspend fun execute(config: SshConfig, command: String, timeoutMs: Long = 60_000): SshExecResult =
        withContext(Dispatchers.IO) {
            var session: Session? = null
            var channel: ChannelExec? = null
            try {
                val jsch = JSch()
                if (!config.privateKey.isNullOrBlank()) {
                    jsch.addIdentity(
                        "voiceconfig-key",
                        config.privateKey!!.toByteArray(Charsets.UTF_8),
                        null,
                        config.privateKeyPassphrase?.toByteArray(Charsets.UTF_8),
                    )
                }
                session = jsch.getSession(config.username, config.host, config.port)
                config.password?.let { session!!.setPassword(it) }
                session!!.setConfig("StrictHostKeyChecking", "no")
                session!!.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
                session!!.connect(15_000)
                val hostKey = session!!.hostKey
                val fingerprint = hostKey?.getFingerPrint(jsch)
                if (config.hostKeyFingerprint != null) {
                    val mismatch = fingerprint == null || config.hostKeyFingerprint != fingerprint
                    if (mismatch) {
                        session!!.disconnect()
                        return@withContext SshExecResult(-1, "", "Host key mismatch: $fingerprint", fingerprint)
                    }
                }

                channel = session!!.openChannel("exec") as ChannelExec
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
                        return@withContext SshExecResult(-1, stdout.toString(Charsets.UTF_8), "SSH command timed out", fingerprint)
                    }
                    delay(50)
                }
                SshExecResult(
                    exitCode = channel!!.exitStatus,
                    stdout = stdout.toString(Charsets.UTF_8),
                    stderr = stderr.toString(Charsets.UTF_8),
                    hostKeyFingerprint = fingerprint,
                )
            } catch (e: Exception) {
                SshExecResult(-1, "", e.message ?: "SSH failed", null)
            } finally {
                channel?.disconnect()
                session?.disconnect()
            }
        }

    suspend fun upload(config: SshConfig, remotePath: String, content: String): Boolean =
        withContext(Dispatchers.IO) {
            var session: Session? = null
            var sftp: ChannelSftp? = null
            try {
                val jsch = JSch()
                if (!config.privateKey.isNullOrBlank()) {
                    jsch.addIdentity(
                        "voiceconfig-key",
                        config.privateKey!!.toByteArray(Charsets.UTF_8),
                        null,
                        config.privateKeyPassphrase?.toByteArray(Charsets.UTF_8),
                    )
                }
                session = jsch.getSession(config.username, config.host, config.port)
                config.password?.let { session!!.setPassword(it) }
                session!!.setConfig("StrictHostKeyChecking", "no")
                session!!.connect(15_000)
                val hostKey = session!!.hostKey
                val fingerprint = hostKey?.getFingerPrint(jsch)
                if (config.hostKeyFingerprint != null) {
                    val mismatch = fingerprint == null || config.hostKeyFingerprint != fingerprint
                    if (mismatch) {
                        session!!.disconnect()
                        return@withContext false
                    }
                }

                sftp = session!!.openChannel("sftp") as ChannelSftp
                sftp!!.connect(15_000)
                sftp!!.put(ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)), remotePath)
                true
            } catch (e: Exception) {
                false
            } finally {
                sftp?.disconnect()
                session?.disconnect()
            }
        }

    suspend fun download(config: SshConfig, remotePath: String): String? =
        withContext(Dispatchers.IO) {
            var session: Session? = null
            var sftp: ChannelSftp? = null
            try {
                val jsch = JSch()
                if (!config.privateKey.isNullOrBlank()) {
                    jsch.addIdentity(
                        "voiceconfig-key",
                        config.privateKey!!.toByteArray(Charsets.UTF_8),
                        null,
                        config.privateKeyPassphrase?.toByteArray(Charsets.UTF_8),
                    )
                }
                session = jsch.getSession(config.username, config.host, config.port)
                config.password?.let { session!!.setPassword(it) }
                session!!.setConfig("StrictHostKeyChecking", "no")
                session!!.connect(15_000)
                val hostKey = session!!.hostKey
                val fingerprint = hostKey?.getFingerPrint(jsch)
                if (config.hostKeyFingerprint != null) {
                    val mismatch = fingerprint == null || config.hostKeyFingerprint != fingerprint
                    if (mismatch) {
                        session!!.disconnect()
                        return@withContext null
                    }
                }

                sftp = session!!.openChannel("sftp") as ChannelSftp
                sftp!!.connect(15_000)
                val stream = sftp!!.get(remotePath)
                stream.readBytes().toString(Charsets.UTF_8)
            } catch (e: Exception) {
                null
            } finally {
                sftp?.disconnect()
                session?.disconnect()
            }
        }

    /**
     * 打开一个带 PTY 的交互式 shell。返回句柄后由调用方发送命令/关闭。
     * 输出通过 onOutput 回调持续送达；会话退出时调用 onClosed。
     */
    suspend fun openShell(
        config: SshConfig,
        onOutput: (String) -> Unit,
        onClosed: () -> Unit,
    ): SshShellHandle? = withContext(Dispatchers.IO) {
        var session: Session? = null
        var channel: ChannelShell? = null
        try {
            val jsch = JSch()
            if (!config.privateKey.isNullOrBlank()) {
                jsch.addIdentity(
                    "voiceconfig-key",
                    config.privateKey!!.toByteArray(Charsets.UTF_8),
                    null,
                    config.privateKeyPassphrase?.toByteArray(Charsets.UTF_8),
                )
            }
            session = jsch.getSession(config.username, config.host, config.port)
            config.password?.let { session!!.setPassword(it) }
            session!!.setConfig("StrictHostKeyChecking", "no")
            session!!.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
            session!!.connect(15_000)
            val hostKey = session!!.hostKey
            val fingerprint = hostKey?.getFingerPrint(jsch)
            if (config.hostKeyFingerprint != null) {
                val mismatch = fingerprint == null || config.hostKeyFingerprint != fingerprint
                if (mismatch) {
                    session!!.disconnect()
                    onClosed()
                    return@withContext null
                }
            }

            channel = session!!.openChannel("shell") as ChannelShell
            channel!!.setPty(true)
            channel!!.connect(15_000)
            val input = channel!!.inputStream
            val output = channel!!.outputStream
            val handle = SshShellHandle(session!!, channel!!, output)

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
            session?.disconnect()
            onClosed()
            null
        }
    }
}
