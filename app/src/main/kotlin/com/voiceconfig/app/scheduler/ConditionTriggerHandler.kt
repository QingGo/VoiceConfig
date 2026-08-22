package com.voiceconfig.app.scheduler

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.BatteryManager
import com.voiceconfig.app.agent.ToolRegistry
import com.voiceconfig.core.executor.ExecutionEngine
import com.voiceconfig.core.executor.ExecutionRequest
import com.voiceconfig.core.model.ActionType
import com.voiceconfig.core.model.ExecutionMode
import com.voiceconfig.core.model.ScheduleSpec
import com.voiceconfig.core.model.Task
import com.voiceconfig.core.model.TriggerCondition
import com.voiceconfig.core.model.TriggerRule
import com.voiceconfig.core.scheduler.ConditionEvaluator
import com.voiceconfig.data.local.repository.TriggerRuleRepository
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 条件触发统一处理逻辑，供广播接收器和前台服务动态注册使用。
 */
@Singleton
class ConditionTriggerHandler @Inject constructor(
    private val triggerRuleRepository: TriggerRuleRepository,
    private val executionEngine: ExecutionEngine,
    private val toolRegistry: ToolRegistry,
) {
    suspend fun handle(context: Context, intent: Intent) {
        Log.i(TAG, "handle action=${intent.action}")
        if (intent.action == ConditionTriggerReceiver.ACTION_LOCATION_PROXIMITY_ALERT) {
            val ruleId = intent.getLongExtra(ConditionTriggerReceiver.EXTRA_RULE_ID, -1L)
            if (ruleId <= 0L) return
            val rule = triggerRuleRepository.getById(ruleId)
            if (rule != null && rule.enabled) {
                executeRule(context, rule)
            }
            return
        }

        val eventType = when (intent.action) {
            WifiManager.NETWORK_STATE_CHANGED_ACTION -> TriggerCondition.TriggerType.WIFI
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED,
            Intent.ACTION_BATTERY_LOW,
            -> TriggerCondition.TriggerType.BATTERY
            else -> return
        }
        val rules = triggerRuleRepository.getEnabled()
        val snapshot = buildSnapshot(context, eventType)
        val matched = rules.filter { it.condition.type == eventType && ConditionEvaluator.matches(it.condition, snapshot) }
        Log.i(TAG, "event=$eventType rules=${rules.size} matched=${matched.size} snapshot=$snapshot")
        matched.forEach { rule ->
            executeRule(context, rule)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildSnapshot(
        context: Context,
        type: TriggerCondition.TriggerType,
    ): ConditionEvaluator.Snapshot = when (type) {
        TriggerCondition.TriggerType.WIFI -> {
            val ssid = runCatching {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifi.connectionInfo?.ssid?.trim('"')
            }.getOrNull()
            ConditionEvaluator.Snapshot(connectedWifiSsids = if (ssid.isNullOrBlank()) emptySet() else setOf(ssid))
        }
        TriggerCondition.TriggerType.BATTERY -> {
            val sticky = runCatching {
                context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }.getOrNull()
            val level = sticky?.let { intent ->
                val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                if (raw >= 0) (raw * 100) / scale else null
            }
            val charging = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING ||
                sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_FULL
            ConditionEvaluator.Snapshot(batteryLevel = level, isCharging = charging)
        }
        else -> ConditionEvaluator.Snapshot()
    }

    private companion object {
        private const val TAG = "ConditionHandler"
    }

    private suspend fun executeRule(context: Context, rule: TriggerRule) {
        val action = rule.action
        when (action.type) {
            ActionType.OPEN_APP, ActionType.OPEN_DEEPLINK -> {
                val now = java.time.LocalDateTime.now()
                val task = Task(
                    rawText = rule.name,
                    title = rule.name,
                    schedule = ScheduleSpec.once(now.toLocalDate(), now.toLocalTime()),
                    actionType = action.type,
                    targetPackage = action.targetPackage,
                    targetActivity = action.targetActivity,
                    deepLink = action.deepLink,
                    executionMode = if (!action.deepLink.isNullOrBlank()) ExecutionMode.DEEP_LINK else ExecutionMode.SHIZUKU,
                    createdAtEpochMillis = System.currentTimeMillis(),
                    updatedAtEpochMillis = System.currentTimeMillis(),
                )
                executionEngine.execute(ExecutionRequest(task = task, requestedMode = task.executionMode))
            }
            ActionType.SHORTCUT, ActionType.UI_ACTION -> {
                val command = action.shellCommand
                if (!command.isNullOrBlank()) {
                    toolRegistry.get("run_shell")?.execute(mapOf("command" to command))
                } else {
                    if (!action.targetPackage.isNullOrBlank()) {
                        val now = java.time.LocalDateTime.now()
                        val openTask = Task(
                            rawText = rule.name,
                            title = rule.name,
                            schedule = ScheduleSpec.once(now.toLocalDate(), now.toLocalTime()),
                            actionType = ActionType.OPEN_APP,
                            targetPackage = action.targetPackage,
                            targetActivity = action.targetActivity,
                            deepLink = null,
                            executionMode = ExecutionMode.SHIZUKU,
                            createdAtEpochMillis = System.currentTimeMillis(),
                            updatedAtEpochMillis = System.currentTimeMillis(),
                        )
                        executionEngine.execute(ExecutionRequest(openTask, ExecutionMode.SHIZUKU))
                        toolRegistry.get("wait")?.execute(mapOf("ms" to 800))
                    }
                    val tapTarget = action.tapTarget
                    if (!tapTarget.isNullOrBlank()) {
                        val parts = tapTarget.split(",").mapNotNull { it.trim().toIntOrNull() }
                        if (parts.size == 2) {
                            toolRegistry.get("tap")?.execute(mapOf("x" to parts[0], "y" to parts[1]))
                        } else {
                            toolRegistry.get("notify")?.execute(
                                mapOf(
                                    "title" to rule.name,
                                    "content" to "条件已满足，请手动点击：$tapTarget",
                                ),
                            )
                        }
                    }
                    if (!action.inputText.isNullOrBlank()) {
                        toolRegistry.get("input_text")?.execute(mapOf("text" to action.inputText))
                    }
                    if (action.tapTarget.isNullOrBlank() && action.inputText.isNullOrBlank()) {
                        toolRegistry.get("notify")?.execute(
                            mapOf(
                                "title" to rule.name,
                                "content" to "条件已满足",
                            ),
                        )
                    }
                }
            }
            ActionType.NOTIFY -> {
                toolRegistry.get("notify")?.execute(
                    mapOf("title" to rule.name, "content" to "触发条件已满足"),
                )
            }
            ActionType.AGENT -> {
                toolRegistry.get("notify")?.execute(
                    mapOf(
                        "title" to rule.name,
                        "content" to "复杂任务需要智能助手执行，请手动在「智能助手」中发起",
                    ),
                )
            }
        }
    }
}
