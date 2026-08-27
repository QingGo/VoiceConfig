package com.voiceconfig.app.agent

import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProductCompareTool @Inject constructor() : AgentTool {
    override val name: String = "product_compare"
    override val description: String =
        "比较多个商品的价格/评分/评价，输出最低价、最高评分、性价比推荐。参数：{\"products\":\"[{\\\"title\\\":\\\"...\\\",\\\"platform\\\":\\\"...\\\",\\\"price\\\":129,\\\"rating\\\":4.8,\\\"reviewCount\\\":100}]\"}"
    override val metadata: AgentToolMetadata = AgentToolMetadata(
        category = "购物研究",
        group = ToolGroup.RESEARCH,
        risk = ToolRisk.READ_ONLY,
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val raw = args["products"] ?: return ToolResult.failure("缺少参数 products（商品 JSON 数组）")
        val products = when (raw) {
            is List<*> -> parseFromList(raw)
            else -> ProductAnalyzer.parseProducts(raw.toString())
        }
        if (products.isEmpty()) return ToolResult.failure("没有可解析的商品，请传入至少一个商品")
        val result = ProductAnalyzer.compare(products)
        return ToolResult.success(
            result.summary,
            mapOf(
                "products" to products,
                "bestValueId" to result.bestValue?.id,
                "cheapestId" to result.cheapest?.id,
                "highestRatedId" to result.highestRated?.id,
                "mostReviewedId" to result.mostReviewed?.id,
                "summary" to result.summary,
            ),
        )
    }

    private fun parseFromList(list: List<*>): List<ProductInfo> {
        val items = mutableListOf<Map<String, Any?>>()
        list.forEach { raw ->
            when (raw) {
                is Map<*, *> -> items += raw.entries.associate { it.key.toString() to it.value }
                is String -> items += emptyMap()
            }
        }
        if (items.isEmpty()) return emptyList()
        val json = JSONArray()
        items.forEach { map ->
            val obj = org.json.JSONObject()
            map.forEach { (key, value) -> obj.put(key, value) }
            json.put(obj)
        }
        return ProductAnalyzer.parseProducts(json.toString())
    }
}

@Singleton
class ProductSearchTool @Inject constructor(
    private val deepSeekWebSearch: DeepSeekWebSearch,
) : AgentTool {
    override val name: String = "product_search"
    override val description: String =
        "搜索商品信息（多平台/价格/评价线索）。参数：{\"query\":\"搜索词\"}，返回搜索结果摘要"
    override val metadata: AgentToolMetadata = AgentToolMetadata(
        category = "购物研究",
        group = ToolGroup.RESEARCH,
        risk = ToolRisk.READ_ONLY,
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val query = args["query"]?.toString()?.trim()?.ifBlank { null }
            ?: return ToolResult.failure("缺少参数 query")
        val result = deepSeekWebSearch.search(query)
        return if (result.ok) {
            ToolResult.success(
                "搜索完成（${result.text.length} 字符）",
                mapOf("query" to query, "result" to result.text),
            )
        } else {
            ToolResult.failure("搜索失败：${result.error ?: "未知错误"}")
        }
    }
}

@Singleton
class ProductExtractTool @Inject constructor(
    private val chat: AgentChat,
) : AgentTool {
    override val name: String = "product_extract"
    override val description: String =
        "从搜索文本中提取结构化商品信息（ProductInfo JSON）。参数：{\"text\":\"搜索/比价原文\"}"
    override val metadata: AgentToolMetadata = AgentToolMetadata(
        category = "购物研究",
        group = ToolGroup.RESEARCH,
        risk = ToolRisk.READ_ONLY,
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val text = args["text"]?.toString()?.trim()?.ifBlank { null }
            ?: return ToolResult.failure("缺少参数 text")
        val system = "你是一个商品信息提取器。请从用户提供的文本中提取所有商品信息，输出严格 JSON：{\"products\":[{\"id\":\"唯一ID\",\"title\":\"标题\",\"platform\":\"平台\",\"price\":数字,\"originalPrice\":数字或null,\"rating\":数字或null,\"reviewCount\":整数或null,\"sales\":整数或null,\"tags\":[\"标签\"],\"url\":\"\"}]}。不要输出其他内容。"
        val output = runCatching {
            chat.complete(system, listOf(AgentMessage("user", text)))
        }.getOrNull() ?: return ToolResult.failure("结构化提取失败：模型未返回结果")
        val products = ProductAnalyzer.parseProductsFlexible(output)
        if (products.isEmpty()) return ToolResult.failure("未能从文本中提取到商品")
        return ToolResult.success(
            "已提取 ${products.size} 个商品",
            mapOf("products" to products, "count" to products.size),
        )
    }
}
