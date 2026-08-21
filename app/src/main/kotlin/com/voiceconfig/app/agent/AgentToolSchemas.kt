package com.voiceconfig.app.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 根据工具注册表生成 DeepSeek 原生 function calling 所需的 tools JSON。
 */
object AgentToolSchemas {

    fun build(tools: List<AgentTool>): JSONArray {
        val array = JSONArray()
        tools.forEach { tool ->
            array.put(
                JSONObject().apply {
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", parametersFor(tool.name))
                    })
                },
            )
        }
        return array
    }

    private fun parametersFor(name: String): JSONObject = when (name) {
        "open_app" -> objectSchema(
            "package" to string("目标 App 包名"),
            "deepLink" to string("Deep Link URL"),
            "activity" to string("Activity 全名"),
        )
        "find_app" -> objectSchema(
            "keyword" to string("应用名称或包名关键词"),
        )
        "run_shell" -> objectSchema(
            "command" to string("要执行的 shell 命令"),
        )
        "read_ui" -> objectSchema(
            "maxNodes" to integer("最多返回节点数"),
        )
        "read_screen" -> objectSchema(
            "gridStep" to integer("网格间隔像素，可选 50/100/200/400，默认200；越细越容易定位但图片更密"),
        )
        "tap" -> objectSchema(
            "x" to integer("屏幕绝对 X 坐标"),
            "y" to integer("屏幕绝对 Y 坐标"),
        )
        "tap_text" -> objectSchema(
            "text" to string("要点击的界面文字，例如：发送、同意、菜单"),
            "texts" to JSONObject().apply {
                put("type", "array")
                put("items", JSONObject().apply { put("type", "string") })
                put("description", "多个候选文字，命中任意一个即可点击")
            },
        )
        "review_tap" -> objectSchema(
            "x" to integer("计划点击的 X 坐标"),
            "y" to integer("计划点击的 Y 坐标"),
        )
        "input_text" -> objectSchema(
            "text" to string("要输入的文本"),
        )
        "swipe" -> objectSchema(
            "x1" to integer("起点 X"),
            "y1" to integer("起点 Y"),
            "x2" to integer("终点 X"),
            "y2" to integer("终点 Y"),
            "durationMs" to integer("时长毫秒"),
        )
        "press_key" -> objectSchema(
            "key" to string("按键名：back/home/enter/menu/app_switch/search"),
            "keycode" to integer("直接指定 Android keycode"),
        )
        "wait" -> objectSchema(
            "ms" to integer("等待毫秒数"),
        )
        "notify" -> objectSchema(
            "title" to string("通知标题"),
            "content" to string("通知内容"),
        )
        "web_search" -> objectSchema(
            "query" to string("搜索关键词"),
            "maxUses" to integer("最大搜索次数"),
        )
        "file_write" -> objectSchema(
            "filename" to string("文件名"),
            "content" to string("文件内容"),
        )
        "file_read" -> objectSchema(
            "filename" to string("文件名"),
            "path" to string("绝对路径"),
        )
        "clipboard_read" -> JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject())
        }
        "logcat_read" -> objectSchema(
            "lines" to integer("读取行数"),
        )
        "open_file" -> objectSchema(
            "filename" to string("文件名"),
            "path" to string("绝对路径"),
        )
        else -> JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject())
            put("additionalProperties", true)
        }
    }

    private fun objectSchema(vararg fields: Pair<String, JSONObject>): JSONObject =
        JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                fields.forEach { (name, schema) -> put(name, schema) }
            })
        }

    private fun string(description: String): JSONObject = JSONObject().apply {
        put("type", "string")
        put("description", description)
    }

    private fun integer(description: String): JSONObject = JSONObject().apply {
        put("type", "integer")
        put("description", description)
    }
}
