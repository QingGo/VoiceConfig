package com.voiceconfig.app.executor

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.voiceconfig.core.executor.ExecutionChannel
import com.voiceconfig.core.executor.ExecutionRequest
import com.voiceconfig.core.executor.ExecutionResult
import com.voiceconfig.core.model.ExecutionMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 基于 Shizuku 的高级执行通道。
 *
 * 不直接依赖 Shizuku AAR，而是通过反射调用 `rikka.shizuku.Shizuku`：
 * - 如果设备未安装 Shizuku、服务未运行、或未授权，则 canExecute=false；
 * - 执行时通过 `Shizuku.newProcess` 在 shell 身份下运行 `am start`，尝试真正自动打开 App。
 *
 * 该通道属于“渐进增强”：不可用时 ExecutionEngine 会自动降级到通知/Deep Link。
 */
@Singleton
class ShizukuExecutionChannel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ExecutionChannel {

    override val supportedMode: ExecutionMode = ExecutionMode.SHIZUKU

    override fun canExecute(request: ExecutionRequest): Boolean {
        val ok = runCatching {
            val clazz = shizukuClass()
            if (clazz == null) {
                android.util.Log.d(TAG, "canExecute: Shizuku class not found")
                return false
            }
            val ping = clazz.getMethod("pingBinder").invoke(null) as? Boolean
            android.util.Log.d(TAG, "canExecute: pingBinder=$ping")
            if (ping != true) return false
            val permission = clazz.getMethod("checkSelfPermission").invoke(null) as? Int
            android.util.Log.d(TAG, "canExecute: checkSelfPermission=$permission (granted=${PackageManager.PERMISSION_GRANTED})")
            permission == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        android.util.Log.d(TAG, "canExecute result=$ok")
        return ok
    }

    override fun unavailableReason(request: ExecutionRequest): String? = runCatching {
        val clazz = shizukuClass()
        if (clazz == null) return "Shizuku 未安装"
        val ping = clazz.getMethod("pingBinder").invoke(null) as? Boolean
        if (ping != true) return "Shizuku 服务未运行或 Binder 不可用"
        val permission = clazz.getMethod("checkSelfPermission").invoke(null) as? Int
        if (permission != PackageManager.PERMISSION_GRANTED) return "Shizuku 未授权言控"
        null
    }.getOrElse { "Shizuku 检测异常：${it.message}" }

    override fun execute(request: ExecutionRequest): ExecutionResult {
        val cmd = buildCommand(request) ?: return ExecutionResult.failure(
            mode = supportedMode,
            errorCode = "NO_TARGET",
            message = "任务缺少可打开的 App 或 Deep Link",
        )
        return try {
            val process = newProcess(cmd)
                ?: run {
                    android.util.Log.e(TAG, "execute: Shizuku.newProcess returned null, cmd=${cmd.joinToString(" ")}")
                    return ExecutionResult.failure(
                        mode = supportedMode,
                        errorCode = "SHIZUKU_NEW_PROCESS_UNAVAILABLE",
                        message = "无法调用 Shizuku.newProcess",
                    )
                }
            val exitCode = runCatching {
                process.javaClass.getMethod("waitFor").invoke(process) as Int
            }.getOrElse { -1 }
            val errorOutput = readErrorOutput(process)
            // `am start` prints "Warning: Activity not started, its current task has
            // been brought to the front" when the target app is already running and
            // only needs to be brought to the foreground. That is a success for us.
            val launchFailed = isLaunchFailure(errorOutput)
            if (exitCode == 0 && !launchFailed) {
                val verification = verifyForeground(request.task)
                ExecutionResult.success(supportedMode).copy(
                    message = verification?.takeIf { it.isNotBlank() },
                )
            } else {
                val failureMessage = if (launchFailed) errorOutput.trim().ifBlank { "启动目标 App 失败" } else "Shizuku 执行失败，exit=$exitCode"
                android.util.Log.e(TAG, "execute failed: exit=$exitCode, error=$errorOutput")
                ExecutionResult.failure(
                    mode = supportedMode,
                    errorCode = if (launchFailed) "SHIZUKU_LAUNCH_FAILED" else "SHIZUKU_EXIT_$exitCode",
                    message = failureMessage,
                )
            }
        } catch (e: Exception) {
            ExecutionResult.failure(
                mode = supportedMode,
                errorCode = "SHIZUKU_EXECUTION_FAILED",
                message = e.message,
            )
        }
    }

    private fun buildCommand(request: ExecutionRequest): Array<String>? {
        val task = request.task
        val deepLink = task.deepLink
        if (!deepLink.isNullOrBlank()) {
            return arrayOf(
                "am", "start",
                "-a", "android.intent.action.VIEW",
                "-d", deepLink,
            )
        }
        val targetPackage = task.targetPackage
        if (targetPackage != null) {
            val component = resolveLaunchComponent(targetPackage, task.targetActivity)
            if (component != null) {
                return arrayOf(
                    "am", "start",
                    "-n", component.flattenToShortString(),
                )
            }
            return arrayOf(
                "am", "start",
                "-a", "android.intent.action.MAIN",
                "-c", "android.intent.category.LAUNCHER",
                "-p", targetPackage,
            )
        }
        return null
    }

    private fun resolveLaunchComponent(packageName: String, targetActivity: String?): ComponentName? {
        if (!targetActivity.isNullOrBlank()) {
            val explicit = ComponentName(packageName, targetActivity)
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

    private fun readErrorOutput(process: Any): String = runCatching {
        val method = process.javaClass.getMethod("getErrorStream")
        val stream = method.invoke(process) as? java.io.InputStream ?: return ""
        stream.bufferedReader().use { it.readText() }
    }.getOrDefault("")

    private fun readOutput(process: Any): String = runCatching {
        val method = process.javaClass.getMethod("getInputStream")
        val stream = method.invoke(process) as? java.io.InputStream ?: return ""
        stream.bufferedReader().use { it.readText() }
    }.getOrDefault("")

    private fun verifyForeground(task: com.voiceconfig.core.model.Task): String? {
        val targetPackage = task.targetPackage ?: return null
        if (targetPackage.isBlank()) return null
        // 给 Activity 一点启动时间，再读取当前前台 Activity。
        val verifyCmd = arrayOf(
            "sh", "-c",
            "sleep 1; dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity|ResumedActivity' | head -n 8",
        )
        val process = newProcess(verifyCmd) ?: return null
        val exitCode = runCatching {
            process.javaClass.getMethod("waitFor").invoke(process) as Int
        }.getOrElse { -1 }
        if (exitCode != 0) return null
        val output = readOutput(process)
        val currentPackage = parseForegroundPackage(output)
        return if (currentPackage == targetPackage) {
            "已验证前台为 $targetPackage"
        } else if (currentPackage != null) {
            "已发出启动命令，但当前前台为 $currentPackage（目标 $targetPackage）"
        } else {
            "已发出启动命令，未能确认前台 Activity"
        }
    }

    private fun parseForegroundPackage(output: String): String? {
        val pattern = Regex("""(?:topResumedActivity|mResumedActivity|ResumedActivity)[^/]*\s+([^/\s]+)""")
        return pattern.find(output)?.groupValues?.getOrNull(1)
    }

    private fun shizukuClass(): Class<*>? =
        runCatching { Class.forName("rikka.shizuku.Shizuku") }.getOrNull()

    private fun newProcess(cmd: Array<String>): Any? {
        val clazz = shizukuClass() ?: return null
        val method = runCatching {
            clazz.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
        }.getOrNull() ?: clazz.declaredMethods.firstOrNull { it.name == "newProcess" } ?: return null
        method.isAccessible = true
        return method.invoke(null, cmd, null, null)
    }

    companion object {
        private const val TAG = "ShizukuExec"

        /**
         * `am start` prints warnings like:
         * - "Warning: Activity not started, its current task has been brought to the front"
         * - "Warning: Activity not started, intent has been delivered to currently running top-most instance."
         * when the target app is already running and only needs to be brought to the
         * foreground / receive the intent. Those are successes for us.
         */
        internal fun isLaunchFailure(errorOutput: String): Boolean {
            val hasRealError = errorOutput.contains("Error", ignoreCase = true) ||
                errorOutput.contains("does not exist", ignoreCase = true) ||
                errorOutput.contains("unable to resolve", ignoreCase = true)
            if (hasRealError) return true
            val isActivityNotStartedWarning = errorOutput.contains("Activity not started", ignoreCase = true) &&
                errorOutput.contains("Warning:", ignoreCase = true)
            return errorOutput.contains("Activity not started", ignoreCase = true) && !isActivityNotStartedWarning
        }
    }
}
