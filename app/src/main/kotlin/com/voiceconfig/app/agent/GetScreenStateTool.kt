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
    override val description: String = "获取当前屏幕文字+UI坐标+弹窗提示，默认不带截图（快）；需要看图标/视觉布局时传 includeImage:true，参数：{\"maxNodes\":120,\"includeImage\":false}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val includeImage = (args["includeImage"] as? Boolean)
            ?: (args["includeScreen"] as? Boolean)
            ?: false
        val totalStartMs = System.currentTimeMillis()
        val uiResult = readUiTool.execute(args)
        val uiTiming = uiResult.data["timingMs"] as? Map<*, *>

        if (!uiResult.ok) {
            return ToolResult.failure(
                "无法读取 UI：${uiResult.message}",
            )
        }

        val messages = mutableListOf<String>()
        val data = mutableMapOf<String, Any?>()
        messages += uiResult.message
        data["ui"] = uiResult.data["ui"]
        data["nodeCount"] = uiResult.data["nodeCount"]
        data["bounds"] = uiResult.data["bounds"]
        uiResult.data["foregroundPackage"]?.let { data["foregroundPackage"] = it }
        uiResult.data["overlay"]?.let { data["overlay"] = it }
        val uiNodes: List<*> = uiResult.data["nodes"] as? List<*> ?: emptyList<Any?>()
        val annotations = mutableListOf<Map<String, Any?>>()
        uiNodes.filterIsInstance<Map<*, *>>().forEachIndexed { index, node ->
            val bounds = node["bounds"] ?: return@forEachIndexed
            annotations += mapOf(
                "id" to (index + 1),
                "text" to (node["text"] as? String ?: ""),
                "contentDesc" to (node["contentDesc"] as? String ?: ""),
                "resourceId" to (node["resourceId"] as? String ?: ""),
                "bounds" to bounds,
                "center" to node["center"],
            )
        }

        if (!includeImage) {
            val timingMs = linkedMapOf<String, Any>()
            if (uiTiming != null) timingMs["read_ui"] = uiTiming
            timingMs["total_ms"] = System.currentTimeMillis() - totalStartMs
            data["timingMs"] = timingMs
            return ToolResult.success(
                messages.joinToString("；") + "（默认无截图，如需视觉请用 read_screen 或 includeImage:true）",
                data,
            )
        }

        val screenArgs = if (annotations.isNotEmpty()) {
            args + mapOf("annotate" to true, "annotations" to annotations.take(60))
        } else {
            args
        }
        val screenResult = readScreenTool.execute(screenArgs)
        val screenTiming = screenResult.data["timingMs"] as? Map<*, *>
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

        if (annotations.isNotEmpty()) {
            data["annotations"] = annotations.take(60)
            val mappingText = annotations.take(60).joinToString("；") { ann ->
                val id = ann["id"]?.toString() ?: "?"
                val label = (ann["text"] as? String ?: "").ifBlank { ann["contentDesc"] as? String ?: "" }.ifBlank { ann["resourceId"] as? String ?: "" }.take(24)
                val center = ann["center"] as? List<*> ?: emptyList<Any?>()
                val centerText = if (center.size >= 2) "${center[0]},${center[1]}" else "?"
                "#$id $label center=($centerText)"
            }
            messages += "屏幕标注编号映射：$mappingText"
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
