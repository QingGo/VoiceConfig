package com.voiceconfig.app.agent

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 搜索工具：调用 DeepSeek 内置 web_search。
 * 参数：{"query":"..."} 或 {"q":"..."}，可选 {"maxUses":3}
 */
@Singleton
class WebSearchTool @Inject constructor(
    private val deepSeekWebSearch: DeepSeekWebSearch,
) : AgentTool {

    override val name: String = "web_search"
    override val description: String = "搜索互联网并返回结果摘要，参数：{\"query\":\"关键词\"}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val query = (args["query"] ?: args["q"])?.toString()?.trim()?.ifBlank { null }
            ?: return ToolResult.failure("缺少参数 query")
        val maxUses = (args["maxUses"] as? Number)?.toInt()?.coerceIn(1, 5) ?: 3
        val result = deepSeekWebSearch.search(query, maxUses)
        return if (result.ok) {
            ToolResult.success(
                "搜索完成（${result.text.length} 字符）",
                mapOf("result" to result.text, "query" to query),
            )
        } else {
            ToolResult.failure("搜索失败：${result.error ?: "未知错误"}")
        }
    }
}
