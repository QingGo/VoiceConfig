package com.voiceconfig.app.agent

import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统一运行记录。
 *
 * 这不是历史聊天消息，也不是执行日志表，而是“一次 Agent run”的结构化摘要：
 * 用户输入、最终状态、工具序列、验证、耗时、是否等待人类。
 * 后续可持久化、可查询、可回放、可生成评测指标。
 */
data class AgentRunRecord(
    val runId: String,
    val userText: String,
    val ok: Boolean,
    val state: AgentRunState,
    val message: String,
    val toolCalls: List<String>,
    val durationMs: Long,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val waitingForHuman: Boolean,
    val verified: Boolean? = null,
)

interface AgentRunLedger {
    fun record(record: AgentRunRecord)
    fun latest(): AgentRunRecord?
    fun all(): List<AgentRunRecord>
}

@Singleton
class InMemoryAgentRunLedger @Inject constructor() : AgentRunLedger {
    private val records = CopyOnWriteArrayList<AgentRunRecord>()

    override fun record(record: AgentRunRecord) {
        records.add(record)
        // 内存防止无限增长：最多保留最近 500 条。
        while (records.size > 500) {
            records.removeAt(0)
        }
    }

    override fun latest(): AgentRunRecord? = records.lastOrNull()

    override fun all(): List<AgentRunRecord> = records.toList()
}

fun AgentTurnResult.toRunRecord(userText: String): AgentRunRecord {
    val now = System.currentTimeMillis()
    return AgentRunRecord(
        runId = runId,
        userText = userText,
        ok = ok,
        state = state,
        message = message,
        toolCalls = toolCalls.map { it.tool },
        durationMs = durationMs,
        startedAtMs = now - durationMs,
        finishedAtMs = now,
        waitingForHuman = state == AgentRunState.WAITING_CONFIRM,
        // 详细 verified 仍需从 AgentTrace/tool_result 中提取，这里先留空，
        // 避免在没有实际证据时产生误导性记录。
        verified = null,
    )
}
