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
        "get_screen_state" -> objectSchema(
            "maxNodes" to integer("最多返回 UI 节点数"),
            "includeImage" to JSONObject().apply {
                put("type", "boolean")
                put("description", "是否包含截图；默认 false，只有需要看图标/视觉布局时才 true")
            },
        )
        "dismiss_popups" -> objectSchema(
            "maxAttempts" to integer("最多尝试关闭次数，默认3"),
        )
        "task_plan" -> objectSchema(
            "action" to string("create/update/wait_user/get"),
            "goal" to string("目标描述，create 时必填"),
            "steps" to JSONObject().apply {
                put("type", "array")
                put("items", JSONObject().apply { put("type", "string") })
                put("description", "步骤标题列表，create 时可选")
            },
            "stepId" to string("步骤 id，update 时必填"),
            "status" to string("步骤状态：PENDING/IN_PROGRESS/COMPLETED/FAILED/BLOCKED/SKIPPED"),
            "evidence" to string("完成该步骤的 UI/事实证据"),
            "note" to string("备注"),
            "reason" to string("等待用户的原因"),
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
        "create_reminder" -> objectSchema(
            "content" to string("提醒内容，例如：喝水"),
            "time" to string("提醒时间 HH:mm，例如 08:00"),
            "timeText" to string("原始中文时间表达，例如：明早8点"),
            "date" to string("日期：today/tomorrow/yyyy-MM-dd"),
            "scheduleType" to string("ONCE/DAILY/WEEKLY/INTERVAL"),
            "daysOfWeek" to JSONObject().apply {
                put("type", "array")
                put("items", JSONObject().apply { put("type", "string") })
                put("description", "周几，例如 MONDAY")
            },
            "intervalMinutes" to integer("间隔分钟数"),
        )
        "wait_user" -> objectSchema(
            "reason" to string("等待用户确认的原因"),
        )
        "create_scheduled_task" -> objectSchema(
            "action" to string("open_app/open_deeplink/open_search/remind"),
            "package" to string("App 包名"),
            "deepLink" to string("Deep Link/URL"),
            "query" to string("搜索关键词"),
            "content" to string("提醒内容"),
            "title" to string("任务标题"),
            "time" to string("时间 HH:mm"),
            "timeText" to string("中文时间表达，例如：每天8点"),
            "date" to string("日期：today/tomorrow/yyyy-MM-dd"),
            "scheduleType" to string("ONCE/DAILY/WEEKLY/INTERVAL"),
            "daysOfWeek" to JSONObject().apply {
                put("type", "array")
                put("items", JSONObject().apply { put("type", "string") })
                put("description", "周几，例如 MONDAY")
            },
            "intervalMinutes" to integer("间隔分钟数"),
            "engine" to string("搜索引擎：baidu/google/bing，默认 baidu"),
        )
        "web_search" -> objectSchema(
            "query" to string("搜索关键词"),
            "maxUses" to integer("最大搜索次数"),
        )
        "open_search" -> objectSchema(
            "engine" to string("搜索引擎：baidu/google/bing，默认 baidu"),
            "query" to string("搜索关键词"),
            "browser" to string("浏览器：chrome，默认 chrome"),
        )
        "create_calendar_event" -> objectSchema(
            "title" to string("事件标题"),
            "date" to string("日期：today/tomorrow/YYYY-MM-DD"),
            "startHour" to integer("开始小时 0-23，默认9"),
            "startMinute" to integer("开始分钟 0-59，默认0"),
            "durationMinutes" to integer("时长分钟，默认60"),
            "startTimeMs" to integer("可选：毫秒时间戳，优先于 date"),
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
