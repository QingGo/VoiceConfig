package com.voiceconfig.app.agent

import com.voiceconfig.data.local.repository.ShoppingItemRecord
import com.voiceconfig.data.local.repository.ShoppingItemRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShoppingSaveTool @Inject constructor(
    private val repository: ShoppingItemRepository,
) : AgentTool {
    override val name: String = "shopping_save"
    override val description: String =
        "保存已研究的商品到采购清单。参数：{\"products\":\"商品JSON数组\",\"status\":\"WATCH|RECOMMENDED\",\"note\":\"可选备注\"}"
    override val metadata: AgentToolMetadata = AgentToolMetadata(
        category = "购物研究",
        group = ToolGroup.RESEARCH,
        risk = ToolRisk.LOW,
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val raw = args["products"] ?: return ToolResult.failure("缺少参数 products")
        val products = when (raw) {
            is List<*> -> parseList(raw)
            else -> ProductAnalyzer.parseProducts(raw.toString())
        }
        if (products.isEmpty()) return ToolResult.failure("没有可保存的商品")
        val status = (args["status"] as? String)?.uppercase()?.takeIf { it in setOf("WATCH", "RECOMMENDED", "BOUGHT") }
            ?: "WATCH"
        val note = args["note"]?.toString().orEmpty()
        var saved = 0
        products.forEach { p ->
            val existing = repository.getByProductId(p.id)
            repository.save(
                ShoppingItemRecord(
                    productId = p.id,
                    title = p.title,
                    platform = p.platform,
                    price = p.price,
                    originalPrice = p.originalPrice,
                    rating = p.rating,
                    reviewCount = p.reviewCount,
                    sales = p.sales,
                    tags = p.tags,
                    url = p.url,
                    note = if (note.isNotBlank()) note else existing?.note.orEmpty(),
                    status = if (existing != null) existing.status else status,
                ),
            )
            saved++
        }
        return ToolResult.success("已保存 $saved 个商品到购物研究清单", mapOf("saved" to saved, "status" to status))
    }

    private fun parseList(list: List<*>): List<ProductInfo> {
        val json = org.json.JSONArray()
        list.forEach { raw ->
            if (raw is Map<*, *>) {
                val obj = org.json.JSONObject()
                raw.forEach { (k, v) -> obj.put(k.toString(), v) }
                json.put(obj)
            }
        }
        return ProductAnalyzer.parseProducts(json.toString())
    }
}

@Singleton
class ShoppingListTool @Inject constructor(
    private val repository: ShoppingItemRepository,
) : AgentTool {
    override val name: String = "shopping_list"
    override val description: String =
        "读取已保存的购物研究清单。参数：{\"status\":\"可选 WATCH|RECOMMENDED|BOUGHT\"}"
    override val metadata: AgentToolMetadata = AgentToolMetadata(
        category = "购物研究",
        group = ToolGroup.RESEARCH,
        risk = ToolRisk.READ_ONLY,
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val status = args["status"]?.toString()?.uppercase()?.takeIf { it.isNotBlank() }
        val items = if (status != null) repository.getByStatus(status) else repository.getItems()
        val lines = items.take(100).joinToString("\n") { item ->
            "- ${item.title} | ${item.platform} | ${item.price}元 | ${item.rating?.let { "评分$it" } ?: "无评分"} | ${item.status}"
        }
        return ToolResult.success(
            "购物研究清单共 ${items.size} 项",
            mapOf("count" to items.size, "items" to lines, "status" to status),
        )
    }
}

@Singleton
class ShoppingUpdateStatusTool @Inject constructor(
    private val repository: ShoppingItemRepository,
) : AgentTool {
    override val name: String = "shopping_update_status"
    override val description: String =
        "更新购物清单状态。参数：{\"productId\":\"商品ID\",\"status\":\"WATCH|RECOMMENDED|BOUGHT\"}"
    override val metadata: AgentToolMetadata = AgentToolMetadata(
        category = "购物研究",
        group = ToolGroup.RESEARCH,
        risk = ToolRisk.LOW,
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val productId = args["productId"]?.toString()?.trim()?.ifBlank { null }
            ?: return ToolResult.failure("缺少参数 productId")
        val status = args["status"]?.toString()?.trim()?.uppercase()?.takeIf { it in setOf("WATCH", "RECOMMENDED", "BOUGHT") }
            ?: return ToolResult.failure("status 必须是 WATCH/RECOMMENDED/BOUGHT")
        val item = repository.getByProductId(productId)
            ?: return ToolResult.failure("购物清单中不存在 productId=$productId")
        repository.updateStatus(item.id, status)
        return ToolResult.success("已更新 $productId 为 $status", mapOf("productId" to productId, "status" to status))
    }
}
