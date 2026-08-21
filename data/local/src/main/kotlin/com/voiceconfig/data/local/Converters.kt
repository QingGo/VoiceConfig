package com.voiceconfig.data.local

import androidx.room.TypeConverter
import com.voiceconfig.core.model.ActionType
import com.voiceconfig.core.model.AppAlias
import com.voiceconfig.core.model.ExecutionMode
import com.voiceconfig.core.model.ExecutionStatus
import com.voiceconfig.core.model.ScheduleSpec

class Converters {
    @TypeConverter
    fun actionTypeToString(value: ActionType): String = value.name

    @TypeConverter
    fun stringToActionType(value: String): ActionType = ActionType.valueOf(value)

    @TypeConverter
    fun executionModeToString(value: ExecutionMode): String = value.name

    @TypeConverter
    fun stringToExecutionMode(value: String): ExecutionMode = ExecutionMode.valueOf(value)

    @TypeConverter
    fun executionStatusToString(value: ExecutionStatus): String = value.name

    @TypeConverter
    fun stringToExecutionStatus(value: String): ExecutionStatus = ExecutionStatus.valueOf(value)

    @TypeConverter
    fun scheduleTypeToString(value: ScheduleSpec.ScheduleType): String = value.name

    @TypeConverter
    fun stringToScheduleType(value: String): ScheduleSpec.ScheduleType = ScheduleSpec.ScheduleType.valueOf(value)

    @TypeConverter
    fun aliasSourceToString(value: AppAlias.AliasSource): String = value.name

    @TypeConverter
    fun stringToAliasSource(value: String): AppAlias.AliasSource = AppAlias.AliasSource.valueOf(value)
}
