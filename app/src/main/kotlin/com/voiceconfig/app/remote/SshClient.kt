package com.voiceconfig.app.remote

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
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
                if (config.hostKeyFingerprint != null && fingerprint != null && config.hostKeyFingerprint != fingerprint) {
                    session!!.disconnect()
                    return@withContext SshExecResult(-1, "", "Host key mismatch: $fingerprint", fingerprint)
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
}
