package com.voiceconfig.core.scheduler

import com.voiceconfig.core.model.TriggerCondition.BatteryState
import com.voiceconfig.core.model.TriggerCondition
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 纯 Kotlin 条件求值器：判断一个 [TriggerCondition] 在当前快照下是否满足。
 *
 * 不依赖 Android 框架，便于单元测试。时间/位置等数据由调用方从系统获取后传入。
 */
object ConditionEvaluator {

    data class Snapshot(
        val now: LocalDateTime = LocalDateTime.now(),
        val connectedWifiSsids: Set<String> = emptySet(),
        val batteryLevel: Int? = null,
        val isCharging: Boolean = false,
        val latitude: Double? = null,
        val longitude: Double? = null,
    )

    fun matches(condition: TriggerCondition, snapshot: Snapshot): Boolean = when (condition.type) {
        TriggerCondition.TriggerType.TIME -> matchesTime(condition, snapshot.now)
        TriggerCondition.TriggerType.WIFI -> condition.wifiSsid?.let { ssid ->
            snapshot.connectedWifiSsids.any { it.equals(ssid, ignoreCase = true) }
        } ?: false
        TriggerCondition.TriggerType.BATTERY -> matchesBattery(condition, snapshot)
        TriggerCondition.TriggerType.LOCATION -> matchesLocation(condition, snapshot)
        TriggerCondition.TriggerType.CALENDAR -> true // 日历匹配需要额外数据源，暂视为外部判断
    }

    private fun matchesTime(condition: TriggerCondition, now: LocalDateTime): Boolean {
        val time = condition.time?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: return false
        if (now.toLocalTime() != time) return false
        if (condition.daysOfWeek.isNotEmpty()) {
            val today = now.dayOfWeek.value // 1=MONDAY ... 7=SUNDAY
            if (today !in condition.daysOfWeek) return false
        }
        return true
    }

    private fun matchesBattery(condition: TriggerCondition, snapshot: Snapshot): Boolean {
        val level = snapshot.batteryLevel ?: return false
        when (condition.batteryState) {
            BatteryState.LOW -> {
                val threshold = condition.batteryLevel ?: 20
                return level <= threshold
            }
            BatteryState.CHARGING -> return snapshot.isCharging
            BatteryState.FULL -> return level >= 95
            null -> {
                val threshold = condition.batteryLevel ?: return false
                return level <= threshold
            }
        }
    }

    private fun matchesLocation(condition: TriggerCondition, snapshot: Snapshot): Boolean {
        val lat = condition.latitude ?: return false
        val lng = condition.longitude ?: return false
        val currentLat = snapshot.latitude ?: return false
        val currentLng = snapshot.longitude ?: return false
        val radius = condition.radiusMeters ?: 100
        return distanceMeters(lat, lng, currentLat, currentLng) <= radius
    }

    private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return earthRadius * c
    }
}
