package com.voiceconfig.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceConfigMigrationTest {

    @Test
    fun `migrations from v1 to v9 create expected columns`() {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            createV1Schema(conn)
            val db = supportDatabase(conn)
            VoiceConfigDatabase.MIGRATION_1_2.migrate(db)
            VoiceConfigDatabase.MIGRATION_2_3.migrate(db)
            VoiceConfigDatabase.MIGRATION_3_4.migrate(db)
            VoiceConfigDatabase.MIGRATION_4_5.migrate(db)
            VoiceConfigDatabase.MIGRATION_5_6.migrate(db)
            VoiceConfigDatabase.MIGRATION_6_7.migrate(db)
        VoiceConfigDatabase.MIGRATION_7_8.migrate(db)
        VoiceConfigDatabase.MIGRATION_8_9.migrate(db)

            assertTrue(columns(conn, "tasks").containsAll(setOf("id", "rawText", "title", "enabled", "scheduleType", "time", "date", "daysOfWeek", "intervalMinutes", "actionType", "targetPackage", "targetActivity", "deepLink", "agentPrompt", "executionMode", "nextRunAtEpochMillis", "createdAtEpochMillis", "updatedAtEpochMillis")))
            assertTrue(columns(conn, "execution_logs").containsAll(setOf("id", "taskId", "scheduledAtEpochMillis", "startedAtEpochMillis", "finishedAtEpochMillis", "status", "executionMode", "errorCode", "message", "agentSessionId")))
            assertTrue(columns(conn, "app_aliases").containsAll(setOf("id", "alias", "packageName", "activityName", "source")))
            assertTrue(columns(conn, "templates").containsAll(setOf("id", "name", "description", "category", "configJson", "usageCount")))

            assertTrue(columns(conn, "ai_debug_logs").containsAll(setOf("id", "createdAtEpochMillis", "input", "model", "thinkingEnabled", "reasoningEffort", "rawResponse", "parseError")))
            assertTrue(columns(conn, "trigger_rules").containsAll(setOf("id", "name", "conditionType", "actionType", "verifyType", "enabled")))
            assertTrue(columns(conn, "agent_sessions").containsAll(setOf("id", "title", "createdAtEpochMillis", "updatedAtEpochMillis", "messageCount")))
            assertTrue(columns(conn, "agent_messages").containsAll(setOf("id", "sessionId", "role", "content", "toolName", "toolArgs", "toolResultOk", "toolCallId", "toolCallsJson", "reasoningContent", "createdAtEpochMillis")))
            assertTrue(columns(conn, "task_events").containsAll(setOf("id", "taskId", "agentSessionId", "eventType", "rawText", "summary", "createdAtEpochMillis")))
            assertTrue(columns(conn, "agent_steps").containsAll(setOf("id", "sessionId", "runId", "stepIndex", "toolName", "argsText", "status", "message", "createdAtEpochMillis", "updatedAtEpochMillis")))

            // 旧表不应因迁移而消失
            assertFalse(columns(conn, "tasks").isEmpty())
            assertFalse(columns(conn, "execution_logs").isEmpty())
            assertFalse(columns(conn, "app_aliases").isEmpty())
            assertFalse(columns(conn, "templates").isEmpty())
        } finally {
            conn.close()
        }
    }

    private fun createV1Schema(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE `tasks` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `rawText` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `scheduleType` TEXT NOT NULL,
                    `time` TEXT,
                    `date` TEXT,
                    `daysOfWeek` TEXT,
                    `intervalMinutes` INTEGER,
                    `actionType` TEXT NOT NULL,
                    `targetPackage` TEXT,
                    `targetActivity` TEXT,
                    `deepLink` TEXT,
                    `executionMode` TEXT NOT NULL,
                    `nextRunAtEpochMillis` INTEGER,
                    `createdAtEpochMillis` INTEGER NOT NULL,
                    `updatedAtEpochMillis` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            stmt.execute(
                """
                CREATE TABLE `execution_logs` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `taskId` INTEGER NOT NULL,
                    `scheduledAtEpochMillis` INTEGER NOT NULL,
                    `startedAtEpochMillis` INTEGER,
                    `finishedAtEpochMillis` INTEGER,
                    `status` TEXT NOT NULL,
                    `executionMode` TEXT,
                    `errorCode` TEXT,
                    `message` TEXT
                )
                """.trimIndent(),
            )
            stmt.execute(
                """
                CREATE TABLE `app_aliases` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `alias` TEXT NOT NULL,
                    `packageName` TEXT NOT NULL,
                    `activityName` TEXT,
                    `source` TEXT NOT NULL
                )
                """.trimIndent(),
            )
            stmt.execute(
                """
                CREATE TABLE `templates` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `description` TEXT NOT NULL,
                    `category` TEXT NOT NULL,
                    `configJson` TEXT NOT NULL,
                    `usageCount` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }
    }

    private fun columns(conn: Connection, table: String): Set<String> {
        val result = mutableSetOf<String>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                while (rs.next()) {
                    result += rs.getString("name")
                }
            }
        }
        return result
    }

    private fun supportDatabase(conn: Connection): SupportSQLiteDatabase {
        val handler = InvocationHandler { _, method, args ->
            when (method.name) {
                "execSQL" -> {
                    val sql = args?.getOrNull(0) as? String
                    if (sql != null) {
                        conn.createStatement().use { it.execute(sql) }
                    }
                    null
                }
                "close" -> {
                    conn.close()
                    null
                }
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    java.lang.Double.TYPE -> 0.0
                    else -> null
                }
            }
        }
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
            handler,
        ) as SupportSQLiteDatabase
    }
}
