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
        group = ToolGroup.REMOTE,
        risk = ToolRisk.SENSITIVE,
        sensitive = true,
        requiresOptIn = true,
        executionTimeoutMs = 120_000L,
        autoConfirmable = false,
        effect = ToolEffect.SYSTEM,
        reversibility = ToolReversibility.IRREVERSIBLE,
        confirmationMode = ConfirmationMode.PER_ACTION,
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
        val response = httpGet(node, "/health")
        val health = response.body ?: return ToolResult.failed(
            "节点 ${node.name} 健康检查失败：${response.error ?: "HTTP ${response.code}"}",
            code = "remote_node.http_${response.code}",
            retryable = response.code == 0 || response.code >= 500,
        )
        val ok = health.optBoolean("ok", false)
        val nodeId = health.optString("node", "")
        val version = health.optString("version", "")
        return if (ok) {
            ToolResult.success(
                "节点 ${node.name} 在线：version=$version node=$nodeId",
                mapOf("node" to nodeId, "version" to version, "health" to jsonToMap(health)),
            )
        } else {
            ToolResult.failed("节点 ${node.name} 返回异常", code = "remote_node.health_failed")
        }
    }

    private suspend fun exec(args: Map<String, Any?>): ToolResult {
        val node = resolveNode(args) ?: return ToolResult.failure("未找到可用的远程节点")
        val command = args["command"]?.toString()?.trim().orEmpty()
        if (command.isBlank()) return ToolResult.failure("缺少参数 command")
        if (command !in node.allowedCommands) {
            return ToolResult.failure("命令 $command 不在节点 ${node.name} 的允许列表中：${node.allowedCommands.joinToString("/")}")
        }
        val body = JSONObject().apply {
            put("command", command)
            args["idempotency_key"]?.toString()?.takeIf { it.isNotBlank() }?.let { put("idempotency_key", it) }
            args["trace_id"]?.toString()?.takeIf { it.isNotBlank() }?.let { put("trace_id", it) }
        }
        val response = httpPost(node, "/api/exec", body)
        val result = response.body ?: return ToolResult.failed(
            "远程执行请求失败：${response.error ?: "HTTP ${response.code}"}",
            code = "remote_node.http_${response.code}",
            retryable = response.code == 0 || response.code >= 500,
        )
        val ok = result.optBoolean("ok", false)
        val stdout = result.optString("stdout", "").take(8_000)
        val stderr = result.optString("stderr", "").take(2_000)
        val nodeId = result.optString("node", node.nodeId)
        val data = mapOf(
            "node" to nodeId,
            "command" to command,
            "stdout" to stdout,
            "stderr" to stderr,
            "exit_code" to result.optInt("exit_code", 0),
            "duration_ms" to result.optLong("duration_ms", 0L),
            "argv" to result.optJSONArray("argv")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }.orEmpty(),
            "raw" to jsonToMap(result),
        )
        val taskId = result.optString("task_id")
        if (taskId.isNotBlank() || result.optString("status") == "accepted") {
            return ToolResult.accepted("节点 ${node.name} 已接受任务", taskId, data)
        }
        return if (ok) {
            ToolResult.success("节点 ${node.name} 执行 $command 成功", data)
        } else {
            ToolResult.failed(
                "节点 ${node.name} 执行 $command 失败：${result.optString("error", stderr)}",
                code = result.optString("error", "remote_node.exec_failed"),
                retryable = result.optBoolean("retryable", false),
                data = data,
            )
        }
    }

    private fun jsonToMap(obj: JSONObject): Map<String, Any?> =
        obj.keys().asSequence().associateWith { key -> obj.opt(key) }
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

    private data class NodeResponse(
        val body: JSONObject?,
        val code: Int,
        val error: String?,
    )

    private fun httpGet(node: RemoteNode, path: String): NodeResponse =
        request("GET", node, path, null)

    private fun httpPost(node: RemoteNode, path: String, body: JSONObject): NodeResponse =
        request("POST", node, path, body)

    private fun request(method: String, node: RemoteNode, path: String, body: JSONObject?): NodeResponse {
        val token = node.token ?: return NodeResponse(null, 0, "节点未配置 token")
        val url = URL("${node.scheme}://${node.host}:${node.port}$path")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = method
            conn.connectTimeout = 5_000
            conn.readTimeout = 120_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val parsed = runCatching { if (text.isBlank()) null else JSONObject(text) }.getOrNull()
            if (code in 200..299) {
                NodeResponse(parsed, code, if (parsed == null) "响应不是 JSON" else null)
            } else {
                NodeResponse(parsed, code, "HTTP $code ${text.take(300)}")
            }
        } catch (e: Exception) {
            NodeResponse(null, 0, e.message ?: e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }
}