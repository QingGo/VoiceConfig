package com.voiceconfig.app.agent

import javax.inject.Inject
import javax.inject.Singleton

data class LuckinOrderDraft(
    val store: String,
    val drink: String,
    val size: String = "默认",
    val sugar: String = "默认",
    val ice: String = "默认",
    val quantity: Int = 1,
    val price: Double? = null,
)

@Singleton
class LuckinPrepareOrderTool @Inject constructor() : AgentTool {
    override val name: String = "luckin_prepare_order"
    override val description: String =
        "生成瑞幸点单确认清单。参数：{\"store\":\"门店\",\"drink\":\"饮品\",\"size\":\"杯型\",\"sugar\":\"甜度\",\"ice\":\"冰量\",\"quantity\":1}。只生成清单，不自动下单。"
    override val metadata: AgentToolMetadata = AgentToolMetadata(
        category = "消费技能",
        group = ToolGroup.APP_SKILL,
        risk = ToolRisk.LOW,
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val store = args["store"]?.toString()?.trim()?.ifBlank { null } ?: "当前定位门店"
        val drink = args["drink"]?.toString()?.trim()?.ifBlank { null }
            ?: return ToolResult.failure("缺少参数 drink（饮品名称）")
        val size = args["size"]?.toString()?.trim()?.ifBlank { "默认" } ?: "默认"
        val sugar = args["sugar"]?.toString()?.trim()?.ifBlank { "默认" } ?: "默认"
        val ice = args["ice"]?.toString()?.trim()?.ifBlank { "默认" } ?: "默认"
        val quantity = (args["quantity"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 1
        val price = (args["price"] as? Number)?.toDouble()
        val draft = LuckinOrderDraft(
            store = store,
            drink = drink,
            size = size,
            sugar = sugar,
            ice = ice,
            quantity = quantity,
            price = price,
        )
        val lines = listOf(
            "门店：${draft.store}",
            "饮品：${draft.drink}",
            "杯型：${draft.size}",
            "甜度：${draft.sugar}",
            "冰量：${draft.ice}",
            "数量：${draft.quantity}",
            price?.let { "预估单价：$it 元" },
        ).filterNotNull().joinToString("\n")
        return ToolResult.success(
            "已生成瑞幸点单确认清单（未下单）:\n$lines",
            mapOf(
                "draft" to draft,
                "requiresConfirmation" to true,
                "safe" to true,
                "summary" to lines,
            ),
        )
    }
}

@Singleton
class LuckinOpenTool @Inject constructor(
    private val openAppTool: OpenAppTool,
) : AgentTool {
    override val name: String = "luckin_open"
    override val description: String = "打开瑞幸咖啡 App"
    override val metadata: AgentToolMetadata = AgentToolMetadata(
        category = "消费技能",
        group = ToolGroup.APP_SKILL,
        risk = ToolRisk.MEDIUM,
        mutatesUi = true,
        requiresAutoVerify = true,
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult =
        openAppTool.execute(mapOf("package" to "com.luckincoffee.android"))
}

enum class LuckinOrderStep {
    IDLE,
    STORE_SELECTED,
    DRINK_SELECTED,
    IN_CART,
    CONFIRMED,
    CANCELLED,
}

data class LuckinOrderSession(
    val store: String = "",
    val drink: String = "",
    val size: String = "默认",
    val sugar: String = "默认",
    val ice: String = "默认",
    val quantity: Int = 1,
    val step: LuckinOrderStep = LuckinOrderStep.IDLE,
    val confirmedAt: Long? = null,
)

@javax.inject.Singleton
class LuckinOrderSessionManager @javax.inject.Inject constructor() {
    private var session: LuckinOrderSession = LuckinOrderSession()

    fun current(): LuckinOrderSession = session

    fun selectStore(store: String): LuckinOrderSession {
        session = session.copy(store = store, step = LuckinOrderStep.STORE_SELECTED)
        return session
    }

    fun selectDrink(drink: String, size: String = "默认", sugar: String = "默认", ice: String = "默认"): LuckinOrderSession {
        session = session.copy(
            drink = drink,
            size = size,
            sugar = sugar,
            ice = ice,
            step = LuckinOrderStep.DRINK_SELECTED,
        )
        return session
    }

    fun addToCart(quantity: Int = 1): LuckinOrderSession {
        session = session.copy(quantity = quantity.coerceAtLeast(1), step = LuckinOrderStep.IN_CART)
        return session
    }

    fun confirm(): LuckinOrderSession {
        session = session.copy(step = LuckinOrderStep.CONFIRMED, confirmedAt = System.currentTimeMillis())
        return session
    }

    fun cancel(): LuckinOrderSession {
        session = session.copy(step = LuckinOrderStep.CANCELLED)
        return session
    }

    fun reset() {
        session = LuckinOrderSession()
    }
}
