package com.voiceconfig.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voiceconfig.data.local.repository.RemoteNode
import com.voiceconfig.data.local.repository.RemoteProjectRecord
import com.voiceconfig.app.remote.RemoteCommandResult
import com.voiceconfig.app.remote.SshBootstrapClient
import com.voiceconfig.app.remote.SshAuditStore
import com.voiceconfig.app.remote.SshBootstrapResult
import com.voiceconfig.app.remote.SshClient
import com.voiceconfig.app.remote.SshCredentialStore
import com.voiceconfig.app.remote.SshFileResult
import com.voiceconfig.app.remote.SshHostKeyStore
import com.voiceconfig.app.remote.SshKeyManager
import com.voiceconfig.app.remote.SshKeyStore
import com.voiceconfig.app.remote.SshManagedKey
import com.voiceconfig.app.remote.SshPendingTrust
import com.voiceconfig.app.remote.SshRemoteFile
import com.voiceconfig.app.remote.SshShellHandle
import com.voiceconfig.app.remote.StoredSshCredential
import com.voiceconfig.app.remote.SshConfig
import com.voiceconfig.app.remote.SshExecResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class SshViewModel @Inject constructor(
    private val remoteNodeFeature: RemoteNodeFeature,
    private val sshClient: SshClient,
    private val sshBootstrapClient: SshBootstrapClient,
    private val sshCredentialStore: SshCredentialStore,
    private val sshHostKeyStore: SshHostKeyStore,
    private val sshAuditStore: SshAuditStore,
    private val sshKeyStore: SshKeyStore,
    private val sshKeyManager: SshKeyManager,
) : ViewModel() {

    override fun onCleared() {
        closeSshShell()
        super.onCleared()
    }

    val remoteNodes: StateFlow<List<RemoteNode>> = remoteNodeFeature.nodes
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val remoteProjects: StateFlow<List<RemoteProjectRecord>> = remoteNodeFeature.projects
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val remoteCommandResult: StateFlow<RemoteCommandResult?> = remoteNodeFeature.commandResult

    private val _sshResult = MutableStateFlow<SshExecResult?>(null)
    val sshResult: StateFlow<SshExecResult?> = _sshResult.asStateFlow()

    private val _sshServiceResult = MutableStateFlow<SshExecResult?>(null)
    val sshServiceResult: StateFlow<SshExecResult?> = _sshServiceResult.asStateFlow()

    private val _sshNodeLogResult = MutableStateFlow<SshExecResult?>(null)
    val sshNodeLogResult: StateFlow<SshExecResult?> = _sshNodeLogResult.asStateFlow()

    private val _sshBootstrapResult = MutableStateFlow<SshBootstrapResult?>(null)
    val sshBootstrapResult: StateFlow<SshBootstrapResult?> = _sshBootstrapResult.asStateFlow()

    private val _pendingSshHostKey = MutableStateFlow<SshPendingTrust?>(null)
    val pendingSshHostKey: StateFlow<SshPendingTrust?> = _pendingSshHostKey.asStateFlow()

    private val _sshFileResult = MutableStateFlow<SshFileResult?>(null)
    val sshFileResult: StateFlow<SshFileResult?> = _sshFileResult.asStateFlow()

    private val _sshFileEntries = MutableStateFlow<List<SshRemoteFile>?>(null)
    val sshFileEntries: StateFlow<List<SshRemoteFile>?> = _sshFileEntries.asStateFlow()

    private val _sshShellOutput = MutableStateFlow("")
    val sshShellOutput: StateFlow<String> = _sshShellOutput.asStateFlow()

    private val _sshShellRunning = MutableStateFlow(false)
    val sshShellRunning: StateFlow<Boolean> = _sshShellRunning.asStateFlow()

    private val _sshShellError = MutableStateFlow<String?>(null)
    val sshShellError: StateFlow<String?> = _sshShellError.asStateFlow()

    private val _sshAuditText = MutableStateFlow("")
    val sshAuditText: StateFlow<String> = _sshAuditText.asStateFlow()

    private val _sshKeys = MutableStateFlow<List<SshManagedKey>>(sshKeyStore.list())
    val sshKeys: StateFlow<List<SshManagedKey>> = _sshKeys.asStateFlow()

    private var sshShellHandle: SshShellHandle? = null
    private var sshShellHostHost: String? = null
    private var sshShellHostPort: Int? = null
    private var sshShellHostUser: String? = null

    fun saveRemoteNode(node: RemoteNode) {
        viewModelScope.launch { remoteNodeFeature.saveNode(node) }
    }

    fun deleteRemoteNode(id: Long) {
        viewModelScope.launch { remoteNodeFeature.deleteNode(id) }
    }

    fun setRemoteNodeEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { remoteNodeFeature.setEnabled(id, enabled) }
    }

    fun setRemoteNodePaused(id: Long, paused: Boolean) {
        viewModelScope.launch { remoteNodeFeature.setPaused(id, paused) }
    }

    suspend fun getRemoteNode(id: Long): RemoteNode? = remoteNodeFeature.getNode(id)

    suspend fun refreshRemoteNode(id: Long) {
        remoteNodeFeature.refreshNode(id)
    }

    fun executeRemoteCommand(node: RemoteNode, command: String) {
        viewModelScope.launch { remoteNodeFeature.executeCommand(node, command) }
    }

    fun clearRemoteCommandResult() {
        remoteNodeFeature.clearCommandResult()
    }

    fun getSshCredential(host: String, port: Int = 22): StoredSshCredential? =
        sshCredentialStore.load(host, port)

    fun clearSshHostKey(config: SshConfig) {
        sshHostKeyStore.clear(config.host, config.port)
    }

    fun getSshHostKey(host: String, port: Int = 22): String? =
        sshHostKeyStore.get(host, port)

    fun refreshSshKeys() {
        _sshKeys.value = sshKeyStore.list()
    }

    fun generateSshKey(type: String, name: String) {
        viewModelScope.launch {
            val key = withContext(Dispatchers.IO) {
                sshKeyManager.generate(type, name)
            }
            if (key != null) _sshKeys.value = sshKeyStore.list()
        }
    }

    fun deleteSshKey(id: String) {
        sshKeyStore.delete(id)
        _sshKeys.value = sshKeyStore.list()
    }

    fun renameSshKey(id: String, name: String) {
        sshKeyStore.rename(id, name)
        _sshKeys.value = sshKeyStore.list()
    }

    fun getSshKey(id: String): SshManagedKey? = sshKeyStore.get(id)

    fun executeSsh(config: SshConfig, command: String) {
        viewModelScope.launch {
            _sshResult.value = null
            sshCredentialStore.save(config)
            val trusted = sshHostKeyStore.get(config.host, config.port)
            if (trusted == null) {
                // 首次连接：先用无害命令换取指纹，等待用户确认后再执行真正命令。
                val probe = sshClient.execute(config, "true", 15_000)
                val fp = probe.hostKeyFingerprint
                if (fp == null) {
                    _sshResult.value = probe
                    return@launch
                }
                _pendingSshHostKey.value = SshPendingTrust(
                    config = config,
                    fingerprint = fp,
                    hostKeyType = probe.hostKeyType,
                    hostKeyBase64 = probe.hostKeyBase64,
                    command = command,
                )
                return@launch
            }
            val effective = config.copy(hostKeyFingerprint = trusted)
            val result = sshClient.execute(effective, command)
            sshAuditStore.record(
                config.host, config.port, config.username,
                "exec", command, result.exitCode == 0,
            )
            _sshResult.value = result
        }
    }

    fun installNodeViaSsh(config: SshConfig, bindMode: String = "tailscale") {
        viewModelScope.launch {
            _sshBootstrapResult.value = null
            sshCredentialStore.save(config)
            val trusted = sshHostKeyStore.get(config.host, config.port)
            if (trusted == null) {
                val probe = sshClient.execute(config, "true", 15_000)
                val fp = probe.hostKeyFingerprint
                if (fp == null) {
                    _sshBootstrapResult.value = SshBootstrapResult(
                        false,
                        "无法获取主机指纹：${probe.stderr.ifBlank { probe.stdout }}",
                    )
                    return@launch
                }
                _pendingSshHostKey.value = SshPendingTrust(
                    config = config,
                    fingerprint = fp,
                    hostKeyType = probe.hostKeyType,
                    hostKeyBase64 = probe.hostKeyBase64,
                    installBindMode = bindMode,
                )
                return@launch
            }
            runSshInstallDirect(config.copy(hostKeyFingerprint = trusted), bindMode)
        }
    }

    fun confirmSshHostKey(trusted: Boolean) {
        val pending = _pendingSshHostKey.value ?: return
        _pendingSshHostKey.value = null
        if (!trusted) return
        viewModelScope.launch {
            sshHostKeyStore.save(pending.config.host, pending.config.port, pending.fingerprint)
            if (pending.hostKeyType != null && pending.hostKeyBase64 != null) {
                sshHostKeyStore.saveHostKey(
                    pending.config.host,
                    pending.config.port,
                    pending.hostKeyType,
                    pending.hostKeyBase64,
                    pending.fingerprint,
                )
            }
            val effective = pending.config.copy(hostKeyFingerprint = pending.fingerprint)
            if (pending.command != null) {
                _sshResult.value = null
                val execResult = sshClient.execute(effective, pending.command)
                sshAuditStore.record(
                    pending.config.host, pending.config.port, pending.config.username,
                    "exec", pending.command, execResult.exitCode == 0,
                )
                _sshResult.value = execResult
            } else if (pending.installBindMode != null) {
                _sshBootstrapResult.value = null
                runSshInstallDirect(effective, pending.installBindMode)
            }
        }
    }

    private suspend fun runSshInstallDirect(config: SshConfig, bindMode: String) {
        val result = sshBootstrapClient.install(config, bindMode)
        if (result.ok && result.token != null) {
            remoteNodeFeature.saveNode(
                RemoteNode(
                    nodeId = result.nodeId ?: ("node_ssh_" + System.currentTimeMillis()),
                    name = config.host,
                    host = config.host,
                    port = 8787,
                    scheme = "http",
                    token = result.token,
                    allowedCommands = listOf(
                        "hostname", "uname", "uptime", "free", "df", "ps",
                        "os_release", "network", "tailscale",
                    ),
                    enabled = true,
                    paused = false,
                    createdAtEpochMillis = System.currentTimeMillis(),
                    updatedAtEpochMillis = System.currentTimeMillis(),
                ),
            )
        }
        sshAuditStore.record(
            config.host, config.port, config.username,
            "bootstrap", bindMode, result.ok,
        )
        _sshBootstrapResult.value = result
    }

    fun listSshFiles(config: SshConfig, path: String) {
        viewModelScope.launch {
            _sshFileResult.value = null
            _sshFileEntries.value = null
            sshCredentialStore.save(config)
            val trusted = sshHostKeyStore.get(config.host, config.port)
            if (trusted == null) {
                _sshFileResult.value = SshFileResult(false, path, "", "请先在 SSH 命令终端确认主机指纹")
                return@launch
            }
            val effective = config.copy(hostKeyFingerprint = trusted)
            val files = sshClient.listFiles(effective, path)
            if (files == null) {
                _sshFileResult.value = SshFileResult(false, path, "", "列目录失败（路径不存在或不可读）")
                sshAuditStore.record(config.host, config.port, config.username, "list", path, false)
            } else {
                _sshFileEntries.value = files
                _sshFileResult.value = SshFileResult(true, path, "共 ${files.size} 项")
                sshAuditStore.record(config.host, config.port, config.username, "list", path, true)
            }
        }
    }

    fun readSshFile(config: SshConfig, path: String) {
        viewModelScope.launch {
            _sshFileResult.value = null
            sshCredentialStore.save(config)
            val trusted = sshHostKeyStore.get(config.host, config.port)
            if (trusted == null) {
                _sshFileResult.value = SshFileResult(false, path, "", "请先在 SSH 命令终端确认主机指纹")
                return@launch
            }
            val effective = config.copy(hostKeyFingerprint = trusted)
            val content = sshClient.download(effective, path)
            if (content == null) {
                sshAuditStore.record(config.host, config.port, config.username, "read", path, false)
                _sshFileResult.value = SshFileResult(false, path, "", "读取失败（文件不存在或不可读）")
            } else {
                sshAuditStore.record(config.host, config.port, config.username, "read", path, true)
                _sshFileResult.value = SshFileResult(true, path, content)
            }
        }
    }

    fun writeSshFile(config: SshConfig, path: String, content: String) {
        viewModelScope.launch {
            _sshFileResult.value = null
            sshCredentialStore.save(config)
            val trusted = sshHostKeyStore.get(config.host, config.port)
            if (trusted == null) {
                _sshFileResult.value = SshFileResult(false, path, "", "请先在 SSH 命令终端确认主机指纹")
                return@launch
            }
            val effective = config.copy(hostKeyFingerprint = trusted)
            val ok = sshClient.upload(effective, path, content)
            sshAuditStore.record(config.host, config.port, config.username, "write", path, ok)
            _sshFileResult.value = if (ok) {
                SshFileResult(true, path, "已写入 $path")
            } else {
                SshFileResult(false, path, "", "写入失败（请检查路径权限）")
            }
        }
    }

    fun clearSshFileResult() {
        _sshFileResult.value = null
        _sshFileEntries.value = null
    }

    fun mkdirSshFile(config: SshConfig, path: String) {
        viewModelScope.launch {
            _sshFileResult.value = null
            val trusted = sshHostKeyStore.get(config.host, config.port)
            if (trusted == null) {
                _sshFileResult.value = SshFileResult(false, path, "", "请先确认主机指纹")
                return@launch
            }
            val effective = config.copy(hostKeyFingerprint = trusted)
            val ok = sshClient.mkdir(effective, path)
            sshAuditStore.record(config.host, config.port, config.username, "mkdir", path, ok)
            _sshFileResult.value = if (ok) SshFileResult(true, path, "已创建目录 $path")
            else SshFileResult(false, path, "", "创建目录失败")
        }
    }

    fun deleteSshFile(config: SshConfig, path: String) {
        viewModelScope.launch {
            _sshFileResult.value = null
            val trusted = sshHostKeyStore.get(config.host, config.port)
            if (trusted == null) {
                _sshFileResult.value = SshFileResult(false, path, "", "请先确认主机指纹")
                return@launch
            }
            val effective = config.copy(hostKeyFingerprint = trusted)
            val ok = sshClient.delete(effective, path)
            sshAuditStore.record(config.host, config.port, config.username, "delete", path, ok)
            _sshFileResult.value = if (ok) SshFileResult(true, path, "已删除 $path")
            else SshFileResult(false, path, "", "删除失败（目录非空或权限不足）")
        }
    }

    fun renameSshFile(config: SshConfig, oldPath: String, newPath: String) {
        viewModelScope.launch {
            _sshFileResult.value = null
            val trusted = sshHostKeyStore.get(config.host, config.port)
            if (trusted == null) {
                _sshFileResult.value = SshFileResult(false, oldPath, "", "请先确认主机指纹")
                return@launch
            }
            val effective = config.copy(hostKeyFingerprint = trusted)
            val ok = sshClient.rename(effective, oldPath, newPath)
            sshAuditStore.record(config.host, config.port, config.username, "rename", "$oldPath -> $newPath", ok)
            _sshFileResult.value = if (ok) SshFileResult(true, newPath, "已重命名为 $newPath")
            else SshFileResult(false, oldPath, "", "重命名失败")
        }
    }

    private fun ensureTrustedOrNull(config: SshConfig): SshConfig? {
        val trusted = sshHostKeyStore.get(config.host, config.port) ?: return null
        return config.copy(hostKeyFingerprint = trusted)
    }

    fun listSshServices(config: SshConfig) {
        viewModelScope.launch {
            _sshServiceResult.value = null
            val effective = ensureTrustedOrNull(config) ?: run {
                _sshServiceResult.value = SshExecResult(-1, "", "请先确认主机指纹")
                return@launch
            }
            val result = sshClient.execute(effective, "systemctl --no-pager --type=service --all list-units", 30_000)
            sshAuditStore.record(config.host, config.port, config.username, "service_list", "systemctl list-units", result.exitCode == 0)
            _sshServiceResult.value = result
        }
    }

    fun runSshService(config: SshConfig, command: String, detail: String) {
        viewModelScope.launch {
            _sshServiceResult.value = null
            val effective = ensureTrustedOrNull(config) ?: run {
                _sshServiceResult.value = SshExecResult(-1, "", "请先确认主机指纹")
                return@launch
            }
            val result = sshClient.execute(effective, command, 30_000)
            sshAuditStore.record(config.host, config.port, config.username, "service", detail, result.exitCode == 0)
            _sshServiceResult.value = result
        }
    }

    fun startSshService(config: SshConfig, name: String) {
        runSshService(config, "sudo systemctl start " + shellQuote(name.trim()), "start $name")
    }

    fun stopSshService(config: SshConfig, name: String) {
        runSshService(config, "sudo systemctl stop " + shellQuote(name.trim()), "stop $name")
    }

    fun restartSshService(config: SshConfig, name: String) {
        runSshService(config, "sudo systemctl restart " + shellQuote(name.trim()), "restart $name")
    }

    fun statusSshService(config: SshConfig, name: String) {
        runSshService(config, "systemctl status " + shellQuote(name.trim()) + " --no-pager -l", "status $name")
    }

    fun logsSshService(config: SshConfig, name: String) {
        runSshService(config, "journalctl -u " + shellQuote(name.trim()) + " -n 200 --no-pager", "logs $name")
    }

    fun readSshNodeAudit(config: SshConfig) {
        viewModelScope.launch {
            _sshNodeLogResult.value = null
            val effective = ensureTrustedOrNull(config) ?: run {
                _sshNodeLogResult.value = SshExecResult(-1, "", "请先确认主机指纹")
                return@launch
            }
            val result = sshClient.execute(effective, "tail -n 300 ~/.voiceconfig-node/audit.jsonl", 30_000)
            _sshNodeLogResult.value = result
        }
    }

    fun readSshNodeLog(config: SshConfig) {
        viewModelScope.launch {
            _sshNodeLogResult.value = null
            val effective = ensureTrustedOrNull(config) ?: run {
                _sshNodeLogResult.value = SshExecResult(-1, "", "请先确认主机指纹")
                return@launch
            }
            val result = sshClient.execute(effective, "tail -n 300 ~/.voiceconfig-node/node.log 2>/dev/null || echo '(no node.log)'", 30_000)
            _sshNodeLogResult.value = result
        }
    }

    fun clearSshServiceResult() {
        _sshServiceResult.value = null
    }

    fun clearSshNodeLogResult() {
        _sshNodeLogResult.value = null
    }

    fun startSshShell(config: SshConfig) {
        viewModelScope.launch {
            closeSshShell()
            val trusted = sshHostKeyStore.get(config.host, config.port)
            if (trusted == null) {
                _sshShellError.value = "请先在 SSH 命令终端确认主机指纹"
                _sshShellRunning.value = false
                return@launch
            }
            val effective = config.copy(hostKeyFingerprint = trusted)
            _sshShellOutput.value = ""
            _sshShellError.value = null
            _sshShellRunning.value = true
            sshShellHostHost = config.host
            sshShellHostPort = config.port
            sshShellHostUser = config.username
            sshShellHandle = sshClient.openShell(
                effective,
                onOutput = { text ->
                    _sshShellOutput.value = (_sshShellOutput.value + text).takeLast(200_000)
                    sshAuditStore.record(
                        config.host, config.port, config.username,
                        "shell_out", text.take(200), true,
                    )
                },
                onClosed = {
                    _sshShellRunning.value = false
                    sshShellHandle = null
                    sshShellHostHost = null
                    sshShellHostPort = null
                    sshShellHostUser = null
                },
            )
            if (sshShellHandle == null) {
                _sshShellRunning.value = false
                _sshShellError.value = "无法打开交互式终端"
                sshAuditStore.record(config.host, config.port, config.username, "shell_start", "failed", false)
            } else {
                sshAuditStore.record(config.host, config.port, config.username, "shell_start", "opened", true)
            }
        }
    }

    fun sendSshShellCommand(command: String) {
        if (command.isBlank()) return
        val handle = sshShellHandle ?: return
        viewModelScope.launch {
            val sent = withContext(Dispatchers.IO) {
                handle.send(command)
            }
            // 终端命令不记录敏感内容太长；只记录前 200 字符。
            sshAuditStore.record(
                sshShellHostHost ?: "",
                sshShellHostPort ?: 22,
                sshShellHostUser ?: "",
                "shell_cmd",
                command.take(200),
                sent,
            )
            if (!sent) {
                _sshShellError.value = "发送命令失败：" + (handle.lastSendError ?: "终端连接可能已断开")
            }
        }
    }

    fun resizeSshShell(width: Int, height: Int) {
        val handle = sshShellHandle ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                handle.resize(width, height)
            }
        }
    }

    fun closeSshShell() {
        val handle = sshShellHandle
        sshShellHandle = null
        _sshShellRunning.value = false
        sshShellHostHost = null
        sshShellHostPort = null
        sshShellHostUser = null
        if (handle != null) {
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    handle.close()
                }
            }
        }
    }

    fun clearSshShellOutput() {
        _sshShellOutput.value = ""
    }

    fun clearSshShellError() {
        _sshShellError.value = null
    }

    fun loadSshAudit() {
        viewModelScope.launch {
            _sshAuditText.value = withContext(Dispatchers.IO) {
                sshAuditStore.readRecent(200)
            }
        }
    }

    fun clearSshAudit() {
        _sshAuditText.value = ""
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\''") + "'"

    fun clearSshResult() {
        _sshResult.value = null
    }

    fun clearSshBootstrapResult() {
        _sshBootstrapResult.value = null
    }

}
