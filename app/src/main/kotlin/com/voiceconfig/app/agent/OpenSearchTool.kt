package com.voiceconfig.app.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.voiceconfig.app.executor.ShizukuExecutionChannel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 直接打开搜索引擎结果页。
 *
 * 用于避免“打开浏览器 -> 找输入框 -> 输入中文”的冗长链路，尤其是在
 * Chrome + 百度搜索这类场景下，直接 Deep Link 到搜索结果页成功率远高于
 * 手动输入中文。
 */
@Singleton
class OpenSearchTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizuku: ShizukuCommandRunner,
) : AgentTool {

    override val name: String = "open_search"
    override val description: String = "直接用 Deep Link 打开搜索引擎结果页，适合网页搜索；参数：{\"engine\":\"baidu\",\"query\":\"Android 15 发布时间\",\"browser\":\"chrome\"}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val query = (args["query"] ?: args["q"])?.toString()?.trim()?.ifBlank { null }
            ?: return ToolResult.failure("缺少参数 query")
        val engine = (args["engine"]?.toString() ?: "baidu").lowercase()
        val browser = (args["browser"]?.toString() ?: "chrome").lowercase()
        val url = buildSearchUrl(engine, query) ?: return ToolResult.failure("不支持的搜索引擎：$engine")

        // 优先通过 Shizuku 在 Chrome 中打开搜索页；失败时用普通 Intent 默认浏览器。
        if (shizuku.isAvailable()) {
            val packageName = if (browser == "chrome") "com.android.chrome" else null
            val command = if (packageName != null) {
                arrayOf(
                    "am", "start", "-a", "android.intent.action.VIEW",
                    "-d", url, "-p", packageName,
                )
            } else {
                arrayOf(
                    "am", "start", "-a", "android.intent.action.VIEW",
                    "-d", url,
                )
            }
            val result = shizuku.execute(*command)
            val failure = ShizukuExecutionChannel.isLaunchFailure(result.stderr)
            if (result.ok && !failure) {
                return ToolResult.success(
                    "已用 Deep Link 打开${engineLabel(engine)}搜索结果：$query",
                    mapOf("engine" to engine, "query" to query, "url" to url, "mode" to "shizuku"),
                )
            }
            if (failure) {
                // 继续尝试普通 Intent
            }
        }

        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.success(
                "已用 Deep Link 打开${engineLabel(engine)}搜索结果：$query",
                mapOf("engine" to engine, "query" to query, "url" to url, "mode" to "intent"),
            )
        } catch (e: Exception) {
            ToolResult.failure("打开搜索页失败：${e.message}", mapOf("engine" to engine, "query" to query, "url" to url))
        }
    }

    private fun buildSearchUrl(engine: String, query: String): String? {
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        return when (engine) {
            "baidu" -> "https://www.baidu.com/s?wd=$encoded"
            "google" -> "https://www.google.com/search?q=$encoded"
            "bing" -> "https://www.bing.com/search?q=$encoded"
            else -> null
        }
    }

    private fun engineLabel(engine: String): String = when (engine) {
        "baidu" -> "百度"
        "google" -> "Google"
        "bing" -> "Bing"
        else -> engine
    }
}
