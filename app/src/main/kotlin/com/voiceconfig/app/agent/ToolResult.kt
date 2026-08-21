package com.voiceconfig.app.agent

/**
 * 工具执行结果。
 *
 * @param ok 是否成功
 * @param message 给 LLM/用户看的简短结果
 * @param data 结构化数据（如 UI 摘要、命令输出），会回填给 LLM 做下一步决策
 */
data class ToolResult(
    val ok: Boolean,
    val message: String,
    val data: Map<String, Any?> = emptyMap(),
) {
    companion object {
        fun success(message: String, data: Map<String, Any?> = emptyMap()) = ToolResult(true, message, data)
        fun failure(message: String, data: Map<String, Any?> = emptyMap()) = ToolResult(false, message, data)
    }
}
