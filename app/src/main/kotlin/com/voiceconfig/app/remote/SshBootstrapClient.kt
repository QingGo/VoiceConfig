package com.voiceconfig.app.remote

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class SshBootstrapResult(
    val ok: Boolean,
    val message: String,
    val token: String? = null,
    val nodeId: String? = null,
)

/**
 * 通过 SSH 在树莓派上首次安装 VoiceConfig 节点。
 * 解决“没有节点时怎么引导”的问题。
 */
@Singleton
class SshBootstrapClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sshClient: SshClient,
) {

    suspend fun install(
        config: SshConfig,
        bindMode: String = "tailscale",
    ): SshBootstrapResult {
        val homeResult = sshClient.execute(config, "echo \$HOME")
        if (homeResult.exitCode != 0 || homeResult.stdout.isBlank()) {
            return SshBootstrapResult(false, "无法获取远程 HOME：${homeResult.stderr.ifBlank { homeResult.stdout }}")
        }
        val home = homeResult.stdout.trim()
        val remoteDir = "$home/voiceconfig-node"
        val dataDir = "$home/.voiceconfig-node"

        val mkdir = sshClient.execute(
            config,
            "mkdir -p $remoteDir $dataDir $home/.config/systemd/user",
        )
        if (mkdir.exitCode != 0) {
            return SshBootstrapResult(false, "创建目录失败：${mkdir.stderr}")
        }

        val script = runCatching {
            context.assets.open("voiceconfig_agent_node.py").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return SshBootstrapResult(false, "应用内缺少节点脚本资源")

        val uploaded = sshClient.upload(config, "$remoteDir/voiceconfig_agent_node.py", script)
        if (!uploaded) {
            return SshBootstrapResult(false, "上传节点脚本失败")
        }

        val bindArg = if (bindMode == "lan") "--host 0.0.0.0" else "--bind-tailscale"
        val service = """
            [Unit]
            Description=VoiceConfig Agent Node
            After=network-online.target
            Wants=network-online.target

            [Service]
            ExecStart=/usr/bin/python3 $remoteDir/voiceconfig_agent_node.py $bindArg --port 8787 --data-dir $dataDir
            Restart=always
            RestartSec=3
            Environment=PYTHONUNBUFFERED=1
            NoNewPrivileges=true
            PrivateTmp=true

            [Install]
            WantedBy=default.target
        """.trimIndent() + "\n"

        val uploadedService = sshClient.upload(
            config,
            "$home/.config/systemd/user/voiceconfig-node.service",
            service,
        )
        if (!uploadedService) {
            return SshBootstrapResult(false, "上传 systemd 服务失败")
        }

        val start = sshClient.execute(
            config,
            "systemctl --user daemon-reload && systemctl --user enable --now voiceconfig-node.service && loginctl enable-linger ${config.username} || true",
        )
        if (start.exitCode != 0) {
            return SshBootstrapResult(false, "启动节点失败：${start.stderr}")
        }

        val active = sshClient.execute(config, "systemctl --user is-active voiceconfig-node.service")
        if (active.stdout.trim() != "active") {
            return SshBootstrapResult(false, "节点未进入 active 状态：${active.stdout.ifBlank { active.stderr }}")
        }

        val tokenResult = sshClient.execute(config, "cat $dataDir/node.token")
        val token = tokenResult.stdout.trim().ifBlank { null }
        val identityResult = sshClient.execute(config, "cat $dataDir/identity.json")
        val nodeId = parseNodeId(identityResult.stdout)

        return SshBootstrapResult(
            ok = token != null,
            message = if (token != null) "节点安装成功" else "节点已启动但未读取到 Token",
            token = token,
            nodeId = nodeId,
        )
    }

    private fun parseNodeId(json: String): String? {
        val marker = "\"node_id\": \""
        val start = json.indexOf(marker)
        if (start < 0) return null
        val after = start + marker.length
        val end = json.indexOf('"', after)
        return if (end > after) json.substring(after, end) else null
    }
}
