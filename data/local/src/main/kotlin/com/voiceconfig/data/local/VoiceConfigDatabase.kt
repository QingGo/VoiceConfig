package com.voiceconfig.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.voiceconfig.data.local.dao.AgentMessageDao
import com.voiceconfig.data.local.dao.AgentRunRecordDao
import com.voiceconfig.data.local.dao.AgentStepDao
import com.voiceconfig.data.local.dao.AgentSessionDao
import com.voiceconfig.data.local.dao.TaskPlanDao
import com.voiceconfig.data.local.dao.AiDebugLogDao
import com.voiceconfig.data.local.dao.AppAliasDao
import com.voiceconfig.data.local.dao.ExecutionLogDao
import com.voiceconfig.data.local.dao.TaskDao
import com.voiceconfig.data.local.dao.TriggerRuleDao
import com.voiceconfig.data.local.dao.TemplateDao
import com.voiceconfig.data.local.dao.RemoteNodeDao
import com.voiceconfig.data.local.dao.RemoteProjectDao
import com.voiceconfig.data.local.dao.ShoppingItemDao
import com.voiceconfig.data.local.dao.TaskEventDao
import com.voiceconfig.data.local.entity.AgentMessageEntity
import com.voiceconfig.data.local.entity.AgentRunRecordEntity
import com.voiceconfig.data.local.entity.AgentStepEntity
import com.voiceconfig.data.local.entity.AgentSessionEntity
import com.voiceconfig.data.local.entity.TaskPlanEntity
import com.voiceconfig.data.local.entity.TaskPlanStepEntity
import com.voiceconfig.data.local.entity.AiDebugLogEntity
import com.voiceconfig.data.local.entity.AppAliasEntity
import com.voiceconfig.data.local.entity.ExecutionLogEntity
import com.voiceconfig.data.local.entity.TaskEntity
import com.voiceconfig.data.local.entity.TemplateEntity
import com.voiceconfig.data.local.entity.RemoteNodeEntity
import com.voiceconfig.data.local.entity.RemoteProjectEntity
import com.voiceconfig.data.local.entity.ShoppingItemEntity
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
        AgentRunRecordEntity::class,
        AgentStepEntity::class,
        TaskEventEntity::class,
        TaskPlanEntity::class,
        TaskPlanStepEntity::class,
        RemoteNodeEntity::class,
        RemoteProjectEntity::class,
        ShoppingItemEntity::class,
    ],
    version = 21,
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
    abstract fun agentRunRecordDao(): AgentRunRecordDao
    abstract fun agentStepDao(): AgentStepDao
    abstract fun taskEventDao(): TaskEventDao
    abstract fun taskPlanDao(): TaskPlanDao
    abstract fun remoteNodeDao(): RemoteNodeDao
    abstract fun remoteProjectDao(): RemoteProjectDao
    abstract fun shoppingItemDao(): ShoppingItemDao

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

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `execution_logs` ADD COLUMN `requestedMode` TEXT")
                db.execSQL("ALTER TABLE `execution_logs` ADD COLUMN `verified` INTEGER")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `task_plans` (
                        `id` TEXT NOT NULL,
                        `goal` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `waitingForHuman` TEXT,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        `updatedAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `task_plan_steps` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `planId` TEXT NOT NULL,
                        `stepId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `evidence` TEXT NOT NULL DEFAULT '',
                        `note` TEXT NOT NULL DEFAULT '',
                        `sortOrder` INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(`planId`) REFERENCES `task_plans`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_plan_steps_planId` ON `task_plan_steps` (`planId`)")
            }
        }
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_run_records` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `runId` TEXT NOT NULL,
                        `userText` TEXT NOT NULL,
                        `ok` INTEGER NOT NULL,
                        `state` TEXT NOT NULL,
                        `message` TEXT NOT NULL,
                        `toolCallsJson` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `startedAtEpochMillis` INTEGER NOT NULL,
                        `finishedAtEpochMillis` INTEGER NOT NULL,
                        `waitingForHuman` INTEGER NOT NULL,
                        `verified` INTEGER
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_run_records_runId` ON `agent_run_records` (`runId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_run_records_startedAtEpochMillis` ON `agent_run_records` (`startedAtEpochMillis`)")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `agent_run_records` ADD COLUMN `capabilitySummary` TEXT")
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `remote_nodes` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `nodeId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `host` TEXT NOT NULL,
                        `port` INTEGER NOT NULL,
                        `scheme` TEXT NOT NULL,
                        `tokenCiphertext` TEXT,
                        `tokenIv` TEXT,
                        `allowedCommandsJson` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `paused` INTEGER NOT NULL,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        `updatedAtEpochMillis` INTEGER NOT NULL,
                        `lastSeenAtEpochMillis` INTEGER,
                        `lastStatus` TEXT,
                        `lastError` TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_remote_nodes_nodeId` ON `remote_nodes` (`nodeId`)")
            }
        }

        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `agent_run_records` ADD COLUMN `safetyConfirmations` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `agent_run_records` ADD COLUMN `safetyApprovals` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `agent_run_records` ADD COLUMN `safetyDenials` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `agent_run_records` ADD COLUMN `safetyBlocks` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `remote_projects` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `projectId` TEXT NOT NULL,
                        `nodeHost` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `rootPath` TEXT NOT NULL,
                        `repoType` TEXT NOT NULL,
                        `buildCommand` TEXT,
                        `testCommand` TEXT,
                        `installCommand` TEXT,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        `updatedAtEpochMillis` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_remote_projects_projectId` ON `remote_projects` (`projectId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_remote_projects_rootPath` ON `remote_projects` (`rootPath`)")
            }
        }

        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `shopping_items` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `productId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `platform` TEXT NOT NULL,
                        `price` REAL NOT NULL,
                        `originalPrice` REAL,
                        `rating` REAL,
                        `reviewCount` INTEGER,
                        `sales` INTEGER,
                        `tagsJson` TEXT NOT NULL,
                        `url` TEXT NOT NULL DEFAULT '',
                        `note` TEXT NOT NULL DEFAULT '',
                        `status` TEXT NOT NULL DEFAULT 'WATCH',
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        `updatedAtEpochMillis` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_shopping_items_productId` ON `shopping_items` (`productId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_items_platform` ON `shopping_items` (`platform`)")
            }
        }

    }
}
