package com.voiceconfig.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.voiceconfig.data.local.dao.AgentMessageDao
import com.voiceconfig.data.local.dao.AgentStepDao
import com.voiceconfig.data.local.dao.AgentSessionDao
import com.voiceconfig.data.local.dao.AiDebugLogDao
import com.voiceconfig.data.local.dao.AppAliasDao
import com.voiceconfig.data.local.dao.ExecutionLogDao
import com.voiceconfig.data.local.dao.TaskDao
import com.voiceconfig.data.local.dao.TriggerRuleDao
import com.voiceconfig.data.local.dao.TemplateDao
import com.voiceconfig.data.local.dao.TaskEventDao
import com.voiceconfig.data.local.entity.AgentMessageEntity
import com.voiceconfig.data.local.entity.AgentStepEntity
import com.voiceconfig.data.local.entity.AgentSessionEntity
import com.voiceconfig.data.local.entity.AiDebugLogEntity
import com.voiceconfig.data.local.entity.AppAliasEntity
import com.voiceconfig.data.local.entity.ExecutionLogEntity
import com.voiceconfig.data.local.entity.TaskEntity
import com.voiceconfig.data.local.entity.TemplateEntity
import com.voiceconfig.data.local.entity.TaskEventEntity
import com.voiceconfig.data.local.entity.TriggerRuleEntity

@TypeConverters(Converters::class)
@Database(
    entities = [
        TaskEntity::class,
        ExecutionLogEntity::class,
        AppAliasEntity::class,
        TemplateEntity::class,
        AiDebugLogEntity::class,
        TriggerRuleEntity::class,
        AgentSessionEntity::class,
        AgentMessageEntity::class,
        AgentStepEntity::class,
        TaskEventEntity::class,
    ],
    version = 13,
    exportSchema = false,
)
abstract class VoiceConfigDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun executionLogDao(): ExecutionLogDao
    abstract fun appAliasDao(): AppAliasDao
    abstract fun templateDao(): TemplateDao
    abstract fun aiDebugLogDao(): AiDebugLogDao
    abstract fun triggerRuleDao(): TriggerRuleDao
    abstract fun agentSessionDao(): AgentSessionDao
    abstract fun agentMessageDao(): AgentMessageDao
    abstract fun agentStepDao(): AgentStepDao
    abstract fun taskEventDao(): TaskEventDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ai_debug_logs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        `input` TEXT NOT NULL,
                        `model` TEXT NOT NULL,
                        `thinkingEnabled` INTEGER NOT NULL,
                        `reasoningEffort` TEXT NOT NULL,
                        `rawResponse` TEXT,
                        `parseError` TEXT
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `trigger_rules` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `conditionType` TEXT NOT NULL,
                        `conditionTime` TEXT,
                        `conditionDaysOfWeek` TEXT,
                        `conditionWifiSsid` TEXT,
                        `conditionLatitude` REAL,
                        `conditionLongitude` REAL,
                        `conditionRadiusMeters` INTEGER,
                        `conditionBatteryLevel` INTEGER,
                        `conditionBatteryState` TEXT,
                        `conditionCalendarKeyword` TEXT,
                        `actionType` TEXT NOT NULL,
                        `targetPackage` TEXT,
                        `targetActivity` TEXT,
                        `deepLink` TEXT,
                        `tapTarget` TEXT,
                        `inputText` TEXT,
                        `shellCommand` TEXT,
                        `settingKey` TEXT,
                        `settingValue` TEXT,
                        `verifyType` TEXT NOT NULL,
                        `expectedPackage` TEXT,
                        `expectedText` TEXT,
                        `fallbackNotifyOnFailure` INTEGER NOT NULL,
                        `fallbackRetryCount` INTEGER NOT NULL,
                        `fallbackAskUser` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `nextRunAtEpochMillis` INTEGER,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        `updatedAtEpochMillis` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_sessions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        `updatedAtEpochMillis` INTEGER NOT NULL,
                        `messageCount` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_messages` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` INTEGER NOT NULL,
                        `role` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `toolName` TEXT,
                        `toolArgs` TEXT,
                        `toolResultOk` INTEGER,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        FOREIGN KEY(`sessionId`) REFERENCES `agent_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_messages_sessionId` ON `agent_messages` (`sessionId`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `task_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `taskId` INTEGER,
                        `agentSessionId` INTEGER,
                        `eventType` TEXT NOT NULL,
                        `rawText` TEXT,
                        `summary` TEXT NOT NULL,
                        `createdAtEpochMillis` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_events_taskId` ON `task_events` (`taskId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_events_agentSessionId` ON `task_events` (`agentSessionId`)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `agent_messages` ADD COLUMN `toolCallId` TEXT")
                db.execSQL("ALTER TABLE `agent_messages` ADD COLUMN `toolCallsJson` TEXT")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `execution_logs` ADD COLUMN `agentSessionId` INTEGER")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `agent_messages` ADD COLUMN `reasoningContent` TEXT")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_steps` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` INTEGER NOT NULL,
                        `runId` TEXT NOT NULL,
                        `stepIndex` INTEGER NOT NULL,
                        `toolName` TEXT NOT NULL,
                        `argsText` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `message` TEXT NOT NULL,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        `updatedAtEpochMillis` INTEGER NOT NULL,
                        FOREIGN KEY(`sessionId`) REFERENCES `agent_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_steps_sessionId` ON `agent_steps` (`sessionId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_steps_session_runId_stepIndex` ON `agent_steps` (`sessionId`, `runId`, `stepIndex`)")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `agentPrompt` TEXT")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `agent_messages` ADD COLUMN `durationMs` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `agent_messages` ADD COLUMN `thinkingMs` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `agent_messages` ADD COLUMN `outputMs` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `agent_steps` ADD COLUMN `durationMs` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `agent_steps` ADD COLUMN `gapBeforeMs` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `agent_steps` ADD COLUMN `startedAtElapsedMs` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `agent_sessions` ADD COLUMN `lastRunDurationMs` INTEGER")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `agent_messages` ADD COLUMN `ttftMs` INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
