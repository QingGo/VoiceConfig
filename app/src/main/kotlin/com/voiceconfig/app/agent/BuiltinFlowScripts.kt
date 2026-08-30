package com.voiceconfig.app.agent

/**
 * 内置通用流程脚本。
 *
 * 这些是“数据”，不是逻辑。新增 App 流程时只需在这里添加一条脚本，
 * 执行逻辑统一由 [UiFlowExecutor] 处理。
 */
object BuiltinFlowScripts {

    val luckinStandardIce: FlowScript = FlowScript(
        id = "builtin_luckin_standard_ice",
        name = "瑞幸标准冰美式到免密支付",
        description = "打开瑞幸，按标准路径选择冰美式并停在免密支付/确认订单页，绝不自动支付。",
        openPackage = "com.lucky.luckyclient",
        terminalMarkers = listOf("免密支付", "确认订单"),
        maxIterations = 18,
        forbiddenActionTokens = listOf("pay", "confirm_payment", "send", "delete"),
        steps = listOf(
            FlowStep(
                id = "update",
                name = "跳过更新/营销页",
                whenContains = listOf("立即更新", "马上更新"),
                action = FlowAction.TapTextOrBack(listOf("以后再说", "暂不更新", "跳过", "取消", "我知道了", "关闭")),
            ),
            FlowStep(
                id = "menu",
                name = "进入菜单",
                whenContains = listOf("菜单"),
                whenNotContains = listOf("去结算", "美式家族", "保存口味"),
                action = FlowAction.TapText(listOf("菜单")),
            ),
            FlowStep(
                id = "store",
                name = "选择门店",
                whenContains = listOf("更多门店", "距你"),
                action = FlowAction.TapText(listOf("融创天朗珑府店", "红星美凯龙至尊MALL生活馆店", "附近门店", "自提")),
            ),
            FlowStep(
                id = "family",
                name = "进入美式家族",
                whenContains = listOf("美式家族"),
                whenNotContains = listOf("保存口味", "立即购买"),
                action = FlowAction.TapText(listOf("美式家族")),
            ),
            FlowStep(
                id = "drink",
                name = "选择标准美式",
                whenContains = listOf("标准美式"),
                whenNotContains = listOf("保存口味", "立即购买"),
                action = FlowAction.TapText(listOf("标准美式")),
            ),
            FlowStep(
                id = "ice",
                name = "选择冰",
                whenContains = listOf("保存口味", "热"),
                whenNotContains = listOf("冰"),
                action = FlowAction.TapText(listOf("冰")),
            ),
            FlowStep(
                id = "buy",
                name = "立即购买/去结算",
                whenContains = listOf("保存口味", "立即购买", "去结算"),
                action = FlowAction.TapText(listOf("立即购买", "去结算")),
            ),
            FlowStep(
                id = "dismiss",
                name = "关闭换购浮层",
                whenContains = listOf("一键换购", "确认订单", "免密支付"),
                action = FlowAction.DismissPopups,
            ),
        ),
    )

    val all: List<FlowScript> = listOf(luckinStandardIce)
}
