package com.voiceconfig.app.agent

import com.voiceconfig.app.remote.SshClient
import com.voiceconfig.app.remote.SshConfig
import com.voiceconfig.app.remote.SshCredentialStore
import com.voiceconfig.app.remote.SshHostKeyStore
import com.voiceconfig.data.local.repository.RemoteNodeRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 远程 SSH Agent 工具的服务层。
 *
 * 使用已保存的 SSH 凭据和已信任主机，不向模型暴露 Token/私钥。
 */
@Singleton
class RemoteSshAgentService @Inject constructor(
    private val sshClient: SshClient,
    private val sshCredentialStore: SshCredentialStore,
    private val sshHostKeyStore: SshHostKeyStore,
    private val remoteNodeRepository: RemoteNodeRepository,
) {
    suspend fun resolveConfig(hostOrNode: String?): SshConfig? {
        val nodes = runCatching { remoteNodeRepository.getNodes() }.getOrElse { emptyList() }
            .filter { it.enabled && !it.paused }
        val key = hostOrNode?.trim().orEmpty()
        val node = when {
            key.isBlank() -> nodes.firstOrNull()
            else -> nodes.firstOrNull { it.name == key || it.host == key || it.nodeId == key }
        }
        val host = node?.host ?: key.takeIf { it.isNotBlank() } ?: return null
        val stored = sshCredentialStore.load(host, 22) ?: return null
        val trusted = sshHostKeyStore.get(host, 22) ?: return null
        return SshConfig(
            host = host,
            port = 22,
            username = stored.username,
            password = stored.password,
            privateKey = stored.privateKey,
            privateKeyPassphrase = stored.privateKeyPassphrase,
            hostKeyFingerprint = trusted,
        )
    }

    suspend fun exec(hostOrNode: String?, command: String): ToolResult {
        val config = resolveConfig(hostOrNode)
            ?: return ToolResult.failure("未找到可用 SSH 凭据，请先在设置中连接并信任远程节点")
        val result = sshClient.execute(config, command, timeoutMs = 120_000)
        return if (result.exitCode == 0) {
            ToolResult.success(
                "远程命令执行成功",
                mapOf(
                    "host" to config.host,
                    "command" to command,
                    "exitCode" to result.exitCode,
                    "stdout" to result.stdout.take(6000),
                    "stderr" to result.stderr.take(2000),
                ),
            )
        } else {
            ToolResult.failure(
                "远程命令失败 exit=${result.exitCode}: ${result.stderr.ifBlank { result.stdout }.take(500)}",
                mapOf(
                    "host" to config.host,
                    "command" to command,
                    "exitCode" to result.exitCode,
                    "stdout" to result.stdout.take(6000),
                    "stderr" to result.stderr.take(2000),
                ),
            )
        }
    }

    suspend fun read(hostOrNode: String?, path: String): ToolResult {
        val config = resolveConfig(hostOrNode)
            ?: return ToolResult.failure("未找到可用 SSH 凭据，请先连接并信任远程节点")
        val content = sshClient.download(config, path) ?: return ToolResult.failure("远程文件读取失败或文件过大/二进制")
        return ToolResult.success(
            "已读取 $path",
            mapOf("host" to config.host, "path" to path, "content" to content),
        )
    }

    suspend fun write(hostOrNode: String?, path: String, content: String): ToolResult {
        val config = resolveConfig(hostOrNode)
            ?: return ToolResult.failure("未找到可用 SSH 凭据，请先连接并信任远程节点")
        val ok = sshClient.upload(config, path, content)
        return if (ok) {
            ToolResult.success("已写入 $path", mapOf("host" to config.host, "path" to path, "size" to content.length))
        } else {
            ToolResult.failure("远程文件写入失败：$path")
        }
    }

    suspend fun list(hostOrNode: String?, path: String): ToolResult {
        val config = resolveConfig(hostOrNode)
            ?: return ToolResult.failure("未找到可用 SSH 凭据，请先连接并信任远程节点")
        val files = sshClient.listFiles(config, path)
            ?: return ToolResult.failure("远程目录读取失败：$path")
        val lines = files.take(200).map { f ->
            "${if (f.isDirectory) "D" else "F"} ${f.permissions.padEnd(10)} ${f.size.toString().padStart(10)} ${f.name}"
        }
        return ToolResult.success(
            "远程目录 $path 共 ${files.size} 项",
            mapOf("host" to config.host, "path" to path, "files" to lines),
        )
    }

    suspend fun search(hostOrNode: String?, pattern: String, path: String): ToolResult {
        val config = resolveConfig(hostOrNode)
            ?: return ToolResult.failure("未找到可用 SSH 凭据，请先连接并信任远程节点")
        val safePattern = pattern.replace("'", "'\''")
        val safePath = path.replace("'", "'\''")
        val result = sshClient.execute(
            config,
            "grep -rn --exclude-dir=.git --exclude-dir=build --exclude='*.class' '$safePattern' '$safePath' 2>/dev/null | head -200",
            timeoutMs = 60_000,
        )
        return if (result.exitCode == 0 || result.stdout.isNotBlank()) {
            ToolResult.success(
                "搜索完成",
                mapOf("host" to config.host, "pattern" to pattern, "path" to path, "matches" to result.stdout.take(6000)),
            )
        } else {
            ToolResult.failure("搜索无结果或失败", mapOf("host" to config.host, "pattern" to pattern, "path" to path))
        }
    }
}
