package com.voiceconfig.app.agent

import com.voiceconfig.data.local.entity.AgentRunRecordEntity
import com.voiceconfig.data.local.repository.AgentRunRecordRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
    fun observeRecords(limit: Int = 100): Flow<List<AgentRunRecord>> =
        kotlinx.coroutines.flow.flowOf(all().takeLast(limit))
}

@Singleton
class InMemoryAgentRunLedger @Inject constructor() : AgentRunLedger {
    private val records = CopyOnWriteArrayList<AgentRunRecord>()
    private val _records = MutableStateFlow<List<AgentRunRecord>>(emptyList())

    override fun record(record: AgentRunRecord) {
        records.add(record)
        // 内存防止无限增长：最多保留最近 500 条。
        while (records.size > 500) {
            records.removeAt(0)
        }
        _records.value = records.toList()
    }

    override fun latest(): AgentRunRecord? = records.lastOrNull()

    override fun all(): List<AgentRunRecord> = records.toList()

    override fun observeRecords(limit: Int): Flow<List<AgentRunRecord>> =
        _records.map { it.takeLast(limit) }
}

/**
 * 持久化运行记录。
 *
 * 同步保留内存态供 AgentSession 立即读取，同时异步写入 Room，
 * 进程重启后可通过 observeRecords 从数据库恢复。
 */
@Singleton
class PersistentAgentRunLedger @Inject constructor(
    private val repository: AgentRunRecordRepository,
) : AgentRunLedger {
    private val memory = InMemoryAgentRunLedger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun record(record: AgentRunRecord) {
        memory.record(record)
        val entity = record.toEntity()
        scope.launch {
            runCatching { repository.save(entity) }
        }
    }

    override fun latest(): AgentRunRecord? = memory.latest()

    override fun all(): List<AgentRunRecord> = memory.all()

    override fun observeRecords(limit: Int): Flow<List<AgentRunRecord>> =
        repository.observeRecent(limit).map { entities ->
            entities.map { it.toRunRecord() }.ifEmpty { memory.all().takeLast(limit) }
        }
}

fun AgentRunRecord.toEntity(): AgentRunRecordEntity {
    return AgentRunRecordEntity(
        runId = runId,
        userText = userText,
        ok = ok,
        state = state.name,
        message = message,
        toolCallsJson = toolCalls.joinToString("\n"),
        durationMs = durationMs,
        startedAtEpochMillis = startedAtMs,
        finishedAtEpochMillis = finishedAtMs,
        waitingForHuman = waitingForHuman,
        verified = verified,
    )
}

fun AgentRunRecordEntity.toRunRecord(): AgentRunRecord {
    val calls = toolCallsJson
        .split("\n")
        .map { it.trim() }
        .filter { it.isNotBlank() }
    return AgentRunRecord(
        runId = runId,
        userText = userText,
        ok = ok,
        state = runCatching { AgentRunState.valueOf(state) }.getOrDefault(AgentRunState.DONE),
        message = message,
        toolCalls = calls,
        durationMs = durationMs,
        startedAtMs = startedAtEpochMillis,
        finishedAtMs = finishedAtEpochMillis,
        waitingForHuman = waitingForHuman,
        verified = verified,
    )
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
        verified = computeVerified(toolCalls, toolResults),
    )
}

fun computeVerified(
    toolCalls: List<ToolCall>,
    toolResults: List<ToolResult>,
): Boolean? {
    var anyRequired = false
    var allValid = true
    toolCalls.forEachIndexed { index, call ->
        val spec = AgentVerificationMatrix.specFor(call.tool)
        val requiresEvidence = spec.requirement == VerificationRequirement.FOREGROUND ||
            spec.requirement == VerificationRequirement.TASK_CREATED
        if (!requiresEvidence) return@forEachIndexed
        anyRequired = true
        val result = toolResults.getOrNull(index)
        val evidence = result?.data?.get(spec.evidenceField)
        val valid = result?.ok == true && evidence == true
        if (!valid) allValid = false
    }
    return if (!anyRequired) null else allValid
}
