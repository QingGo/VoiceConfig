package com.voiceconfig.core.model

/**
 * 触发器条件：描述“什么时候/什么条件下”执行动作。
 */
data class TriggerCondition(
    val type: TriggerType,
    val time: String? = null,             // "HH:mm"
    val daysOfWeek: Set<Int> = emptySet(), // 1=MONDAY ... 7=SUNDAY（java.time.DayOfWeek.value）
    val wifiSsid: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusMeters: Int? = null,
    val batteryLevel: Int? = null,        // 0-100
    val batteryState: BatteryState? = null,
    val calendarKeyword: String? = null,
) {
    enum class TriggerType {
        TIME,
        WIFI,
        LOCATION,
        BATTERY,
        CALENDAR,
    }

    enum class BatteryState {
        LOW,
        CHARGING,
        FULL,
    }
}
