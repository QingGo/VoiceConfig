package com.voiceconfig.app.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.voiceconfig.app.executor.ShizukuExecutionChannel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.delay
import javax.inject.Singleton

/**
 * 打开 App / Deep Link / 指定 Activity。
 *
 * 优先走 Shizuku shell `am start`（可后台自动打开），失败或未授权时降级为
 * 普通 Intent 启动（需用户当前在前台/有 Activity 上下文）。
 */
@Singleton
class OpenAppTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizuku: ShizukuCommandRunner,
    private val dismissPopupsTool: DismissPopupsTool,
) : AgentTool {

    override val name: String = "open_app"
    override val description: String = "打开 App、Deep Link 或指定 Activity，参数：{\"package\":\"com.tencent.wework\",\"deepLink\":\"https://...\",\"activity\":\"com.example.MainActivity\"}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val packageName = args["package"]?.toString()?.ifBlank { null }
        val deepLink = args["deepLink"]?.toString()?.ifBlank { null }
        val activity = args["activity"]?.toString()?.ifBlank { null }

        if (packageName == null && deepLink == null) {
            return ToolResult.failure("缺少参数 package 或 deepLink")
        }

        val command = when {
            deepLink != null -> arrayOf(
                "am", "start", "-a", "android.intent.action.VIEW", "-d", deepLink,
            )
            packageName != null -> {
                val component = resolveLaunchComponent(packageName, activity)
                if (component != null) {
                    arrayOf("am", "start", "-n", component.flattenToShortString())
                } else {
                    arrayOf(
                        "am", "start",
                        "-a", "android.intent.action.MAIN",
                        "-c", "android.intent.category.LAUNCHER",
                        "-p", packageName,
                    )
                }
            }
            else -> null
        }

        if (command != null && shizuku.isAvailable()) {
            val result = shizuku.execute(*command)
            val failure = ShizukuExecutionChannel.isLaunchFailure(result.stderr)
            if (result.ok && !failure) {
                val verified = verifyForeground(packageName)
                val popup = tryDismissPopupsAfterOpen()
                return ToolResult.success(
                    if (verified) "已打开并确认 ${packageName ?: deepLink}${popup?.let { "；已自动关闭弹窗" } ?: ""}" else "已打开 ${packageName ?: deepLink}",
                    mapOf(
                        "mode" to "shizuku",
                        "package" to (packageName ?: ""),
                        "deepLink" to (deepLink ?: ""),
                        "verified" to verified,
                        "autoDismiss" to (popup ?: emptyMap<String, Any?>()),
                    ),
                )
            }
            if (!result.stderr.isBlank() && failure) {
                return ToolResult.failure("Shizuku 启动失败：${result.stderr.trim()}", mapOf("mode" to "shizuku"))
            }
            // Shizuku 命令成功但输出异常（未读 stderr）时继续尝试 Intent 降级。
        }

        return try {
            val intent = when {
                deepLink != null -> Intent(Intent.ACTION_VIEW, Uri.parse(deepLink))
                packageName != null -> {
                    val launchIntent = if (!activity.isNullOrBlank()) {
                        runCatching { context.packageManager.getLaunchIntentForPackage(packageName) }
                            .getOrNull()
                            ?.setClassName(packageName, activity)
                            ?: Intent().setClassName(packageName, activity)
                    } else {
                        context.packageManager.getLaunchIntentForPackage(packageName)
                    } ?: Intent(Intent.ACTION_MAIN).apply {
                        setPackage(packageName)
                        addCategory(Intent.CATEGORY_LAUNCHER)
                    }
                    launchIntent
                }
                else -> return ToolResult.failure("缺少可打开的地址")
            }.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val popup = tryDismissPopupsAfterOpen()
            ToolResult.success(
                "已打开 ${packageName ?: deepLink}${popup?.let { "；已自动关闭弹窗" } ?: ""}",
                mapOf("mode" to "intent", "autoDismiss" to (popup ?: emptyMap<String, Any?>())),
            )
        } catch (e: Exception) {
            ToolResult.failure("打开失败：${e.message}", mapOf("mode" to "intent"))
        }
    }

    private suspend fun tryDismissPopupsAfterOpen(): Map<String, Any?>? {
        if (!shizuku.isAvailable()) return null
        delay(300)
        val result = runCatching {
            dismissPopupsTool.dismissFast()
        }.getOrNull() ?: return null
        return if (result.ok) {
            mapOf(
                "ok" to true,
                "message" to result.message,
                "actions" to (result.data["actions"] ?: emptyList<Any?>()),
            )
        } else {
            null
        }
    }

    private suspend fun verifyForeground(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return true
        repeat(3) {
            delay(500)
            val check = shizuku.execute("dumpsys", "activity", "activities")
            if (check.ok && check.stdout.contains(packageName)) return true
        }
        return false
    }

    private fun resolveLaunchComponent(packageName: String, activity: String?): android.content.ComponentName? {
        if (!activity.isNullOrBlank()) {
            val explicit = android.content.ComponentName(packageName, activity)
            val exists = runCatching {
                context.packageManager.getActivityInfo(explicit, 0)
                true
            }.getOrDefault(false)
            if (exists) return explicit
        }
        return runCatching {
            context.packageManager.getLaunchIntentForPackage(packageName)?.component
        }.getOrNull()
    }
}
