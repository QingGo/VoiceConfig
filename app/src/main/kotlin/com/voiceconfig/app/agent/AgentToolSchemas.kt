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
        "ui_assert" -> objectSchema(
            "action" to string("visible/not_visible/wait_for，默认 visible"),
            "resourceId" to string("目标资源 id，例如 com.lucky.luckyclient:id/close_iv"),
            "text" to string("目标文字"),
            "desc" to string("目标 content-desc"),
            "timeoutMs" to integer("wait_for 的等待超时毫秒，默认 5000"),
        )
        "ui_wait" -> objectSchema(
            "resourceId" to string("目标资源 id，例如 com.lucky.luckyclient:id/close_iv"),
            "text" to string("目标文字"),
            "desc" to string("目标 content-desc"),
            "timeoutMs" to integer("等待超时毫秒，默认 5000"),
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
            "x" to integer("可选，输入框屏幕绝对 X 坐标；输入框未聚焦时先点击该坐标"),
            "y" to integer("可选，输入框屏幕绝对 Y 坐标；输入框未聚焦时先点击该坐标"),
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
        "remote_ssh_exec" -> objectSchema(
            "host" to string("节点名或 IP，可省略；省略时使用第一个可用远程节点"),
            "command" to string("要执行的 shell 命令"),
        )
        "remote_ssh_read" -> objectSchema(
            "host" to string("节点名或 IP，可省略"),
            "path" to string("远程文件绝对路径"),
        )
        "remote_ssh_write" -> objectSchema(
            "host" to string("节点名或 IP，可省略"),
            "path" to string("远程文件绝对路径"),
            "content" to string("要写入的完整文本内容"),
        )
        "remote_ssh_list" -> objectSchema(
            "host" to string("节点名或 IP，可省略"),
            "path" to string("远程目录绝对路径，默认 /"),
        )
        "remote_ssh_search" -> objectSchema(
            "host" to string("节点名或 IP，可省略"),
            "pattern" to string("搜索关键词"),
            "path" to string("远程目录绝对路径，默认 /home"),
        )
        "remote_node" -> objectSchema(
            "action" to string("list/health/exec"),
            "node" to string("节点名称或 nodeId；留空表示第一个可用节点"),
            "command" to string("要执行的只读命令名，例如 uptime/hostname"),
        )
        "home_devices" -> objectSchema(
            "filter" to string("可选，按设备名/域名/entity_id 过滤"),
        )
        "home_control" -> objectSchema(
            "domain" to string("设备域：climate/light/cover/media_player/switch"),
            "service" to string("服务：set_temperature/turn_on/turn_off/open_cover/close_cover/play_media"),
            "entityId" to string("设备 entity_id，如 climate.living_room"),
            "data" to JSONObject().apply {
                put("type", "object")
                put("description", "服务附加参数，如 temperature/volume_level")
            },
        )
        "product_compare" -> objectSchema(
            "products" to string("商品 JSON 数组字符串，每项含 title/platform/price/rating/reviewCount 等"),
        )
        "product_search" -> objectSchema(
            "query" to string("商品搜索关键词，例如：婴儿奶粉 京东 价格"),
        )
        "product_extract" -> objectSchema(
            "text" to string("原始搜索/比价文本"),
        )
        "shopping_save" -> objectSchema(
            "products" to string("商品 JSON 数组字符串"),
            "status" to string("WATCH/RECOMMENDED/BOUGHT"),
            "note" to string("可选备注"),
        )
        "shopping_list" -> objectSchema(
            "status" to string("可选 WATCH/RECOMMENDED/BOUGHT"),
        )
        "shopping_update_status" -> objectSchema(
            "productId" to string("商品 ID"),
            "status" to string("WATCH/RECOMMENDED/BOUGHT"),
        )
        "luckin_open" -> objectSchema()
        "luckin_quick_order" -> objectSchema(
            "store" to string("可选，门店名称；默认使用常用门店"),
            "drink" to string("可选，饮品名称；默认标准美式"),
            "temperature" to string("可选，冷热；默认冰"),
        )
        "luckin_prepare_order" -> objectSchema(
            "store" to string("门店名称或位置"),
            "drink" to string("饮品名称，如：冰美式/生椰拿铁"),
            "size" to string("杯型，如：大杯/中杯"),
            "sugar" to string("甜度"),
            "ice" to string("冰量"),
            "quantity" to integer("数量，默认1"),
            "price" to integer("可选预估单价"),
        )
        "wechat_draft_reply" -> objectSchema(
            "receiver" to string("接收人/会话对象"),
            "context" to string("上下文或原消息摘要"),
            "reply" to string("回复草稿内容"),
        )
        "wechat_open" -> objectSchema()
        "wechat_read_messages" -> objectSchema()
        "wechat_send_reply" -> objectSchema(
            "draft" to string("要发送的回复内容"),
            "humanConfirmed" to string("必须 true，表示已经过用户确认"),
        )
        "wework_open" -> objectSchema()
        "wecom_send_message" -> objectSchema(
            "toUser" to string("企业微信成员 UserID，多个用 | 分隔，可选"),
            "toParty" to string("部门 ID，多个用 | 分隔，可选"),
            "toTag" to string("标签 ID，多个用 | 分隔，可选"),
            "content" to string("要发送的文本消息内容"),
        )
        "remote_project_inspect" -> objectSchema(
            "host" to string("节点名或 IP，可省略"),
            "path" to string("远程项目根目录绝对路径"),
        )
        "remote_project_build" -> objectSchema(
            "host" to string("节点名或 IP，可省略"),
            "path" to string("远程项目根目录绝对路径"),
            "projectId" to string("已保存项目 ID，可省略"),
            "command" to string("可选的构建命令，省略时自动识别"),
        )
        "remote_project_test" -> objectSchema(
            "host" to string("节点名或 IP，可省略"),
            "path" to string("远程项目根目录绝对路径"),
            "projectId" to string("已保存项目 ID，可省略"),
            "command" to string("可选的测试命令，省略时自动识别"),
        )
        "remote_project_install" -> objectSchema(
            "host" to string("节点名或 IP，可省略"),
            "path" to string("远程项目根目录绝对路径"),
            "projectId" to string("已保存项目 ID，可省略"),
            "command" to string("可选的安装命令，省略时自动识别"),
        )
        "remote_project_verify" -> objectSchema(
            "host" to string("节点名或 IP，可省略"),
            "path" to string("远程项目根目录绝对路径"),
            "projectId" to string("已保存项目 ID，可省略"),
            "buildCommand" to string("可选构建命令"),
            "testCommand" to string("可选测试命令"),
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
