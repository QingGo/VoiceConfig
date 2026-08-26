package com.voiceconfig.app.remote

import com.voiceconfig.data.local.repository.RemoteNode

data class TransportResult(
    val ok: Boolean,
    val output: String,
    val error: String? = null,
    val exitCode: Int? = null,
)

/**
 * Phase2：统一远程访问抽象。
 *
 * 调用方只面对 RemoteTransport，不关心底层是 HTTP 节点还是 SSH。
 */
interface RemoteTransport {
    suspend fun health(): TransportResult
    suspend fun exec(command: String): TransportResult
}

class HttpNodeTransport(
    private val node: RemoteNode,
    private val client: RemoteCommandClient,
) : RemoteTransport {
    override suspend fun health(): TransportResult {
        val result = client.execute(node.name, "hostname")
        return TransportResult(
            ok = result.ok,
            output = result.stdout,
            error = result.error,
            exitCode = result.exitCode,
        )
    }

    override suspend fun exec(command: String): TransportResult {
        val result = client.execute(node.name, command)
        return TransportResult(
            ok = result.ok,
            output = result.stdout,
            error = result.error ?: result.stderr,
            exitCode = result.exitCode,
        )
    }
}

class SshTransport(
    private val config: SshConfig,
    private val client: SshClient,
) : RemoteTransport {
    override suspend fun health(): TransportResult {
        val result = client.execute(config, "uname -a")
        return TransportResult(
            ok = result.exitCode == 0,
            output = result.stdout,
            error = result.stderr.ifBlank { null },
            exitCode = result.exitCode,
        )
    }

    override suspend fun exec(command: String): TransportResult {
        val result = client.execute(config, command)
        return TransportResult(
            ok = result.exitCode == 0,
            output = result.stdout,
            error = result.stderr.ifBlank { null },
            exitCode = result.exitCode,
        )
    }
}
