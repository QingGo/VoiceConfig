package com.voiceconfig.app.ai

import com.voiceconfig.core.model.ActionType
import com.voiceconfig.core.model.ExecutionLog
import com.voiceconfig.core.model.ExecutionMode
import com.voiceconfig.core.model.ScheduleSpec
import com.voiceconfig.core.model.TaskDraft
import com.voiceconfig.core.nlp.NaturalLanguageParser
import com.voiceconfig.core.nlp.RuleBasedNlpParser
import com.voiceconfig.data.local.entity.AiDebugLogEntity
import com.voiceconfig.data.local.repository.AiDebugLogRepository
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class DeepSeekNlpParser @Inject constructor(
    private val apiKeyStore: ApiKeyStore,
    private val aiDebugLogRepository: AiDebugLogRepository,
    private val installedAppProvider: InstalledAppProvider,
    private val fallback: RuleBasedNlpParser = RuleBasedNlpParser(),
) : NaturalLanguageParser {

    @Volatile
    var lastUsedRemote: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var lastRawResponse: String? = null
        private set

    @Volatile
    var lastParseError: String? = null
        private set

    override fun parse(input: String): TaskDraft? {
        val apiKey = apiKeyStore.deepSeekApiKey
        if (apiKey.isBlank()) {
            lastUsedRemote = false
            lastError = "未配置 API Key"
            return fallback.parse(input)
        }
        return try {
            val content = callDeepSeek(input, apiKey, apiKeyStore.deepSeekModel, buildTaskPrompt())
            lastRawResponse = content
            if (content.isNullOrBlank()) {
                lastUsedRemote = false
                lastError = "DeepSeek 返回为空"
                lastParseError = "content is blank"
                Log.w(TAG, "DeepSeek returned blank content")
                saveDebugLog(input, content, lastParseError)
                fallback.parse(input)
            } else {
                val draft = parseTaskDraft(content)
                if (draft != null) {
                    lastUsedRemote = true
                    lastError = null
                    lastParseError = null
                    draft
                } else {
                    lastUsedRemote = false
                    lastError = "DeepSeek JSON 解析失败"
                    Log.w(TAG, "DeepSeek JSON parse failed. raw=${content.take(2000)} error=${lastParseError}")
                    saveDebugLog(input, content, lastParseError)
                    fallback.parse(input)
                }
            }
        } catch (e: Exception) {
            lastUsedRemote = false
            lastError = e.message ?: e.javaClass.simpleName
            lastParseError = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "DeepSeek call/parse exception", e)
            saveDebugLog(input, null, lastParseError)
            fallback.parse(input)
        }
    }

    private fun buildTaskPrompt(): String {
        val appLines = installedAppProvider.installedApps
            .asSequence()
            .sortedBy { it.label.lowercase() }
            .take(500)
            .joinToString("\n") { "${it.label} -> ${it.packageName}" }
        return if (appLines.isBlank()) {
            DEFAULT_TASK_PROMPT
        } else {
            DEFAULT_TASK_PROMPT + "\n\n当前手机已安装的应用（请优先使用这里的包名，用户说的应用名可能对应其中某个）：\n" + appLines
        }
    }

    private fun saveDebugLog(input: String, rawResponse: String?, parseError: String?) {
        runCatching {
            runBlocking(Dispatchers.IO) {
                aiDebugLogRepository.add(
                    AiDebugLogEntity(
                        createdAtEpochMillis = System.currentTimeMillis(),
                        input = input,
                        model = apiKeyStore.deepSeekModel,
                        thinkingEnabled = apiKeyStore.deepSeekThinkingEnabled,
                        reasoningEffort = apiKeyStore.deepSeekReasoningEffort,
                        rawResponse = rawResponse,
                        parseError = parseError,
                    ),
                )
                aiDebugLogRepository.trim(200)
            }
        }
    }

    fun summarize(logs: List<ExecutionLog>): String? {
        val apiKey = apiKeyStore.deepSeekApiKey
        if (apiKey.isBlank()) {
            lastError = "未配置 API Key，无法生成总结"
            return null
        }
        val logText = logs.joinToString("\n") { log ->
            val time = log.startedAtEpochMillis?.let {
                java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toString()
            } ?: "未知时间"
            "任务#${log.taskId} $time ${log.status} mode=${log.executionMode} error=${log.errorCode ?: "-"} msg=${log.message ?: "-"}"
        }
        val systemPrompt = """
            你是手机自动化助手的执行总结器。请用简洁中文总结以下执行日志，指出成功/失败趋势和可能原因，不要输出 JSON。
        """.trimIndent()
        return try {
            callDeepSeek(logText, apiKey, apiKeyStore.deepSeekModel, systemPrompt)
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            null
        }
    }

    private fun callDeepSeek(input: String, apiKey: String, model: String, systemPrompt: String = DEFAULT_TASK_PROMPT): String? {
        val url = URL("https://api.deepseek.com/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000

            val thinkingEnabled = apiKeyStore.deepSeekThinkingEnabled
            val reasoningEffort = apiKeyStore.deepSeekReasoningEffort
            val body = JSONObject().apply {
                put("model", model)
                put("thinking", JSONObject().put("type", if (thinkingEnabled) "enabled" else "disabled"))
                if (thinkingEnabled) {
                    put("reasoning_effort", reasoningEffort)
                } else {
                    put("temperature", 0)
                }
                put("response_format", JSONObject().put("type", "json_object"))
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    put(JSONObject().put("role", "user").put("content", input))
                })
            }

            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                return null
            }
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(response)
            return root.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } finally {
            conn.disconnect()
        }
    }

    private fun parseTaskDraft(json: String): TaskDraft? {
        val cleaned = extractJsonObject(json)
        return try {
            val root = JSONObject(cleaned)
            val rawText = root.optString("rawText")
            val scheduleJson = root.optJSONObject("schedule")
            val schedule = scheduleJson?.let { parseSchedule(it) }
            val actionType = runCatching {
                ActionType.valueOf(root.optString("actionType", ActionType.NOTIFY.name))
            }.getOrDefault(ActionType.NOTIFY)
            val executionMode = runCatching {
                ExecutionMode.valueOf(root.optString("executionMode", ExecutionMode.AUTO.name))
            }.getOrDefault(ExecutionMode.AUTO)
            lastParseError = null
            TaskDraft(
                rawText = rawText.ifBlank { "" },
                schedule = schedule,
                actionType = actionType,
                targetPackage = optNullableString(root, "targetPackage"),
                targetActivity = optNullableString(root, "targetActivity"),
                deepLink = optNullableString(root, "deepLink"),
                executionMode = executionMode,
                confidence = root.optDouble("confidence", 0.0),
            )
        } catch (e: Exception) {
            lastParseError = "${e.message ?: e.javaClass.simpleName} | cleaned=${cleaned.take(800)}"
            Log.w(TAG, "DeepSeek JSON parse failed. cleaned=$cleaned", e)
            null
        }
    }

    private fun extractJsonObject(text: String): String {
        val trimmed = text.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
    }

    private fun optNullableString(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        val value = json.optString(key)
        return value.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun parseSchedule(json: JSONObject): ScheduleSpec? {
        val type = runCatching {
            ScheduleSpec.ScheduleType.valueOf(json.optString("type"))
        }.getOrNull() ?: return null
        val time = optNullableString(json, "time")?.let { LocalTime.parse(it) }
        val date = optNullableString(json, "date")?.let { LocalDate.parse(it) }
        val days = json.optJSONArray("daysOfWeek")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                runCatching { DayOfWeek.valueOf(arr.getString(i)) }.getOrNull()
            }.toSet()
        }.orEmpty()
        val interval = if (json.has("intervalMinutes") && !json.isNull("intervalMinutes")) {
            json.optLong("intervalMinutes")
        } else {
            null
        }
        return when (type) {
            ScheduleSpec.ScheduleType.ONCE -> ScheduleSpec.once(date ?: LocalDate.now(), time ?: return null)
            ScheduleSpec.ScheduleType.DAILY -> ScheduleSpec.daily(time ?: return null)
            ScheduleSpec.ScheduleType.WEEKLY -> ScheduleSpec.weekly(time ?: return null, days)
            ScheduleSpec.ScheduleType.INTERVAL -> ScheduleSpec.interval(interval ?: return null)
        }
    }

    companion object {
        private const val TAG = "DeepSeekNlp"
        private val DEFAULT_TASK_PROMPT = """
            你是一个手机自动化任务解析器。用户输入很可能来自语音识别，可能存在同音字、口语省略、无标点、时间口语化（如“八点二十五”）等情况。
            请先结合上下文推断用户的真实意图，再输出严格的 JSON，不要输出任何额外文字，不要用 Markdown 代码块包裹。

            JSON Schema（所有字段都要包含，不确定的填 null）：
            {
              "rawText": "原始输入",
              "schedule": {
                "type": "ONCE" 或 "DAILY" 或 "WEEKLY" 或 "INTERVAL",
                "time": "HH:mm" 或 null,
                "date": "yyyy-MM-dd" 或 null,
                "daysOfWeek": ["MONDAY"] 或 null,
                "intervalMinutes": 30 或 null
              },
              "actionType": "OPEN_APP" 或 "OPEN_DEEPLINK" 或 "NOTIFY" 或 "SHORTCUT" 或 "UI_ACTION",
              "targetPackage": "应用包名" 或 null,
              "targetActivity": "Activity 名" 或 null,
              "deepLink": "URL" 或 null,
              "executionMode": "AUTO" 或 "NOTIFICATION" 或 "DEEP_LINK" 或 "SHIZUKU",
              "confidence": 0.0 到 1.0
            }

            字段规则：
            - schedule.type=ONCE 时填 date + time；DAILY 填 time；WEEKLY 填 time + daysOfWeek；INTERVAL 填 intervalMinutes。
            - daysOfWeek 使用 java.time.DayOfWeek 枚举：MONDAY、TUESDAY、WEDNESDAY、THURSDAY、FRIDAY、SATURDAY、SUNDAY。
            - 如果没有明确时间，schedule 填 null。
            - OPEN_APP 必须尽量填 targetPackage；不知道包名时填 null，不要编造包名。
            - 打开网页/URL 用 OPEN_DEEPLINK，deepLink 填完整 URL。
            - 提醒用 NOTIFY。
            - 修改类输入（如“不是8点，改成9点”）也要尽量还原成完整任务 JSON。

            常见应用包名（只列常用，未列出且不确定就填 null）：
            - 企业微信 -> com.tencent.wework
            - 微信 -> com.tencent.mm
            - 钉钉 -> com.alibaba.android.rimet
            - 支付宝 -> com.eg.android.AlipayGphone
            - 高德地图 -> com.autonavi.minimap
            - 百度地图 -> com.baidu.BaiduMap
            - 淘宝 -> com.taobao.taobao
            - 京东 -> com.jingdong.app.mall
            - 抖音 -> com.ss.android.ugc.aweme
            - 微博 -> com.sina.weibo
            - QQ -> com.tencent.mobileqq
            - 邮箱 -> com.android.email
            - 时钟 -> com.android.deskclock
            - 相机 -> com.android.camera

            示例1：
            输入：每天早上8点25分打开企业微信
            输出：{"rawText":"每天早上8点25分打开企业微信","schedule":{"type":"DAILY","time":"08:25","date":null,"daysOfWeek":null,"intervalMinutes":null},"actionType":"OPEN_APP","targetPackage":"com.tencent.wework","targetActivity":null,"deepLink":null,"executionMode":"AUTO","confidence":0.98}

            示例2：
            输入：明天下午三点提醒我开会
            输出：{"rawText":"明天下午三点提醒我开会","schedule":{"type":"ONCE","time":"15:00","date":"<计算后的明天日期>","daysOfWeek":null,"intervalMinutes":null},"actionType":"NOTIFY","targetPackage":null,"targetActivity":null,"deepLink":null,"executionMode":"AUTO","confidence":0.95}
        """.trimIndent()
    }
}
