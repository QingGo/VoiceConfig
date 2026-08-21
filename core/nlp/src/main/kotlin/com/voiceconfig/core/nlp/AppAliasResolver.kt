package com.voiceconfig.core.nlp

import com.voiceconfig.core.model.AppAlias
import com.voiceconfig.core.model.AppAlias.AliasSource

/**
 * 将用户口语中的 App 名称解析为 packageName。
 * 优先级：用户自定义 > 已安装应用 label 匹配 > 内置别名。
 */
class AppAliasResolver(
    private val builtinAliases: Map<String, AppAlias> = defaultBuiltinAliases(),
    private val userAliasesProvider: () -> Map<String, AppAlias> = { emptyMap() },
    private val installedAppsProvider: () -> List<InstalledApp> = { emptyList() },
) {
    private val userAliases: MutableMap<String, AppAlias> = userAliasesProvider().toMutableMap()

    fun addUserAlias(alias: String, packageName: String, activityName: String? = null) {
        val normalized = alias.trim()
        if (normalized.isBlank() || packageName.isBlank()) return
        userAliases[normalized] = AppAlias(
            alias = normalized,
            packageName = packageName,
            activityName = activityName,
            source = AliasSource.USER,
        )
    }

    fun resolve(alias: String): ResolvedApp? {
        userAliases.putAll(userAliasesProvider())
        val normalized = alias.trim()
        if (normalized.isBlank()) return null
        userAliases[normalized]?.let { return it.toResolvedApp() }

        // 1. 内置别名精确匹配优先，避免“微信”被“微信听书”抢走
        builtinAliases[normalized]?.let { return it.toResolvedApp() }
        builtinAliases.entries.firstOrNull { (key, _) -> key.equals(normalized, ignoreCase = true) }
            ?.value
            ?.let { return it.toResolvedApp() }

        // 2. 已安装应用精确匹配
        val installed = installedAppsProvider()
        installed.firstOrNull { it.label.equals(normalized, ignoreCase = true) }
            ?.let { return ResolvedApp(packageName = it.packageName, activityName = it.activityName, source = AliasSource.LEARNED) }

        // 3. 内置别名包含匹配（如“企业微信”“企业薇信”）
        builtinAliases.entries.firstOrNull { (key, _) -> normalized.contains(key) || key.contains(normalized) }
            ?.value
            ?.let { return it.toResolvedApp() }

        // 4. 已安装应用包含匹配（最后兜底）
        installed.firstOrNull { it.label.contains(normalized, ignoreCase = true) }
            ?.let { return ResolvedApp(packageName = it.packageName, activityName = it.activityName, source = AliasSource.LEARNED) }

        return null
    }

    private fun AppAlias.toResolvedApp(): ResolvedApp = ResolvedApp(
        packageName = packageName,
        activityName = activityName,
        source = source,
    )

    data class ResolvedApp(
        val packageName: String,
        val activityName: String? = null,
        val source: AliasSource,
    )

    companion object {
        fun defaultBuiltinAliases(): Map<String, AppAlias> = listOf(
            AppAlias(alias = "企业微信", packageName = "com.tencent.wework", activityName = "com.tencent.wework.launch.LaunchSplashActivity"),
            AppAlias(alias = "微信", packageName = "com.tencent.mm", activityName = "com.tencent.mm.ui.LauncherUI"),
            AppAlias(alias = "钉钉", packageName = "com.alibaba.android.rimet"),
            AppAlias(alias = "支付宝", packageName = "com.eg.android.AlipayGphone"),
            AppAlias(alias = "高德地图", packageName = "com.autonavi.minimap"),
            AppAlias(alias = "百度地图", packageName = "com.baidu.BaiduMap"),
            AppAlias(alias = "淘宝", packageName = "com.taobao.taobao"),
            AppAlias(alias = "京东", packageName = "com.jingdong.app.mall"),
            AppAlias(alias = "抖音", packageName = "com.ss.android.ugc.aweme"),
            AppAlias(alias = "微博", packageName = "com.sina.weibo"),
            AppAlias(alias = "QQ", packageName = "com.tencent.mobileqq"),
            AppAlias(alias = "邮箱", packageName = "com.android.email"),
            AppAlias(alias = "时钟", packageName = "com.android.deskclock"),
            AppAlias(alias = "相机", packageName = "com.android.camera"),
        ).associateBy { it.alias }
    }
}
