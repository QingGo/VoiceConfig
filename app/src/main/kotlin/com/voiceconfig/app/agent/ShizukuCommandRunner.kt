package com.voiceconfig.app.agent

import android.content.pm.PackageManager
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 基于 Shizuku 的 shell 命令执行器。
 *
 * 通过反射调用 `rikka.shizuku.Shizuku`，避免把 Shizuku AAR 类型泄漏到工具层。
 * 所有 Agent 工具统一从这里获取 shell 能力；Shizuku 不可用时返回 [ShellResult] 失败。
 */
@Singleton
class ShizukuCommandRunner @Inject constructor() {

    fun isAvailable(): Boolean = runCatching {
        val clazz = shizukuClass() ?: return false
        val ping = clazz.getMethod("pingBinder").invoke(null) as? Boolean ?: return false
        if (!ping) return false
        val permission = clazz.getMethod("checkSelfPermission").invoke(null) as? Int
        permission == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * 执行命令，等待结束并返回 stdout/stderr。
     * 与 [runAndWait] 不同，此方法会同时读取 stdout 与 stderr。
     */
    fun execute(vararg command: String): ShellResult {
        if (!isAvailable()) {
            return ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = "Shizuku 不可用",
            )
        }
        return try {
            val process = newProcess(command.copyOf()) ?: return ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = "无法调用 Shizuku.newProcess",
            )
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val stdoutThread = Thread { appendStream(process, "getInputStream", stdout) }
            val stderrThread = Thread { appendStream(process, "getErrorStream", stderr) }
            stdoutThread.start()
            stderrThread.start()

            val exitCode = runCatching {
                process.javaClass.getMethod("waitFor").invoke(process) as Int
            }.getOrElse { -1 }

            stdoutThread.join(5_000)
            stderrThread.join(5_000)
            ShellResult(exitCode = exitCode, stdout = stdout.toString(), stderr = stderr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku execute failed", e)
            ShellResult(exitCode = -1, stdout = "", stderr = e.message ?: e.javaClass.simpleName)
        }
    }

    /** 只等待进程结束，不读取输出；用于 `input tap` 等无需输出的命令。 */
    fun executeAndWait(vararg command: String): ShellResult {
        if (!isAvailable()) {
            return ShellResult(-1, "", "Shizuku 不可用")
        }
        return try {
            val process = newProcess(command.copyOf()) ?: return ShellResult(-1, "", "无法调用 Shizuku.newProcess")
            val exitCode = runCatching {
                process.javaClass.getMethod("waitFor").invoke(process) as Int
            }.getOrElse { -1 }
            // 不读取流时也尽量消费，避免阻塞；但 Shizuku 进程通常量小。
            ShellResult(exitCode, "", "")
        } catch (e: Exception) {
            ShellResult(-1, "", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun appendStream(process: Any, methodName: String, target: StringBuilder) {
        runCatching {
            val stream = process.javaClass.getMethod(methodName).invoke(process) as? java.io.InputStream ?: return
            stream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    target.append(buffer, 0, read)
                }
            }
        }
    }

    private fun shizukuClass(): Class<*>? =
        runCatching { Class.forName("rikka.shizuku.Shizuku") }.getOrNull()

    private fun newProcess(cmd: Array<out String>): Any? {
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
        private const val TAG = "ShizukuRunner"
    }
}

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val ok: Boolean get() = exitCode == 0
    val output: String get() = stdout.trim().ifBlank { stderr.trim() }
}
