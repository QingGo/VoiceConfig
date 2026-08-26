package com.voiceconfig.app.remote

import com.voiceconfig.data.local.repository.RemoteNode
import com.voiceconfig.data.local.repository.RemoteNodeRepository
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class RemoteCommandResult(
    val ok: Boolean,
    val command: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val error: String? = null,
)

/**
 * 同步执行远程节点白名单命令。
 */
@Singleton
class RemoteCommandClient @Inject constructor(
    private val repository: RemoteNodeRepository,
) {

    suspend fun execute(nodeName: String, command: String): RemoteCommandResult = withContext(Dispatchers.IO) {
        val node = resolveNode(nodeName) ?: return@withContext RemoteCommandResult(
            ok = false,
            command = command,
            stdout = "",
            stderr = "",
            exitCode = null,
            error = "没有找到可用的远程节点：$nodeName",
        )
        if (command !in node.allowedCommands) {
            return@withContext RemoteCommandResult(
                ok = false,
                command = command,
                stdout = "",
                stderr = "",
                exitCode = null,
                error = "命令 $command 不在节点 ${node.name} 的允许列表中：${node.allowedCommands.joinToString("/")}",
            )
        }
        val token = node.token ?: return@withContext RemoteCommandResult(
            ok = false,
            command = command,
            stdout = "",
            stderr = "",
            exitCode = null,
            error = "节点 ${node.name} 没有可用 Token",
        )
        val body = JSONObject().put("command", command)
        val response = request(node, body) ?: return@withContext RemoteCommandResult(
            ok = false,
            command = command,
            stdout = "",
            stderr = "",
            exitCode = null,
            error = "远程请求失败（无响应）",
        )
        RemoteCommandResult(
            ok = response.optBoolean("ok", false),
            command = command,
            stdout = response.optString("stdout", ""),
            stderr = response.optString("stderr", ""),
            exitCode = if (response.has("exit_code")) response.optInt("exit_code") else null,
            error = response.optString("error", "").ifBlank { null },
        )
    }

    private suspend fun resolveNode(nodeName: String): RemoteNode? {
        val nodes = repository.getNodes()
        return nodes.firstOrNull { it.enabled && !it.paused && (it.name == nodeName || it.nodeId == nodeName) }
            ?: nodes.firstOrNull { it.enabled && !it.paused && nodeName.isBlank() }
    }

    private fun request(node: RemoteNode, body: JSONObject): JSONObject? {
        val token = node.token ?: return null
        val url = URL("${node.scheme}://${node.host}:${node.port}/api/exec")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (text.isBlank()) null else JSONObject(text)
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
