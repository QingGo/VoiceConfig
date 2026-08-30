package com.voiceconfig.app.agent

import com.voiceconfig.app.ai.ApiKeyStore
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 企业微信官方 API 工具。
 *
 * 个人微信没有官方自动化 API，且容易触发风控；
 * 面向“自动发送/自动回复”的产品化能力统一走企业微信官方接口。
 */
@Singleton
class WecomSendMessageTool @Inject constructor(
    private val apiKeyStore: ApiKeyStore,
) : AgentTool {

    override val name: String = "wecom_send_message"
    override val description: String =
        "通过企业微信官方 API 给成员/部门/标签发送应用消息。参数：{\"toUser\":\"zhangsan\",\"content\":\"文本内容\",\"toParty\":\"\",\"toTag\":\"\"}；需要先在设置中配置企业微信 CorpId/AgentId/Secret"

    override val metadata: AgentToolMetadata = AgentToolMetadata(
        category = "通信技能",
        group = ToolGroup.APP_SKILL,
        risk = ToolRisk.HIGH,
        sensitive = true,
        mutatesUi = false,
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val corpId = apiKeyStore.wecomCorpId
        val agentId = apiKeyStore.wecomAgentId
        val secret = apiKeyStore.wecomSecret
        if (corpId.isBlank() || agentId.isBlank() || secret.isBlank()) {
            return ToolResult.failure("未配置企业微信 API，请在设置中填写 CorpId / AgentId / Secret")
        }
        val content = args["content"]?.toString()?.trim()?.ifBlank { null }
            ?: return ToolResult.failure("缺少参数 content（消息内容）")
        val toUser = args["toUser"]?.toString()?.trim()?.ifBlank { null }
        val toParty = args["toParty"]?.toString()?.trim()?.ifBlank { null }
        val toTag = args["toTag"]?.toString()?.trim()?.ifBlank { null }
        if (toUser == null && toParty == null && toTag == null) {
            return ToolResult.failure("至少需要 toUser / toParty / toTag 之一")
        }

        val token = getAccessToken(corpId, secret)
        if (token == null) {
            return ToolResult.failure("获取企业微信 access_token 失败，请检查 CorpId/Secret")
        }

        val body = JSONObject().apply {
            put("touser", toUser ?: "")
            put("toparty", toParty ?: "")
            put("totag", toTag ?: "")
            put("msgtype", "text")
            put("agentid", agentId.toIntOrNull() ?: 0)
            put("text", JSONObject().put("content", content))
            put("safe", 0)
        }

        val url = "https://qyapi.weixin.qq.com/cgi-bin/message/send?access_token=$token"
        val response = postJson(url, body.toString())
        val root = runCatching { JSONObject(response) }.getOrNull()
            ?: return ToolResult.failure("企业微信 API 响应解析失败")
        val errcode = root.optInt("errcode", -1)
        if (errcode != 0) {
            return ToolResult.failure("企业微信发送失败 errcode=$errcode errmsg=${root.optString("errmsg")}")
        }
        return ToolResult.success(
            "已通过企业微信官方 API 发送消息",
            mapOf(
                "toUser" to (toUser ?: ""),
                "toParty" to (toParty ?: ""),
                "toTag" to (toTag ?: ""),
                "sent" to true,
                "source" to "wecom_official_api",
            ),
        )
    }

    /** 仅校验企业微信 CorpId/Secret 是否能获取 access_token，不发送任何消息。 */
    suspend fun verifyCredentials(): ToolResult {
        val corpId = apiKeyStore.wecomCorpId
        val agentId = apiKeyStore.wecomAgentId
        val secret = apiKeyStore.wecomSecret
        if (corpId.isBlank() || agentId.isBlank() || secret.isBlank()) {
            return ToolResult.failure("未配置企业微信 API，请在设置中填写 CorpId / AgentId / Secret")
        }
        val token = getAccessToken(corpId, secret)
        return if (token != null) {
            ToolResult.success(
                "企业微信 API 凭证验证通过（CorpId/AgentId/Secret 有效）",
                mapOf("valid" to true, "tokenLength" to token.length, "source" to "wecom_verify"),
            )
        } else {
            ToolResult.failure("企业微信 API 凭证验证失败，请检查 CorpId/Secret/网络")
        }
    }


    private val tokenLock = Any()
    private var cachedToken: String? = null
    private var cachedTokenCorpId: String = ""
    private var cachedTokenSecret: String = ""
    private var cachedTokenExpireAtMs: Long = 0L

    private suspend fun getAccessToken(corpId: String, secret: String): String? {
        val now = System.currentTimeMillis()
        synchronized(tokenLock) {
            if (cachedToken != null &&
                cachedTokenCorpId == corpId &&
                cachedTokenSecret == secret &&
                now < cachedTokenExpireAtMs
            ) {
                return cachedToken
            }
        }
        val url = "https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=" +
            URLEncoder.encode(corpId, "UTF-8") +
            "&corpsecret=" +
            URLEncoder.encode(secret, "UTF-8")
        val response = get(url) ?: return null
        val root = runCatching { JSONObject(response) }.getOrNull() ?: return null
        if (root.optInt("errcode", -1) != 0) return null
        val token = root.optString("access_token").takeIf { it.isNotBlank() } ?: return null
        val expiresIn = root.optInt("expires_in", 7200).coerceAtLeast(60)
        synchronized(tokenLock) {
            cachedToken = token
            cachedTokenCorpId = corpId
            cachedTokenSecret = secret
            cachedTokenExpireAtMs = System.currentTimeMillis() + (expiresIn - 60) * 1000L
        }
        return token
    }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            val code = conn.responseCode
            val text = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            conn.disconnect()
            text
        }.getOrNull()
    }

    private suspend fun postJson(url: String, json: String): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = if (code in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
        }
        conn.disconnect()
        text
    }
}
