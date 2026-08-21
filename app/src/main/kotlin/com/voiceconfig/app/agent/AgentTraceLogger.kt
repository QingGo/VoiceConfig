package com.voiceconfig.app.agent

import android.content.Context
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

interface AgentTrace {
    fun log(type: String, data: Map<String, Any?> = emptyMap())
    fun saveScreenshot(base64: String, label: String): String
}

/**
 * Agent 运行轨迹记录器。
 *
 * 会把每次 Agent 执行的：
 * - 用户输入
 * - LLM 请求/响应（正文、思考、工具调用）
 * - 工具调用参数
 * - 工具执行结果
 * - 多模态模型看到的截图（保存为 PNG）
 * - 错误与最终结果
 *
 * 写入应用私有目录：files/agent_trace/agent_trace.log
 * 截图保存在：files/agent_trace/screenshots/
 */
@Singleton
class AgentTraceLogger @Inject constructor(
    @ApplicationContext private val context: Context,
) : AgentTrace {
    private val lock = Any()
    private val logDir: File = File(context.filesDir, "agent_trace")
    private val logFile: File = File(logDir, "agent_trace.log")
    private val screenshotDir: File = File(logDir, "screenshots")

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    override fun log(type: String, data: Map<String, Any?>) {
        runCatching {
            synchronized(lock) {
                logDir.mkdirs()
                val entry = JSONObject()
                entry.put("time", timeFormat.format(Date()))
                entry.put("type", type)
                data.forEach { (key, value) ->
                    when (value) {
                        null -> entry.put(key, JSONObject.NULL)
                        is Map<*, *> -> entry.put(key, JSONObject(value as Map<*, *>))
                        is List<*> -> entry.put(key, JSONObject().put("items", value))
                        else -> entry.put(key, value)
                    }
                }
                logFile.appendText(entry.toString() + "\n")
            }
        }.onFailure { e ->
            Log.w(TAG, "Failed to write agent trace", e)
        }
    }

    override fun saveScreenshot(base64: String, label: String): String {
        return runCatching {
            synchronized(lock) {
                screenshotDir.mkdirs()
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val name = "screenshot_${System.currentTimeMillis()}_${label.sanitize()}.png"
                val file = File(screenshotDir, name)
                file.writeBytes(bytes)
                file.absolutePath
            }
        }.getOrElse { e ->
            Log.w(TAG, "Failed to save screenshot", e)
            ""
        }
    }

    private fun String.sanitize(): String =
        replace(Regex("[^a-zA-Z0-9_-]"), "_").take(40)

    companion object {
        private const val TAG = "AgentTrace"
    }
}
