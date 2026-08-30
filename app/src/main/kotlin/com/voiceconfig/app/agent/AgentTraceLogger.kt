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
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface AgentTrace {
    fun startRun(userText: String): String
    fun log(runId: String, type: String, data: Map<String, Any?> = emptyMap())
    fun log(type: String, data: Map<String, Any?> = emptyMap()) = log("local", type, data)
    fun saveScreenshot(runId: String, base64: String, label: String): String
    fun saveScreenshot(base64: String, label: String): String = saveScreenshot("local", base64, label)
    fun readRun(runId: String): List<Map<String, Any?>> = emptyList()
    fun report(runId: String): AgentTraceReport? = null
}

/**
 * Agent 运行轨迹记录器。
 *
 * 每次 Agent 执行使用独立 runId，把：
 * - 用户输入
 * - LLM 请求/响应（正文、思考、工具调用）
 * - 工具调用参数
 * - 工具执行结果
 * - 多模态模型看到的截图（保存为 PNG）
 * - 错误与最终结果
 *
 * 写成结构化 JSON 行，放进应用私有目录：files/agent_trace/agent_trace.log
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

    override fun startRun(userText: String): String {
        val runId = "run_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        log(runId, "run_start", mapOf("userText" to userText))
        return runId
    }

    override fun log(runId: String, type: String, data: Map<String, Any?>) {
        runCatching {
            synchronized(lock) {
                logDir.mkdirs()
                val entry = JSONObject()
                entry.put("time", timeFormat.format(Date()))
                entry.put("runId", runId)
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

    override fun saveScreenshot(runId: String, base64: String, label: String): String {
        return runCatching {
            synchronized(lock) {
                val runDir = File(screenshotDir, runId.sanitize())
                runDir.mkdirs()
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val name = "screenshot_${System.currentTimeMillis()}_${label.sanitize()}.png"
                val file = File(runDir, name)
                file.writeBytes(bytes)
                file.absolutePath
            }
        }.getOrElse { e ->
            Log.w(TAG, "Failed to save screenshot", e)
            ""
        }
    }

    private fun String.sanitize(): String =
        replace(Regex("[^a-zA-Z0-9_-]"), "_").take(60)

    override fun readRun(runId: String): List<Map<String, Any?>> = synchronized(lock) {
        if (!logFile.exists()) return emptyList()
        val result = mutableListOf<Map<String, Any?>>()
        runCatching {
            logFile.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isBlank()) return@forEachLine
                val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return@forEachLine
                if (obj.optString("runId") == runId) {
                    result += obj.toMap()
                }
            }
        }
        result
    }

    override fun report(runId: String): AgentTraceReport? {
        val events = readRun(runId)
        if (events.isEmpty()) return null
        return AgentTraceReportBuilder.build(events)
    }

    private fun JSONObject.toMap(): Map<String, Any?> = buildMap {
        keys().forEach { key ->
            put(key, opt(key))
        }
    }

    companion object {
        private const val TAG = "AgentTrace"
    }
}
