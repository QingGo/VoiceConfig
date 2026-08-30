package com.voiceconfig.app.agent

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

/**
 * 瑞幸快速点单宏。
 *
 * 目标：代替 LLM 逐轮“读屏→点击→读屏”，用预先验证过的文字路径快速到达
 * “免密支付”确认页并停住。
 *
 * 安全边界：
 * - 只点击菜单/商品/规格/“立即购买/去结算”等进入确认页的按钮；
 * - 绝不点击“提交订单/确认支付/立即支付/确认付款/免密支付”；
 * - 到达确认页后写入 TaskPlan WAITING_CONFIRM。
 */
@Singleton
class LuckinQuickOrderTool @Inject constructor(
    private val openAppTool: OpenAppTool,
    private val uiActionLayer: UiActionLayer,
    private val dismissPopupsTool: DismissPopupsTool,
    private val taskPlanStore: TaskPlanStore,
) : AgentTool {

    override val name: String = "luckin_quick_order"
    override val description: String =
        "快速在瑞幸点常规冰美式并停在免密支付页；自动打开瑞幸、跳过更新/弹窗、选门店与标准美式、点立即购买、关闭换购浮层，绝不点击支付。"

    override val metadata: AgentToolMetadata = AgentToolMetadata(
        category = "消费技能",
        group = ToolGroup.APP_SKILL,
        risk = ToolRisk.MEDIUM,
        mutatesUi = true,
        requiresAutoVerify = false,
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val storeName = args["store"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: "融创天朗珑府店"
        val drink = args["drink"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: "标准美式"
        val temperature = args["temperature"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: "冰"

        val openResult = openAppTool.execute(mapOf("package" to "com.lucky.luckyclient"))
        if (!openResult.ok) {
            return ToolResult.failure(openResult.message, openResult.data)
        }

        val used = mutableSetOf<String>()
        for (step in 0 until MAX_STEPS) {
            delay(280)
            val ui = uiActionLayer.readUi(maxNodes = 120, maxChars = 3000)
            if (!ui.ok) {
                return ToolResult.failure(
                    "瑞幸快速点单中断：无法读取当前界面（${ui.error ?: ui.message}）",
                    mapOf("step" to step, "summary" to ui.summary.take(600)),
                )
            }
            val text = ui.summary

            // 已到达支付/订单确认终端：清理浮层并停住。
            if (isTerminal(text)) {
                val dismiss = runCatching { dismissPopupsTool.execute(emptyMap()) }.getOrNull()
                val reason = "已到达瑞幸免密支付确认页（$drink / $temperature），等待用户确认。点击支付前不会自动提交。"
                taskPlanStore.set(
                    TaskPlan(
                        goal = "瑞幸快速点单",
                        waitingForHuman = reason,
                        status = TaskPlanStatus.WAITING_CONFIRM,
                    ),
                )
                taskPlanStore.saveCurrent()
                return ToolResult.success(
                    "已到达免密支付确认页：$drink / $temperature。未点击支付/提交订单。等待用户确认。",
                    mapOf(
                        "verified" to true,
                        "terminalStop" to true,
                        "foregroundPackage" to ui.foregroundPackage,
                        "summary" to ui.summary.take(800),
                        "reason" to reason,
                        "dismissedOverlay" to (dismiss?.ok == true),
                    ),
                )
            }

            // 更新/营销启动页：优先点常规关闭，不行则返回。
            if (text.contains("立即更新") || text.contains("马上更新")) {
                val closeTexts = listOf("以后再说", "暂不更新", "跳过", "取消", "我知道了", "关闭", "X")
                val tapped = uiActionLayer.tapByText(*closeTexts.toTypedArray()).ok
                if (!tapped && used.add("back")) {
                    uiActionLayer.back()
                }
                continue
            }

            // 首页/主界面：进入“菜单”。
            if (text.contains("菜单") && !text.contains("去结算") && !text.contains("美式家族") && !text.contains("保存口味") && used.add("menu")) {
                if (uiActionLayer.tapByText("菜单").ok) continue
            }

            // 门店选择：优先使用默认门店，其次尝试常见门店关键词。
            if ((text.contains("更多门店") || text.contains("距你")) && used.add("store")) {
                val candidates = listOfNotNull(
                    storeName,
                    "融创天朗珑府店",
                    "附近门店",
                    "自提",
                )
                if (uiActionLayer.tapByText(*candidates.toTypedArray()).ok) continue
            }

            // 菜单分类：进入“美式家族”。
            if (text.contains("美式家族") && !text.contains("保存口味") && !text.contains("立即购买") && used.add("family")) {
                if (uiActionLayer.tapByText("美式家族").ok) continue
            }

            // 商品选择：进入标准美式详情。
            if (text.contains(drink) && !text.contains("保存口味") && !text.contains("立即购买") && used.add("drink")) {
                if (uiActionLayer.tapByText(drink, "标准美式").ok) continue
            }

            // 商品详情：调整温度并点击“立即购买”。
            if (text.contains("保存口味") || text.contains("立即购买")) {
                val hasHot = text.contains("热") || text.contains("热饮")
                val hasIce = text.contains("冰") || text.contains("冰饮")
                if (temperature == "冰" && hasHot && !hasIce && used.add("ice")) {
                    if (uiActionLayer.tapByText("冰").ok) continue
                }
                if (used.add("buy")) {
                    if (uiActionLayer.tapByText("立即购买", "去结算", "确认").ok) continue
                }
            }

            // 购物车/结算入口。
            if (text.contains("去结算") && !text.contains("保存口味") && used.add("checkout")) {
                if (uiActionLayer.tapByText("去结算", "立即购买").ok) continue
            }

            // 换购浮层/确认订单但尚未被终端识别：关闭浮层后继续。
            if (text.contains("一键换购") || text.contains("确认订单") || text.contains("免密支付")) {
                runCatching { dismissPopupsTool.execute(emptyMap()) }
                continue
            }
        }

        return ToolResult.failure(
            "瑞幸快速点单未能到达免密支付页（已达最大步数）；请让 LLM 接管并检查当前界面。",
            mapOf("summary" to uiActionLayer.readUi(80, 1200).summary),
        )
    }

    private fun isTerminal(text: String): Boolean {
        val t = text.lowercase()
        return (t.contains("免密支付") || t.contains("确认订单")) && !t.contains("立即更新")
            && (t.contains("支付") || t.contains("应付") || t.contains("确认"))
    }

    companion object {
        private const val MAX_STEPS = 14
    }
}
