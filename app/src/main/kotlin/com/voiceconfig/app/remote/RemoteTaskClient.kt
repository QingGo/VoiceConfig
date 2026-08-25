package com.voiceconfig.app.remote

import com.voiceconfig.data.local.repository.RemoteNode
import com.voiceconfig.data.local.repository.RemoteNodeRepository
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class RemoteTaskInfo(
    val taskId: String,
    val command: String,
    val status: String,
    val progress: Int,
    val checkpoint: JSONObject?,
    val result: JSONObject?,
    val idempotencyKey: String? = null,
    val duplicate: Boolean = false,
)

class RemoteTaskException(message: String) : Exception(message)

/**
 * R3 task protocol client for VoiceConfig remote nodes.
 *
 * Supports submit / ACK / checkpoint / query / resume / list with idempotency keys.
 */
@Singleton
class RemoteTaskClient @Inject constructor(
    private val repository: RemoteNodeRepository,
) {

    suspend fun submit(
        node: String,
        command: String,
        idempotencyKey: String? = null,
        autoStart: Boolean = false,
    ): RemoteTaskInfo = withContext(Dispatchers.IO) {
        val target = resolveNode(node)
        val body = JSONObject()
            .put("command", command)
            .put("auto_start", autoStart)
        if (!idempotencyKey.isNullOrBlank()) {
            body.put("idempotency_key", idempotencyKey)
        }
        val response = request(target, "/api/tasks", "POST", body)
        parseTaskResponse(response)
    }

    suspend fun ack(node: String, taskId: String): RemoteTaskInfo = withContext(Dispatchers.IO) {
        val target = resolveNode(node)
        val response = request(target, "/api/tasks/$taskId/ack", "POST", JSONObject())
        parseTaskResponse(response)
    }

    suspend fun checkpoint(
        node: String,
        taskId: String,
        progress: Int,
        checkpoint: Map<String, Any?>,
    ): RemoteTaskInfo = withContext(Dispatchers.IO) {
        val target = resolveNode(node)
        val body = JSONObject()
            .put("progress", progress)
            .put("checkpoint", JSONObject(checkpoint))
        val response = request(target, "/api/tasks/$taskId/checkpoint", "POST", body)
        parseTaskResponse(response)
    }

    suspend fun get(node: String, taskId: String): RemoteTaskInfo = withContext(Dispatchers.IO) {
        val target = resolveNode(node)
        val response = request(target, "/api/tasks/$taskId", "GET", null)
        parseTaskResponse(response)
    }

    suspend fun resume(node: String, taskId: String, retryFailed: Boolean = false): RemoteTaskInfo =
        withContext(Dispatchers.IO) {
            val target = resolveNode(node)
            val body = JSONObject().put("retry", retryFailed)
            val response = request(target, "/api/tasks/$taskId/resume", "POST", body)
            parseTaskResponse(response)
        }

    suspend fun list(node: String): List<RemoteTaskInfo> = withContext(Dispatchers.IO) {
        val target = resolveNode(node)
        val response = request(target, "/api/tasks", "GET", null)
        val array: JSONArray = response.optJSONArray("tasks") ?: JSONArray()
        buildList {
            for (i in 0 until array.length()) {
                add(parseTask(array.getJSONObject(i)))
            }
        }
    }

    private suspend fun resolveNode(node: String): RemoteNode {
        val nodes = repository.getNodes()
        val target = nodes.firstOrNull { it.enabled && !it.paused && (it.name == node || it.nodeId == node) }
            ?: nodes.firstOrNull { it.enabled && !it.paused && node.isBlank() }
            ?: throw RemoteTaskException("没有找到可用的远程节点：$node")
        return target
    }

    private fun parseTaskResponse(response: JSONObject): RemoteTaskInfo {
        if (!response.optBoolean("ok", false)) {
            throw RemoteTaskException(response.optString("error", "remote task request failed"))
        }
        val task = response.optJSONObject("task")
            ?: JSONObject().put("id", response.optString("task_id", "")).put("command", "").put("status", response.optString("status", "pending"))
        return parseTask(task, response.optBoolean("duplicate", false))
    }

    private fun parseTask(task: JSONObject, duplicate: Boolean = false): RemoteTaskInfo = RemoteTaskInfo(
        taskId = task.optString("id", task.optString("task_id", "")),
        command = task.optString("command", ""),
        status = task.optString("status", ""),
        progress = task.optInt("progress", 0),
        checkpoint = task.optJSONObject("checkpoint"),
        result = task.optJSONObject("result"),
        idempotencyKey = task.optString("idempotency_key", "").ifBlank { null },
        duplicate = duplicate,
    )

    private fun request(
        node: RemoteNode,
        path: String,
        method: String,
        body: JSONObject?,
    ): JSONObject {
        val token = node.token ?: throw RemoteTaskException("节点 ${node.name} 没有可用 Token")
        val url = URL("${node.scheme}://${node.host}:${node.port}$path")
        val conn = url.openConnection() as HttpURLConnection
        try {
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
            if (text.isBlank()) throw RemoteTaskException("远程节点返回为空 (HTTP $code)")
            val json = JSONObject(text)
            if (code !in 200..299) {
                throw RemoteTaskException(json.optString("error", "HTTP $code") + " " + json.optString("message", ""))
            }
            return json
        } catch (e: RemoteTaskException) {
            throw e
        } catch (e: Exception) {
            throw RemoteTaskException("远程请求失败：${e.message}")
        } finally {
            conn.disconnect()
        }
    }
}
