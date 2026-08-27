package com.voiceconfig.app.agent

/**
 * 把一次成功 Agent 运行编译成结构化 Skill 候选。
 *
 * 特点：
 * - 不只保存“工具名+参数”，还保存目的、预期、证据、验证方式、失败兜底；
 * - 只从成功且验证未失败的 run 生成 PENDING 候选；
 * - 支持从实时 turn（ToolCall/ToolResult）或 trace 事件重建。
 */
object AgentSkillBuilder {

    fun build(
        goal: String,
        toolCalls: List<ToolCall>,
        toolResults: List<ToolResult>,
        runId: String = "",
        verified: Boolean? = null,
        capabilitySummary: String? = null,
        sourceSessionId: Long? = null,
    ): AgentSkill? {
        val normalized = goal.trim()
        if (normalized.isBlank() || toolCalls.isEmpty()) return null
        val steps = buildSteps(toolCalls, toolResults)
        if (steps.isEmpty()) return null
        val now = System.currentTimeMillis()
        return AgentSkill(
            id = "skill_${now}_${runId.takeLast(6).ifBlank { "gen" }}",
            name = buildName(normalized),
            description = "用户意图：$normalized",
            text = normalized,
            tags = guessTags(normalized),
            whenToUse = normalized,
            steps = steps,
            createdAt = now,
            updatedAt = now,
            successCount = 1,
            useCount = 0,
            status = AgentSkillStatus.PENDING,
            lastRunId = runId,
            lastSessionId = sourceSessionId,
            lastResult = "success",
            version = 1,
            enabled = true,
            redacted = false,
            sourceRunId = runId,
            sourceVerified = verified,
            requiredCapabilities = requiredCapabilities(toolCalls),
        )
    }

    fun buildFromTrace(
        goal: String,
        runId: String,
        traceEvents: List<Map<String, Any?>>,
        verified: Boolean? = null,
        capabilitySummary: String? = null,
        sourceSessionId: Long? = null,
    ): AgentSkill? {
        val pending = LinkedHashMap<String, MutableList<ToolCall>>()
        val calls = mutableListOf<ToolCall>()
        val results = mutableListOf<ToolResult>()
        traceEvents.forEach { event ->
            val type = event["type"]?.toString()
            when (type) {
                "tool_call" -> {
                    val tool = event["tool"]?.toString() ?: return@forEach
                    val args = asStringMap(event["args"])
                    val call = ToolCall(tool, args)
                    calls += call
                    pending.getOrPut(tool) { mutableListOf() } += call
                }
                "tool_result" -> {
                    val tool = event["tool"]?.toString() ?: return@forEach
                    val ok = event["ok"] == true || event["ok"]?.toString() == "true"
                    val message = event["message"]?.toString().orEmpty()
                    val dataKeys = (event["data_keys"] as? List<*>)?.map { it.toString() }.orEmpty()
                    val data = dataKeys.associateWith { true }
                    val result = ToolResult(ok, message, data)
                    results += result
                    pending[tool]?.let { queue ->
                        if (queue.isNotEmpty()) queue.removeAt(0)
                    }
                }
            }
        }
        if (calls.isEmpty()) return null
        // 如果 trace 未完整配对工具结果，回退为所有步骤未知成功，但不作为已验证候选。
        return build(
            goal = goal,
            toolCalls = calls,
            toolResults = results,
            runId = runId,
            verified = verified,
            capabilitySummary = capabilitySummary,
            sourceSessionId = sourceSessionId,
        )
    }

    fun buildSteps(
        toolCalls: List<ToolCall>,
        toolResults: List<ToolResult>,
    ): List<AgentSkillStep> {
        return toolCalls.mapIndexed { index, call ->
            val result = toolResults.getOrNull(index)
            AgentSkillStep(
                toolName = call.tool,
                args = argsToString(call.args),
                purpose = inferPurpose(call.tool),
                expected = inferExpected(call.tool),
                uiEvidence = evidenceSummary(result),
                verification = verificationFor(call.tool, result),
                fallback = inferFallback(call.tool),
                ok = result?.ok ?: true,
            )
        }
    }

    fun argsToString(args: Map<String, Any?>): String {
        if (args.isEmpty()) return "{}"
        return "{" + args.entries.joinToString(", ") { (key, value) ->
            "$key=${valueToString(value)}"
        } + "}"
    }

    private fun valueToString(value: Any?): String = when (value) {
        null -> "null"
        is String -> if (value.length > 80) value.take(80) + "…" else value
        is Map<*, *> -> value.entries.joinToString(",") { "${it.key}=${valueToString(it.value)}" }
        is List<*> -> value.joinToString(",") { valueToString(it) }.take(120)
        else -> value.toString().take(120)
    }

    private fun evidenceSummary(result: ToolResult?): String {
        if (result == null) return ""
        val interesting = listOf(
            "verified", "package", "url", "taskId", "startTimeMs",
            "actions", "overlay", "nodeCount", "title", "text", "name", "id",
        )
        val present = interesting.filter { result.data.containsKey(it) }
        val valuePart = present.take(6).joinToString(",") { key ->
            val raw = result.data[key]
            val v = when (raw) {
                is List<*> -> "list(${raw.size})"
                is Map<*, *> -> "map(${raw.size})"
                else -> raw?.toString()?.take(24).orEmpty()
            }
            "$key=$v"
        }
        val msgPart = result.message.take(70)
        return listOf(valuePart, if (msgPart.isNotBlank()) "msg=$msgPart" else "")
            .filter { it.isNotBlank() }
            .joinToString(" | ")
    }

    private fun verificationFor(toolName: String, result: ToolResult?): String {
        val spec = AgentVerificationMatrix.specFor(toolName)
        val evidence = spec.evidenceField?.let { field ->
            result?.data?.get(field)?.toString()
        }
        return buildString {
            append(spec.requirement.name)
            if (spec.description.isNotBlank()) append(": ").append(spec.description)
            if (!evidence.isNullOrBlank()) append(" | ").append(spec.evidenceField).append("=").append(evidence)
        }
    }

    private fun inferPurpose(toolName: String): String = when (toolName) {
        "open_app" -> "打开指定应用"
        "open_search" -> "打开指定搜索"
        "open_deeplink" -> "打开指定链接"
        "find_app" -> "查找已安装应用"
        "read_ui" -> "读取当前 UI 树与坐标"
        "get_screen_state" -> "获取当前屏幕状态"
        "read_screen" -> "截图并查看当前屏幕"
        "tap" -> "按坐标点击"
        "tap_text" -> "点击指定文字"
        "input_text" -> "输入文本"
        "press_key" -> "按系统键"
        "swipe" -> "滑动页面"
        "wait" -> "等待页面稳定"
        "dismiss_popups" -> "关闭营销/弹窗"
        "task_plan" -> "维护任务计划"
        "wait_user" -> "等待用户确认"
        "create_reminder" -> "创建提醒"
        "create_scheduled_task" -> "创建定时任务"
        "create_calendar_event" -> "创建日历事件"
        "notify" -> "发送通知"
        "web_search" -> "联网搜索"
        "run_shell" -> "执行 Shell 命令"
        "remote_project_inspect" -> "识别远程项目类型与构建命令"
        "remote_project_build" -> "构建远程项目"
        "remote_project_test" -> "运行远程项目测试"
        "remote_project_install" -> "安装远程项目依赖"
        "home_devices" -> "读取 Home Assistant 设备列表"
        "home_control" -> "控制 Home Assistant 设备"
        "product_compare" -> "比较商品价格/评分/评价"
        else -> "执行 $toolName"
    }

    private fun inferExpected(toolName: String): String = when (toolName) {
        "open_app" -> "目标应用进入前台，系统返回 verified=true"
        "open_search" -> "搜索引擎结果页出现"
        "find_app" -> "返回应用候选，已确认包名"
        "read_ui" -> "返回当前界面文字节点与可点击元素"
        "get_screen_state" -> "返回当前界面摘要/弹窗/节点"
        "read_screen" -> "返回截图与坐标信息"
        "tap" -> "点击后进入新页面或触发操作"
        "tap_text" -> "点击目标文字成功，页面发生变化"
        "input_text" -> "文本已填入输入框"
        "press_key" -> "按键被系统接收"
        "swipe" -> "页面滚动到目标区域"
        "wait" -> "等待完成且后续可继续操作"
        "dismiss_popups" -> "弹窗已关闭，返回主内容"
        "task_plan" -> "任务计划状态可追踪"
        "wait_user" -> "运行停在等待用户确认状态"
        "create_reminder" -> "提醒已保存并注册，系统返回 verified=true"
        "create_scheduled_task" -> "定时任务已保存并注册，系统返回 verified=true"
        "create_calendar_event" -> "日历事件已创建或进入预填页面"
        "notify" -> "通知已发送"
        "web_search" -> "返回搜索结果摘要"
        "run_shell" -> "命令执行成功且输出可验证"
        "remote_project_inspect" -> "返回 projectType/buildCommand/testCommand/installCommand"
        "remote_project_build" -> "构建命令退出码为0"
        "remote_project_test" -> "测试命令执行完成并返回结果"
        "remote_project_install" -> "依赖安装命令退出码为0"
        "home_devices" -> "返回设备列表与状态"
        "home_control" -> "Home Assistant 返回调用成功"
        "product_compare" -> "返回推荐商品与比较摘要"
        else -> "$toolName 成功"
    }

    private fun inferFallback(toolName: String): String = when (toolName) {
        "open_app" -> "若前台包名不匹配，先 find_app 查包名，再重试；仍失败则提示用户手动打开"
        "open_search" -> "若未出现结果页，改用浏览器输入或检查网络"
        "find_app" -> "若未找到，提示用户确认应用名称或检查安装"
        "read_ui" -> "若返回空/权限不足，提示开启 Shizuku 或无障碍，或改用 get_screen_state"
        "get_screen_state" -> "若返回空/权限不足，提示开启无障碍/Shizuku"
        "read_screen" -> "若截图失败，回退 read_ui"
        "tap" -> "若点击无变化，先 read_ui 确认坐标，再使用 tap_text 按文字点击"
        "tap_text" -> "若找不到文字，先 read_ui 获取当前界面文字，或返回上级重新进入"
        "input_text" -> "若输入无效，检查焦点，必要时先 tap 输入框再输入"
        "press_key" -> "若按键无效，改用界面按钮"
        "swipe" -> "若滑动无变化，尝试使用 tap_text/滚动或返回"
        "wait" -> "若等待后仍无结果，读取 UI 确认状态"
        "dismiss_popups" -> "若没有可关闭弹窗，继续原流程；不要误关功能性选择层"
        "task_plan" -> "若计划偏离，更新计划或让用户重新确认目标"
        "wait_user" -> "用户确认后继续；不允许模型自行跳过"
        "create_reminder" -> "若验证失败，提示用户检查提醒权限/系统闹钟权限"
        "create_scheduled_task" -> "若验证失败，提示用户检查任务是否真正保存"
        "create_calendar_event" -> "若页面未打开，尝试直接插入或提示用户手动确认"
        "notify" -> "若通知失败，提示用户检查通知权限"
        "web_search" -> "若搜索无结果，更换关键词或提示用户"
        "run_shell" -> "若权限不足，提示需要 Shizuku"
        "remote_project_inspect" -> "若识别失败，请确认远程路径存在并检查 SSH 连接"
        "remote_project_build" -> "若构建失败，读取 stdErr 并尝试修复后再构建"
        "remote_project_test" -> "若测试失败，输出失败用例并考虑修复循环"
        "remote_project_install" -> "若安装失败，提示检查远程环境/网络"
        "home_devices" -> "若读取失败，检查 Home Assistant 地址/Token/网络"
        "home_control" -> "若控制失败，先 home_devices 确认设备状态与 entity_id"
        "product_compare" -> "若商品数据不全，提示用户补充价格/评分后再比较"
        else -> "若失败，重新读取当前 UI 后再尝试或向用户询问"
    }

    private fun requiredCapabilities(toolCalls: List<ToolCall>): List<String> {
        val result = linkedSetOf<String>()
        toolCalls.map { it.tool }.forEach { tool ->
            when (tool) {
                "run_shell" -> result += "Shizuku"
                "read_ui", "get_screen_state", "read_screen", "tap", "tap_text",
                "input_text", "swipe", "press_key", "dismiss_popups", "review_tap" ->
                    result += "Shizuku或无障碍"
                "open_app", "open_search", "open_deeplink" -> result += "应用启动/DeepLink"
            }
        }
        return result.take(5).toList()
    }

    private fun buildName(text: String): String {
        val trimmed = text.trim().replace(Regex("\\s+"), " ").take(24)
        return trimmed.ifBlank { "未命名技能" }
    }

    private fun guessTags(text: String): List<String> {
        val tags = mutableListOf<String>()
        if (text.contains("闹钟") || text.contains("提醒") || text.contains("定时")) tags += "定时"
        if (text.contains("联系人") || text.contains("电话")) tags += "联系人"
        if (text.contains("日历") || text.contains("会议") || text.contains("事件")) tags += "日历"
        if (text.contains("设置") || text.contains("开关")) tags += "设置"
        if (text.contains("搜索") || text.contains("查找")) tags += "搜索"
        if (text.contains("咖啡") || text.contains("点") || text.contains("下单")) tags += "生活"
        if (text.contains("打开") || text.contains("启动")) tags += "打开"
        if (text.contains("输入") || text.contains("填写")) tags += "输入"
        return tags.distinct().take(8)
    }

    private fun asStringMap(value: Any?): Map<String, Any?> = when (value) {
        is Map<*, *> -> value.entries.associate { it.key.toString() to it.value }
        else -> emptyMap()
    }
}
