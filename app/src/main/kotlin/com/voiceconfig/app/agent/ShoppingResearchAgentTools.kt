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
