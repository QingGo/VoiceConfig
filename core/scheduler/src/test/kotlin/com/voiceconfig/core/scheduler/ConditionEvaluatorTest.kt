package com.voiceconfig.core.scheduler

import com.voiceconfig.core.model.TriggerCondition.BatteryState
import com.voiceconfig.core.model.TriggerCondition
import java.time.LocalDateTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionEvaluatorTest {

    @Test
    fun `time condition matches exact time and weekday`() {
        val condition = TriggerCondition(
            type = TriggerCondition.TriggerType.TIME,
            time = "08:25",
            daysOfWeek = setOf(1, 2, 3, 4, 5),
        )
        val snapshot = ConditionEvaluator.Snapshot(
            now = LocalDateTime.of(2026, 8, 20, 8, 25), // Thursday
        )
        assertTrue(ConditionEvaluator.matches(condition, snapshot))
    }

    @Test
    fun `time condition does not match weekend`() {
        val condition = TriggerCondition(
            type = TriggerCondition.TriggerType.TIME,
            time = "08:25",
            daysOfWeek = setOf(1, 2, 3, 4, 5),
        )
        val snapshot = ConditionEvaluator.Snapshot(
            now = LocalDateTime.of(2026, 8, 22, 8, 25), // Saturday
        )
        assertFalse(ConditionEvaluator.matches(condition, snapshot))
    }

    @Test
    fun `wifi condition matches ssid ignoring case`() {
        val condition = TriggerCondition(
            type = TriggerCondition.TriggerType.WIFI,
            wifiSsid = "Company-WiFi",
        )
        val snapshot = ConditionEvaluator.Snapshot(
            connectedWifiSsids = setOf("company-wifi", "Home"),
        )
        assertTrue(ConditionEvaluator.matches(condition, snapshot))
    }

    @Test
    fun `battery low condition matches threshold`() {
        val condition = TriggerCondition(
            type = TriggerCondition.TriggerType.BATTERY,
            batteryState = BatteryState.LOW,
            batteryLevel = 20,
        )
        assertTrue(ConditionEvaluator.matches(condition, ConditionEvaluator.Snapshot(batteryLevel = 15)))
        assertFalse(ConditionEvaluator.matches(condition, ConditionEvaluator.Snapshot(batteryLevel = 25)))
    }

    @Test
    fun `location condition respects radius`() {
        val condition = TriggerCondition(
            type = TriggerCondition.TriggerType.LOCATION,
            latitude = 31.2304,
            longitude = 121.4737,
            radiusMeters = 100,
        )
        assertTrue(ConditionEvaluator.matches(condition, ConditionEvaluator.Snapshot(latitude = 31.2304, longitude = 121.4737)))
        assertFalse(ConditionEvaluator.matches(condition, ConditionEvaluator.Snapshot(latitude = 32.0, longitude = 121.0)))
    }
}
