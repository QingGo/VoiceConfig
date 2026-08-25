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

/**
 * R4 remote Skill execution client.
 * Sends an approved command-skill to a remote node for local execution.
 */
@Singleton
class RemoteSkillClient @Inject constructor(
    private val repository: RemoteNodeRepository,
) {

    suspend fun runSkill(
        node: String,
        skill: Map<String, Any?>,
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        val target = resolveNode(node)
        val body = JSONObject().put("skill", JSONObject(skill))
        val response = request(target, "/api/skills/run", body)
        response.toMap()
    }

    private suspend fun resolveNode(node: String): RemoteNode {
        val nodes = repository.getNodes()
        val target = nodes.firstOrNull { it.enabled && !it.paused && (it.name == node || it.nodeId == node) }
            ?: nodes.firstOrNull { it.enabled && !it.paused && node.isBlank() }
            ?: throw RemoteTaskException("没有找到可用的远程节点：$node")
        return target
    }

    private fun request(node: RemoteNode, path: String, body: JSONObject): JSONObject {
        val token = node.token ?: throw RemoteTaskException("节点 ${node.name} 没有可用 Token")
        val url = URL("${node.scheme}://${node.host}:${node.port}$path")
        val conn = url.openConnection() as HttpURLConnection
        try {
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
            val json = if (text.isBlank()) JSONObject() else JSONObject(text)
            if (code !in 200..299) {
                throw RemoteTaskException(json.optString("error", "HTTP $code"))
            }
            return json
        } catch (e: RemoteTaskException) {
            throw e
        } catch (e: Exception) {
            throw RemoteTaskException("远程 Skill 执行失败：${e.message}")
        } finally {
            conn.disconnect()
        }
    }
}

private fun JSONObject.toMap(): Map<String, Any?> {
    val result = linkedMapOf<String, Any?>()
    val iterator = keys()
    while (iterator.hasNext()) {
        val key = iterator.next()
        result[key] = opt(key)
    }
    return result
}
