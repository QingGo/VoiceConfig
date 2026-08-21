package com.voiceconfig.app.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 LLM 返回的 JSON 动作数组解析为 [ToolCall] 列表。
 *
 * 期望格式：
 * [{"tool":"open_app","args":{"package":"com.tencent.wework"}}, ...]
 */
object JsonToolCallParser {

    fun parse(text: String): List<ToolCall> {
        val cleaned = extractJsonArray(text)
        val array = JSONArray(cleaned)
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val tool = obj.optString("tool").ifBlank { return@mapNotNull null }
            val args = obj.optJSONObject("args")?.let { jsonToMap(it) } ?: emptyMap()
            ToolCall(tool = tool, args = args)
        }
    }

    private fun extractJsonArray(text: String): String {
        val trimmed = text.trim()
        val start = trimmed.indexOf('[')
        val end = trimmed.lastIndexOf(']')
        return if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
    }

    private fun jsonToMap(json: JSONObject): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.get(key)
            map[key] = when (value) {
                is JSONObject -> jsonToMap(value)
                is JSONArray -> (0 until value.length()).map { value.get(it) }
                else -> value
            }
        }
        return map
    }

    fun parseArguments(json: String): Map<String, Any?> {
        val cleaned = json.trim()
        if (cleaned.isBlank() || cleaned == "null") return emptyMap()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        val obj = if (start >= 0 && end > start) JSONObject(cleaned.substring(start, end + 1)) else JSONObject(cleaned)
        return jsonToMap(obj)
    }
}
