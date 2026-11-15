package com.voiceconfig.app.agent

import org.json.JSONArray
import org.json.JSONObject

/** 工具调用的生命周期状态。 */
enum class ToolCallStatus {
    COMPLETED,
    ACCEPTED,
    FAILED,
    CONFIRMATION_REQUIRED,
    CANCELLED,
}

data class ToolError(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
)

data class ToolProgress(
    val percent: Int? = null,
    val message: String? = null,
)

data class ToolConfirmation(
    val approvalId: String,
    val actionDigest: String,
    val humanSummary: String,
    val expiresAt: String? = null,
    val allowedMethods: List<String> = emptyList(),
)

/**
 * 统一工具结果信封。
 *
 * 兼容原有成功/失败构造器；新增状态用于异步任务和外部审批。
 */
data class ToolResult(
    val ok: Boolean,
    val message: String,
    val data: Map<String, Any?> = emptyMap(),
    val status: ToolCallStatus = if (ok) ToolCallStatus.COMPLETED else ToolCallStatus.FAILED,
    val taskId: String? = null,
    val progress: ToolProgress? = null,
    val error: ToolError? = null,
    val confirmation: ToolConfirmation? = null,
    val durationMs: Long? = null,
) {
    val isTerminalFailure: Boolean
        get() = status == ToolCallStatus.FAILED || status == ToolCallStatus.CANCELLED

    val requiresExternalAction: Boolean
        get() = status == ToolCallStatus.ACCEPTED || status == ToolCallStatus.CONFIRMATION_REQUIRED

    /** 回填给模型的内容：保留原 message，同时携带结构化结果。 */
    fun toModelContent(maxChars: Int = 8_000): String {
        if (status == ToolCallStatus.COMPLETED && taskId == null &&
            progress == null && error == null && confirmation == null && data.isEmpty()
        ) {
            return message
        }
        val json = JSONObject().apply {
            put("ok", ok)
            put("status", status.name.lowercase())
            put("summary", message)
            if (data.isNotEmpty()) put("result", toJsonValue(data))
            taskId?.let { put("task_id", it) }
            durationMs?.let { put("duration_ms", it) }
            progress?.let {
                put("progress", JSONObject().apply {
                    it.percent?.let { p -> put("percent", p) }
                    it.message?.let { m -> put("message", m) }
                })
            }
            error?.let {
                put("error", JSONObject().apply {
                    put("code", it.code)
                    put("message", it.message)
                    put("retryable", it.retryable)
                })
            }
            confirmation?.let {
                put("confirmation", JSONObject().apply {
                    put("required", true)
                    put("approval_id", it.approvalId)
                    put("action_digest", it.actionDigest)
                    put("human_summary", it.humanSummary)
                    it.expiresAt?.let { v -> put("expires_at", v) }
                    if (it.allowedMethods.isNotEmpty()) put("allowed_methods", JSONArray(it.allowedMethods))
                })
            }
        }
        val text = json.toString()
        return if (text.length <= maxChars) text else text.take(maxChars) + "…"
    }

    companion object {
        fun success(message: String, data: Map<String, Any?> = emptyMap()) =
            ToolResult(true, message, data)

        fun failure(message: String, data: Map<String, Any?> = emptyMap()) =
            ToolResult(false, message, data, status = ToolCallStatus.FAILED)

        fun accepted(message: String, taskId: String, data: Map<String, Any?> = emptyMap()) =
            ToolResult(
                ok = true,
                message = message,
                data = data,
                status = ToolCallStatus.ACCEPTED,
                taskId = taskId,
            )

        fun confirmationRequired(
            message: String,
            confirmation: ToolConfirmation,
            data: Map<String, Any?> = emptyMap(),
        ) = ToolResult(
            ok = false,
            message = message,
            data = data,
            status = ToolCallStatus.CONFIRMATION_REQUIRED,
            confirmation = confirmation,
        )

        fun failed(
            message: String,
            code: String,
            retryable: Boolean = false,
            data: Map<String, Any?> = emptyMap(),
        ) = ToolResult(
            ok = false,
            message = message,
            data = data,
            status = ToolCallStatus.FAILED,
            error = ToolError(code, message, retryable),
        )
    }
}

private fun toJsonValue(value: Any?): Any? = when (value) {
    null -> JSONObject.NULL
    is Map<*, *> -> JSONObject().apply {
        value.forEach { (k, v) -> put(k.toString(), toJsonValue(v)) }
    }
    is Iterable<*> -> JSONArray().apply { value.forEach { put(toJsonValue(it)) } }
    is Array<*> -> JSONArray().apply { value.forEach { put(toJsonValue(it)) } }
    is String, is Number, is Boolean -> value
    else -> value.toString()
}
