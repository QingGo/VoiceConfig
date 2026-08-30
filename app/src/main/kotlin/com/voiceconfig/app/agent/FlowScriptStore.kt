package com.voiceconfig.app.agent

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * FlowScript 持久化抽象，便于测试和替换存储。
 */
interface FlowScriptStorage {
    fun readCustom(): List<FlowScript>
    fun writeCustom(scripts: List<FlowScript>)
}

/**
 * 基于 SharedPreferences 的 FlowScript 存储。
 */
class SharedPreferencesFlowScriptStorage @Inject constructor(
    @ApplicationContext context: Context,
) : FlowScriptStorage {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("flow_scripts", Context.MODE_PRIVATE)

    override fun readCustom(): List<FlowScript> {
        val raw = prefs.getString(KEY_CUSTOM, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                FlowScriptCodec.fromJson(arr.getJSONObject(i))?.let { add(it) }
            }
        }
    }

    override fun writeCustom(scripts: List<FlowScript>) {
        val arr = JSONArray()
        scripts.forEach { arr.put(FlowScriptCodec.toJson(it)) }
        prefs.edit().putString(KEY_CUSTOM, arr.toString()).apply()
    }

    private companion object {
        const val KEY_CUSTOM = "custom_flow_scripts"
    }
}

/**
 * FlowScript 仓库：内置 + 用户自定义。
 *
 * - 内置脚本直接来自 [BuiltinFlowScripts]，不可被删除；
 * - 外部导入的脚本默认 PENDING 且 disabled，需审核后启用；
 * - 导出/导入使用统一的 JSON 格式和版本校验。
 */
@Singleton
class FlowScriptStore @Inject constructor(
    private val storage: FlowScriptStorage,
) {

    private val _flows = MutableStateFlow<List<FlowScript>>(emptyList())
    val flows: StateFlow<List<FlowScript>> = _flows.asStateFlow()

    init {
        refresh()
    }

    fun all(): List<FlowScript> = BuiltinFlowScripts.all + storage.readCustom()

    fun observe(): StateFlow<List<FlowScript>> = _flows

    fun get(id: String): FlowScript? = all().firstOrNull { it.id == id }

    fun findByName(name: String): FlowScript? =
        all().firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun approvedEnabled(): List<FlowScript> = all().filter {
        it.status == FlowScriptStatus.APPROVED && it.enabled
    }

    /**
     * 保存用户自定义脚本。内置脚本不可覆盖。
     * 返回 null 表示校验未通过。
     */
    fun save(script: FlowScript): FlowScript? {
        val errors = FlowScriptCodec.validate(script)
        if (errors.isNotEmpty()) return null
        if (script.source == "builtin" || BuiltinFlowScripts.all.any { it.id == script.id }) {
            return null
        }
        val now = System.currentTimeMillis()
        val normalized = script.copy(
            schemaVersion = FlowScriptCodec.CURRENT_SCHEMA_VERSION,
            source = script.source.ifBlank { "user" },
            updatedAtMs = now,
            createdAtMs = if (script.createdAtMs == 0L) now else script.createdAtMs,
        )
        val custom = storage.readCustom()
        val updated = if (custom.any { it.id == normalized.id }) {
            custom.map { if (it.id == normalized.id) normalized else it }
        } else {
            custom + normalized
        }
        storage.writeCustom(updated)
        refresh()
        return normalized
    }

    /**
     * 从 JSON 导入（支持单个对象或对象数组）。导入后始终为 PENDING + disabled，必须人工审核。
     */
    fun importJson(json: String, source: String = "import"): FlowScript? {
        val obj = runCatching { JSONObject(json) }.getOrNull()
        if (obj != null) return importSingle(obj, source)
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return null
        var first: FlowScript? = null
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val imported = importSingle(item, source) ?: continue
            if (first == null) first = imported
        }
        return first
    }

    private fun importSingle(obj: JSONObject, source: String): FlowScript? {
        val parsed = FlowScriptCodec.fromJson(obj) ?: return null
        if (!FlowScriptCodec.isValid(parsed)) return null
        val now = System.currentTimeMillis()
        val uniqueId = uniqueCustomId(parsed.id)
        val imported = parsed.copy(
            id = uniqueId,
            name = parsed.name,
            version = 1,
            schemaVersion = FlowScriptCodec.CURRENT_SCHEMA_VERSION,
            status = FlowScriptStatus.PENDING,
            enabled = false,
            source = source,
            createdAtMs = now,
            updatedAtMs = now,
        )
        return save(imported)
    }

    fun approve(id: String): Boolean = setStatus(id, FlowScriptStatus.APPROVED)

    fun reject(id: String): Boolean = setStatus(id, FlowScriptStatus.REJECTED)

    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val script = get(id) ?: return false
        if (script.source == "builtin") return false
        val updated = script.copy(enabled = enabled, updatedAtMs = System.currentTimeMillis())
        return save(updated) != null
    }

    fun delete(id: String): Boolean {
        val script = get(id) ?: return false
        if (script.source == "builtin") return false
        storage.writeCustom(storage.readCustom().filterNot { it.id == id })
        refresh()
        return true
    }

    fun exportJson(id: String): String? =
        get(id)?.let { FlowScriptCodec.toJsonString(it) }

    fun exportAllJson(): String {
        val arr = JSONArray()
        all().forEach { arr.put(FlowScriptCodec.toJson(it)) }
        return arr.toString()
    }

    private fun setStatus(id: String, status: FlowScriptStatus): Boolean {
        val script = get(id) ?: return false
        if (script.source == "builtin") return false
        val updated = script.copy(
            status = status,
            enabled = if (status == FlowScriptStatus.APPROVED) true else script.enabled,
            updatedAtMs = System.currentTimeMillis(),
        )
        return save(updated) != null
    }

    private fun uniqueCustomId(base: String): String {
        val existing = all().map { it.id }.toSet()
        if (base !in existing) return base
        var index = 2
        while ("${base}_import_$index" in existing) index++
        return "${base}_import_$index"
    }

    private fun refresh() {
        _flows.value = all()
    }
}
