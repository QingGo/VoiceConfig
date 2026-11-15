package com.voiceconfig.app.agent

import android.content.Context
import com.voiceconfig.data.local.security.RemoteNodeTokenCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ExternalToolSummary(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val tools: List<String>,
    val source: String,
)

@Singleton
class ExternalToolManager @Inject constructor(
    @ApplicationContext context: Context,
    private val registry: ToolRegistry,
    private val cipher: RemoteNodeTokenCipher,
) {
    private val prefs = context.getSharedPreferences("external_tools", Context.MODE_PRIVATE)
    private val registeredNames = mutableSetOf<String>()

    fun importPack(raw: String): Result<String> = runCatching {
        val pack = ExternalToolPackParser.parse(raw).getOrThrow().copy(enabled = true)
        val json = ExternalToolPackParser.toStorageJson(pack)
        val arr = loadPackArray()
        var replaced = false
        for (i in 0 until arr.length()) {
            if (arr.optJSONObject(i)?.optString("id") == pack.id) {
                arr.put(i, json); replaced = true; break
            }
        }
        if (!replaced) arr.put(json)
        prefs.edit().putString(KEY_PACKS, arr.toString()).apply()
        if (!pack.authToken.isNullOrBlank()) saveCipher(KEY_PACK_TOKEN_PREFIX + pack.id, pack.authToken)
        "已导入 ${pack.name}（${pack.tools.size} 个工具）"
    }

    fun removePack(id: String) {
        val arr = loadPackArray()
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("id") != id) kept.put(obj)
        }
        prefs.edit().putString(KEY_PACKS, kept.toString()).remove(KEY_PACK_TOKEN_PREFIX + id).apply()
    }

    fun setPackEnabled(id: String, enabled: Boolean) {
        val arr = loadPackArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("id") == id) obj.put("enabled", enabled)
        }
        prefs.edit().putString(KEY_PACKS, arr.toString()).apply()
    }

    fun configureMcp(url: String, token: String, allowedTools: Set<String>, enabled: Boolean, tlsPin: String? = null): Result<Unit> = runCatching {
        require(url.startsWith("https://") || url.startsWith("http://localhost") || url.startsWith("http://127.0.0.1")) {
            "MCP URL 必须是 HTTPS（localhost 调试可用 HTTP）"
        }
        prefs.edit()
            .putString(KEY_MCP_URL, url.trim())
            .putBoolean(KEY_MCP_ENABLED, enabled)
            .putString(KEY_MCP_ALLOWED, JSONArray(allowedTools.toList()).toString())
            .putString(KEY_MCP_PIN, tlsPin?.trim().orEmpty())
            .apply()
        if (token.isNotBlank()) saveCipher(KEY_MCP_TOKEN, token.trim())
    }

    fun clearMcp() {
        prefs.edit().remove(KEY_MCP_URL).remove(KEY_MCP_TOKEN).remove(KEY_MCP_ALLOWED).putBoolean(KEY_MCP_ENABLED, false).apply()
    }

    suspend fun refresh(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            registeredNames.toList().forEach { registry.unregister(it) }
            registeredNames.clear()
            var count = 0
            loadPacks().filter { it.enabled }.forEach { pack ->
                pack.tools.forEach { spec ->
                    val tool = HttpToolPackTool(pack, spec)
                    if (registry.get(tool.name) == null) {
                        registry.register(tool); registeredNames += tool.name; count++
                    }
                }
            }
            val mcp = loadMcpConfig()
            if (mcp != null && mcp.enabled && mcp.token.isNotBlank()) {
                val client = McpHttpClient(mcp)
                val defs = client.listTools().getOrThrow()
                defs.filter { it.name != "fan_approve" }
                    .filter { mcp.allowedTools.isEmpty() || it.name in mcp.allowedTools }.forEach { def ->
                    val tool = McpTool(client, "fan", def)
                    if (registry.get(tool.name) == null) {
                        registry.register(tool); registeredNames += tool.name; count++
                    }
                }
            }
            count
        }
    }

    fun refreshBlocking() {
        kotlinx.coroutines.runBlocking { refresh() }
    }

    fun currentToolNames(): Set<String> = registeredNames.toSet()

    fun summaries(): List<ExternalToolSummary> {
        val packs = loadPacks().map { p ->
            ExternalToolSummary(p.id, p.name, p.enabled, p.tools.map { it.name }, "HTTP Tool Pack")
        }
        val mcp = loadMcpConfig()
        val mcpSummary = if (mcp != null && mcp.enabled) {
            listOf(ExternalToolSummary("mcp-fan", "FAN MCP", true, mcp.allowedTools.toList(), "MCP"))
        } else emptyList()
        return packs + mcpSummary
    }

    private fun loadPacks(): List<ExternalToolPack> {
        val arr = loadPackArray()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("id")
            val token = readCipher(KEY_PACK_TOKEN_PREFIX + id)
            runCatching { ExternalToolPackParser.parse(obj.toString()).getOrThrow().copy(authToken = token) }.getOrNull()
        }
    }

    private fun loadMcpConfig(): McpServerConfig? {
        val url = prefs.getString(KEY_MCP_URL, "").orEmpty()
        if (url.isBlank()) return null
        val allowed = runCatching {
            JSONArray(prefs.getString(KEY_MCP_ALLOWED, "[]") ?: "[]").let { arr ->
                (0 until arr.length()).map { arr.optString(it) }.toSet()
            }
        }.getOrDefault(emptySet())
        return McpServerConfig(
            url = url,
            token = readCipher(KEY_MCP_TOKEN).orEmpty(),
            enabled = prefs.getBoolean(KEY_MCP_ENABLED, false),
            allowedTools = allowed,
            tlsPin = prefs.getString(KEY_MCP_PIN, null)?.takeIf { it.isNotBlank() },
        )
    }

    private fun loadPackArray(): JSONArray =
        runCatching { JSONArray(prefs.getString(KEY_PACKS, "[]") ?: "[]") }.getOrDefault(JSONArray())

    private fun saveCipher(key: String, plain: String) {
        val encrypted = cipher.encrypt(plain)
        prefs.edit().putString(key, encrypted.iv + ":" + encrypted.ciphertext).apply()
    }

    private fun readCipher(key: String): String? {
        val raw = prefs.getString(key, null) ?: return null
        val parts = raw.split(":", limit = 2)
        if (parts.size != 2) return null
        return runCatching { cipher.decrypt(parts[1], parts[0]) }.getOrNull()
    }

    companion object {
        private const val KEY_PACKS = "packs"
        private const val KEY_PACK_TOKEN_PREFIX = "pack_token_"
        private const val KEY_MCP_URL = "mcp_url"
        private const val KEY_MCP_TOKEN = "mcp_token"
        private const val KEY_MCP_ENABLED = "mcp_enabled"
        private const val KEY_MCP_ALLOWED = "mcp_allowed"
        private const val KEY_MCP_PIN = "mcp_tls_pin"
    }
}
