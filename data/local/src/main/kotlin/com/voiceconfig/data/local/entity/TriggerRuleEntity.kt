package com.voiceconfig.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.voiceconfig.core.model.TriggerCondition

@Entity(tableName = "trigger_rules")
data class TriggerRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val conditionType: TriggerCondition.TriggerType,
    val conditionTime: String?,
    val conditionDaysOfWeek: String?,
    val conditionWifiSsid: String?,
    val conditionLatitude: Double?,
    val conditionLongitude: Double?,
    val conditionRadiusMeters: Int?,
    val conditionBatteryLevel: Int?,
    val conditionBatteryState: TriggerCondition.BatteryState?,
    val conditionCalendarKeyword: String?,
    val actionType: com.voiceconfig.core.model.ActionType,
    val targetPackage: String?,
    val targetActivity: String?,
    val deepLink: String?,
    val tapTarget: String?,
    val inputText: String?,
    val shellCommand: String?,
    val settingKey: String?,
    val settingValue: String?,
    val verifyType: com.voiceconfig.core.model.VerifySpec.VerifyType,
    val expectedPackage: String?,
    val expectedText: String?,
    val fallbackNotifyOnFailure: Boolean,
    val fallbackRetryCount: Int,
    val fallbackAskUser: Boolean,
    val enabled: Boolean,
    val nextRunAtEpochMillis: Long?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
