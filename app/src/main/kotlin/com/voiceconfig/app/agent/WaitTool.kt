package com.voiceconfig.app.agent

import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 等待工具：让动作序列在两步之间暂停，或等待页面加载。
 * 参数：ms（必填，毫秒）
 */
@Singleton
class WaitTool @Inject constructor() : AgentTool {
    override val name: String = "wait"
    override val description: String = "等待指定毫秒数，参数：{\"ms\": 1500}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val ms = (args["ms"] as? Number)?.toLong() ?: return ToolResult.failure("缺少参数 ms")
        if (ms < 0 || ms > 30_000) return ToolResult.failure("ms 超出允许范围 0-30000")
        delay(ms)
        return ToolResult.success("已等待 ${ms}ms", mapOf("waitedMs" to ms))
    }
}
