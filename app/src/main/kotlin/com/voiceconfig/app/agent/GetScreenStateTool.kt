package com.voiceconfig.app.agent

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * 结构化屏幕感知工具：
 * 一次调用同时返回 UI 树摘要、可点击元素、带坐标网格的截图和屏幕分辨率。
 *
 * 这是 Phase 0 的“屏幕感知包”，让模型优先基于 UI 元素和文字定位，
 * 而不是仅凭截图猜像素坐标。
 */
@Singleton
class GetScreenStateTool @Inject constructor(
    private val readUiTool: ReadUiTool,
    private val readScreenTool: ReadScreenTool,
) : AgentTool {

    override val name: String = "get_screen_state"
    override val description: String = "获取当前屏幕完整状态：UI元素+文字+坐标+截图，参数：{\"maxNodes\":120,\"gridStep\":200}（可选）"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val totalStartMs = System.currentTimeMillis()
        // UI 树与截图相互独立，短时间并行获取，降低屏幕感知的端到端耗时。
        val (uiResult, screenResult) = coroutineScope {
            val uiDeferred = async { readUiTool.execute(args) }
            val screenDeferred = async { readScreenTool.execute(args) }
            uiDeferred.await() to screenDeferred.await()
        }
        val uiTiming = uiResult.data["timingMs"] as? Map<*, *>
        val screenTiming = screenResult.data["timingMs"] as? Map<*, *>

        if (!uiResult.ok && !screenResult.ok) {
            return ToolResult.failure(
                "无法获取屏幕状态：${uiResult.message}；${screenResult.message}",
            )
        }

        val messages = mutableListOf<String>()
        val data = mutableMapOf<String, Any?>()

        if (uiResult.ok) {
            messages += uiResult.message
            data["ui"] = uiResult.data["ui"]
            data["nodeCount"] = uiResult.data["nodeCount"]
            data["bounds"] = uiResult.data["bounds"]
        } else {
            messages += "UI:${uiResult.message}"
        }

        if (screenResult.ok) {
            messages += screenResult.message
            data["image_base64"] = screenResult.data["image_base64"]
            data["width"] = screenResult.data["width"]
            data["height"] = screenResult.data["height"]
            data["has_grid"] = screenResult.data["has_grid"]
            data["path"] = screenResult.data["path"]
        } else {
            messages += "截图:${screenResult.message}"
        }

        val timingMs = linkedMapOf<String, Any>()
        if (uiTiming != null) timingMs["read_ui"] = uiTiming
        if (screenTiming != null) timingMs["read_screen"] = screenTiming
        timingMs["total_ms"] = System.currentTimeMillis() - totalStartMs
        data["timingMs"] = timingMs

        return ToolResult.success(
            messages.joinToString("；"),
            data,
        )
    }
}
