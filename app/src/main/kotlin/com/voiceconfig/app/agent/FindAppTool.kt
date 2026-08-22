package com.voiceconfig.app.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 根据关键词查找已安装应用，返回包名和名称。
 * 参数：{"keyword":"瑞幸"}
 *
 * 除了按系统应用标签/包名搜索外，还内置常见中文应用别名映射，
 * 避免因为 MIUI/Android 包可见性限制导致找不到瑞幸、微信等常用 App。
 */
@Singleton
class FindAppTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : AgentTool {

    override val name: String = "find_app"
    override val description: String = "按名称/包名关键词查找已安装应用，参数：{\"keyword\":\"瑞幸\"}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val keyword = args["keyword"]?.toString()?.trim()?.ifBlank { null }
            ?: return ToolResult.failure("缺少参数 keyword")
        val pm = context.packageManager

        val apps = mutableListOf<Map<String, String>>()
        val seen = mutableSetOf<String>()

        // 1. 首选：Launcher 可启动应用。这是用户真正能看到的 App，标签最准确。
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        runCatching {
            pm.queryIntentActivities(launcherIntent, 0).forEach { resolve ->
                val pkg = resolve.activityInfo?.packageName ?: return@forEach
                val label = resolve.loadLabel(pm)?.toString()?.trim().orEmpty()
                if ((label.contains(keyword, ignoreCase = true) || pkg.contains(keyword, ignoreCase = true)) && seen.add(pkg)) {
                    apps += mapOf("name" to label.ifBlank { pkg }, "package" to pkg)
                }
            }
        }

        // 2. 次选：系统 ApplicationInfo 标签/包名搜索（覆盖设置、联系人等非 Launcher 入口应用）。
        pm.getInstalledApplications(PackageManager.GET_META_DATA).forEach { info ->
            val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrNull() ?: return@forEach
            if (label.contains(keyword, ignoreCase = true) || info.packageName.contains(keyword, ignoreCase = true)) {
                if (seen.add(info.packageName)) {
                    apps += mapOf("name" to label, "package" to info.packageName)
                }
            }
        }

        // 3. 兜底：内置常见别名映射，仅在上述本机搜索没能找到时使用。
        if (apps.isEmpty()) {
            KNOWN_APPS.forEach { (alias, packages) ->
                if (keyword == alias || keyword.contains(alias) || alias.contains(keyword)) {
                    packages.forEach { pkg ->
                        if (seen.add(pkg) && isInstalled(pm, pkg)) {
                            apps += mapOf("name" to alias, "package" to pkg)
                        }
                    }
                }
            }
        }

        val result = apps.take(20)
        return if (result.isEmpty()) {
            ToolResult.failure("没有找到包含“$keyword”的应用")
        } else {
            val summary = result.joinToString("；") { "${it["name"]} -> ${it["package"]}" }
            ToolResult.success(
                "找到 ${result.size} 个应用：$summary",
                mapOf("apps" to result),
            )
        }
    }

    private fun isInstalled(pm: PackageManager, packageName: String): Boolean =
        runCatching { pm.getPackageInfo(packageName, 0) }.isSuccess

    companion object {
        private val KNOWN_APPS = linkedMapOf(
            "设置" to listOf("com.android.settings"),
            "settings" to listOf("com.android.settings"),
            "时钟" to listOf("com.android.deskclock"),
            "clock" to listOf("com.android.deskclock"),
            "日历" to listOf("com.google.android.calendar"),
            "calendar" to listOf("com.google.android.calendar"),
            "联系人" to listOf("com.android.contacts"),
            "contacts" to listOf("com.android.contacts"),
            "相机" to listOf("com.android.camera", "com.android.camera2"),
            "camera" to listOf("com.android.camera", "com.android.camera2"),
            "文件" to listOf("com.android.documentsui", "com.google.android.documentsui"),
            "浏览器" to listOf("com.android.chrome", "com.android.browser"),
            "chrome" to listOf("com.android.chrome"),
            "瑞幸" to listOf("com.lucky.luckyclient", "com.luckin.coffee"),
            "微信" to listOf("com.tencent.mm"),
            "企业微信" to listOf("com.tencent.wework"),
            "钉钉" to listOf("com.alibaba.android.rimet"),
            "飞书" to listOf("com.ss.android.lark"),
            "支付宝" to listOf("com.eg.android.AlipayGphone"),
            "淘宝" to listOf("com.taobao.taobao"),
            "京东" to listOf("com.jingdong.app.mall"),
            "抖音" to listOf("com.ss.android.ugc.aweme"),
            "原神" to listOf("com.miHoYo.Yuanshen"),
            "美团" to listOf("com.sankuai.meituan"),
            "饿了么" to listOf("me.ele"),
        )
    }
}
