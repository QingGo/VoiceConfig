package com.voiceconfig.data.local.mapper

import com.voiceconfig.core.model.ActionType
import com.voiceconfig.core.model.FallbackSpec
import com.voiceconfig.core.model.TriggerAction
import com.voiceconfig.core.model.TriggerCondition
import com.voiceconfig.core.model.TriggerRule
import com.voiceconfig.core.model.VerifySpec
import com.voiceconfig.data.local.entity.TriggerRuleEntity

object TriggerRuleMapper {

    fun toEntity(rule: TriggerRule): TriggerRuleEntity = TriggerRuleEntity(
        id = rule.id,
        name = rule.name,
        conditionType = rule.condition.type,
        conditionTime = rule.condition.time,
        conditionDaysOfWeek = rule.condition.daysOfWeek.joinToString(","),
        conditionWifiSsid = rule.condition.wifiSsid,
        conditionLatitude = rule.condition.latitude,
        conditionLongitude = rule.condition.longitude,
        conditionRadiusMeters = rule.condition.radiusMeters,
        conditionBatteryLevel = rule.condition.batteryLevel,
        conditionBatteryState = rule.condition.batteryState,
        conditionCalendarKeyword = rule.condition.calendarKeyword,
        actionType = rule.action.type,
        targetPackage = rule.action.targetPackage,
        targetActivity = rule.action.targetActivity,
        deepLink = rule.action.deepLink,
        tapTarget = rule.action.tapTarget,
        inputText = rule.action.inputText,
        shellCommand = rule.action.shellCommand,
        settingKey = rule.action.settingKey,
        settingValue = rule.action.settingValue,
        verifyType = rule.verify.type,
        expectedPackage = rule.verify.expectedPackage,
        expectedText = rule.verify.expectedText,
        fallbackNotifyOnFailure = rule.fallback.notifyOnFailure,
        fallbackRetryCount = rule.fallback.retryCount,
        fallbackAskUser = rule.fallback.askUser,
        enabled = rule.enabled,
        nextRunAtEpochMillis = rule.nextRunAtEpochMillis,
        createdAtEpochMillis = rule.createdAtEpochMillis,
        updatedAtEpochMillis = rule.updatedAtEpochMillis,
    )

    fun toDomain(entity: TriggerRuleEntity): TriggerRule = TriggerRule(
        id = entity.id,
        name = entity.name,
        condition = TriggerCondition(
            type = entity.conditionType,
            time = entity.conditionTime,
            daysOfWeek = entity.conditionDaysOfWeek
                ?.split(",")
                ?.mapNotNull { it.toIntOrNull() }
                ?.toSet()
                .orEmpty(),
            wifiSsid = entity.conditionWifiSsid,
            latitude = entity.conditionLatitude,
            longitude = entity.conditionLongitude,
            radiusMeters = entity.conditionRadiusMeters,
            batteryLevel = entity.conditionBatteryLevel,
            batteryState = entity.conditionBatteryState,
            calendarKeyword = entity.conditionCalendarKeyword,
        ),
        action = TriggerAction(
            type = entity.actionType,
            targetPackage = entity.targetPackage,
            targetActivity = entity.targetActivity,
            deepLink = entity.deepLink,
            tapTarget = entity.tapTarget,
            inputText = entity.inputText,
            shellCommand = entity.shellCommand,
            settingKey = entity.settingKey,
            settingValue = entity.settingValue,
        ),
        verify = VerifySpec(
            type = entity.verifyType,
            expectedPackage = entity.expectedPackage,
            expectedText = entity.expectedText,
        ),
        fallback = FallbackSpec(
            notifyOnFailure = entity.fallbackNotifyOnFailure,
            retryCount = entity.fallbackRetryCount,
            askUser = entity.fallbackAskUser,
        ),
        enabled = entity.enabled,
        nextRunAtEpochMillis = entity.nextRunAtEpochMillis,
        createdAtEpochMillis = entity.createdAtEpochMillis,
        updatedAtEpochMillis = entity.updatedAtEpochMillis,
    )
}
