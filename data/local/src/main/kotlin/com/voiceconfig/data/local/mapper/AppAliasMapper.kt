package com.voiceconfig.data.local.mapper

import com.voiceconfig.core.model.AppAlias
import com.voiceconfig.data.local.entity.AppAliasEntity

object AppAliasMapper {
    fun toEntity(alias: AppAlias): AppAliasEntity = AppAliasEntity(
        id = alias.id,
        alias = alias.alias,
        packageName = alias.packageName,
        activityName = alias.activityName,
        source = alias.source,
    )

    fun toDomain(entity: AppAliasEntity): AppAlias = AppAlias(
        id = entity.id,
        alias = entity.alias,
        packageName = entity.packageName,
        activityName = entity.activityName,
        source = entity.source,
    )
}
