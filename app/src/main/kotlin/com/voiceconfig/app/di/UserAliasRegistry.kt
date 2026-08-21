package com.voiceconfig.app.di

import com.voiceconfig.core.model.AppAlias
import com.voiceconfig.core.model.AppAlias.AliasSource
import com.voiceconfig.data.local.dao.AppAliasDao
import com.voiceconfig.data.local.mapper.AppAliasMapper
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Singleton
class UserAliasRegistry @Inject constructor(
    private val appAliasDao: AppAliasDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val aliases = AtomicReference<Map<String, AppAlias>>(emptyMap())

    init {
        refresh()
    }

    fun current(): Map<String, AppAlias> = aliases.get()

    fun add(alias: String, packageName: String, activityName: String? = null) {
        val normalized = alias.trim()
        if (normalized.isBlank() || packageName.isBlank()) return
        val appAlias = AppAlias(
            alias = normalized,
            packageName = packageName,
            activityName = activityName,
            source = AliasSource.USER,
        )
        aliases.set(aliases.get() + (normalized to appAlias))
        scope.launch {
            runCatching {
                if (appAliasDao.findByAlias(normalized) == null) {
                    appAliasDao.insert(AppAliasMapper.toEntity(appAlias))
                }
            }
        }
    }

    private fun refresh() {
        scope.launch {
            runCatching {
                val loaded = appAliasDao.observeAll().first()
                    .associate { it.alias to AppAliasMapper.toDomain(it) }
                aliases.set(loaded)
            }
        }
    }
}
