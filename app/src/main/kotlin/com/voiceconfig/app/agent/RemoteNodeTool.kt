package com.voiceconfig.app.agent

import com.voiceconfig.data.local.repository.RemoteNode
import com.voiceconfig.data.local.repository.RemoteNodeRepository
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 受控远程节点工具。
 *
 * 安全约束：
 * - 只能操作已注册且 enabled && !paused 的节点；
 * - 只能执行节点 allowlist 中已有的只读命令；
 * - 不把 Token 返回给模型；
 * - 当前定位为 ADVANCED，不进入核心工具列表，后续可加用户级授权再开放。
 */
@Singleton
class RemoteNodeTool @Inject constructor(
    private val repository: RemoteNodeRepository,
) : AgentTool {

    override val name: String = "remote_node"
    override val description: String = "查询已登记的远程节点，或对指定节点执行其允许的只读命令。参数：{\"action\":\"list|health|exec\",\"node\":\"节点名\",\"command\":\"命令名\"}"

    override val metadata: AgentToolMetadata = AgentToolMetadata(
        category = "远程",
        group = ToolGroup.ADVANCED,
        risk = ToolRisk.SENSITIVE,
        sensitive = true,
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult = withContext(Dispatchers.IO) {
        val action = args["action"]?.toString()?.trim()?.lowercase() ?: "list"
        when (action) {
            "list" -> listNodes()
            "health" -> health(args)
            "exec" -> exec(args)
            else -> ToolResult.failure("未知 action：$action，只支持 list/health/exec")
        }
    }

    private suspend fun listNodes(): ToolResult {
        val nodes = repository.getNodes()
        val lines = nodes.map {
            val state = if (!it.enabled) "disabled" else if (it.paused) "paused" else "active"
            "- ${it.name} (${it.nodeId}) ${it.scheme}://${it.host}:${it.port} state=$state commands=${it.allowedCommands.joinToString("/")}"
        }
        return ToolResult.success("已登记 ${nodes.size} 个远程节点", mapOf("nodes" to lines))
    }

    private suspend fun health(args: Map<String, Any?>): ToolResult {
        val node = resolveNode(args) ?: return ToolResult.failure("未找到可用的远程节点")
        val health = httpGet(node, "/health") ?: return ToolResult.failure("节点健康检查失败")
        val ok = health.optBoolean("ok", false)
        val nodeId = health.optString("node", "")
        val version = health.optString("version", "")
        return if (ok) {
            ToolResult.success("节点 ${node.name} 在线：version=$version node=$nodeId", mapOf("health" to health.toString()))
        } else {
            ToolResult.failure("节点 ${node.name} 返回异常")
        }
    }

    private suspend fun exec(args: Map<String, Any?>): ToolResult {
        val node = resolveNode(args) ?: return ToolResult.failure("未找到可用的远程节点")
        val command = args["command"]?.toString()?.trim().orEmpty()
        if (command.isBlank()) return ToolResult.failure("缺少参数 command")
        if (command !in node.allowedCommands) {
            return ToolResult.failure("命令 $command 不在节点 ${node.name} 的允许列表中：${node.allowedCommands.joinToString("/")}")
        }
        val body = JSONObject().put("command", command)
        val result = httpPost(node, "/api/exec", body) ?: return ToolResult.failure("远程执行请求失败")
        val ok = result.optBoolean("ok", false)
        val stdout = result.optString("stdout", "").take(2000)
        val stderr = result.optString("stderr", "").take(500)
        val nodeId = result.optString("node", node.nodeId)
        return if (ok) {
            ToolResult.success("节点 ${node.name} 执行 $command 成功", mapOf("node" to nodeId, "command" to command, "stdout" to stdout))
        } else {
            ToolResult.failure("节点 ${node.name} 执行 $command 失败：${result.optString("error", stderr)}", mapOf("node" to nodeId, "command" to command))
        }
    }

    private suspend fun resolveNode(args: Map<String, Any?>): RemoteNode? {
        val name = args["node"]?.toString()?.trim().orEmpty()
        val all = runCatching { repository.getNodes() }.getOrElse { emptyList() }
        if (name.isBlank()) {
            return all.firstOrNull { it.enabled && !it.paused }
        }
        return all.firstOrNull {
            it.enabled && !it.paused && (it.name == name || it.nodeId == name)
        }
    }

    private fun httpGet(node: RemoteNode, path: String): JSONObject? =
        request("GET", node, path, null)

    private fun httpPost(node: RemoteNode, path: String, body: JSONObject): JSONObject? =
        request("POST", node, path, body)

    private fun request(method: String, node: RemoteNode, path: String, body: JSONObject?): JSONObject? {
        val token = node.token ?: return null
        val url = URL("${node.scheme}://${node.host}:${node.port}$path")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = method
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
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
