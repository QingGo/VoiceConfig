package com.voiceconfig.app.agent

import com.voiceconfig.data.local.repository.RemoteProjectRecord
import com.voiceconfig.data.local.repository.RemoteProjectRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 远程项目抽象工具。
 *
 * 让 Agent 不再手动拼“cd /path && ./gradlew build”，而是：
 * 1. 先 remote_project_inspect 自动识别项目类型和可用命令；
 * 2. 再 remote_project_build / test / install 执行。
 */
enum class RemoteProjectType {
    GRADLE,
    NODE,
    PYTHON,
    GO,
    GENERIC,
    UNKNOWN,
}

data class RemoteProjectInfo(
    val type: RemoteProjectType,
    val buildCommand: String?,
    val testCommand: String?,
    val installCommand: String?,
)

object RemoteProjectDetector {

    fun detect(stdout: String): RemoteProjectInfo {
        val text = stdout.lowercase()
        return when {
            "gradle" in text -> RemoteProjectInfo(
                type = RemoteProjectType.GRADLE,
                buildCommand = "./gradlew assembleDebug",
                testCommand = "./gradlew test",
                installCommand = "./gradlew installDebug",
            )
            "node" in text -> RemoteProjectInfo(
                type = RemoteProjectType.NODE,
                buildCommand = "npm run build",
                testCommand = "npm test",
                installCommand = "npm install",
            )
            "python" in text -> RemoteProjectInfo(
                type = RemoteProjectType.PYTHON,
                buildCommand = "python -m build",
                testCommand = "python -m pytest",
                installCommand = "pip install -r requirements.txt",
            )
            "go" in text -> RemoteProjectInfo(
                type = RemoteProjectType.GO,
                buildCommand = "go build ./...",
                testCommand = "go test ./...",
                installCommand = "go mod download",
            )
            "generic" in text -> RemoteProjectInfo(
                type = RemoteProjectType.GENERIC,
                buildCommand = null,
                testCommand = null,
                installCommand = null,
            )
            else -> RemoteProjectInfo(
                type = RemoteProjectType.UNKNOWN,
                buildCommand = null,
                testCommand = null,
                installCommand = null,
            )
        }
    }
}

@Singleton
class RemoteProjectInspectTool @Inject constructor(
    private val service: RemoteSshAgentService,
    private val repository: RemoteProjectRepository,
) : AgentTool {
    override val name: String = "remote_project_inspect"
    override val description: String =
        "自动识别远程项目类型并返回构建/测试/安装命令。参数：{\"host\":\"节点名或IP，可省略\",\"path\":\"/远程项目根目录\"}"
    override val metadata: AgentToolMetadata = AgentToolMetadata(
        category = "远程开发",
        group = ToolGroup.REMOTE,
        risk = ToolRisk.READ_ONLY,
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val path = (args["path"]?.toString()?.trim().orEmpty())
            .ifBlank { return ToolResult.failure("缺少参数 path") }
        val result = service.exec(args["host"]?.toString(), probeCommand(path))
        if (!result.ok) return result
        val stdout = result.data["stdout"]?.toString().orEmpty()
        val type = stdout.lines().lastOrNull()?.trim().orEmpty()
        val info = RemoteProjectDetector.detect(type)
        if (info.type == RemoteProjectType.UNKNOWN) {
            return ToolResult.failure(
                "未能识别 $path 的项目类型，请确认路径存在",
                mapOf("host" to result.data["host"], "path" to path, "detected" to type),
            )
        }
        val host = result.data["host"]?.toString() ?: args["host"]?.toString() ?: "default"
        val projectId = repository.getByPath(path, host)?.projectId
            ?: "rp_${host}_${path.hashCode()}"
        val name = path.trimEnd('/').substringAfterLast('/').ifBlank { path }
        runCatching {
            repository.save(
                RemoteProjectRecord(
                    projectId = projectId,
                    nodeHost = host,
                    name = name,
                    rootPath = path,
                    repoType = info.type.name,
                    buildCommand = info.buildCommand,
                    testCommand = info.testCommand,
                    installCommand = info.installCommand,
                ),
            )
        }
        return ToolResult.success(
            "已识别远程项目：${info.type}（$path），已保存 projectId=$projectId",
            mapOf(
                "host" to host,
                "path" to path,
                "projectId" to projectId,
                "projectType" to info.type.name,
                "buildCommand" to info.buildCommand,
                "testCommand" to info.testCommand,
                "installCommand" to info.installCommand,
                "detected" to type,
            ),
        )
    }
}

@Singleton
class RemoteProjectBuildTool @Inject constructor(
    private val service: RemoteSshAgentService,
    private val repository: RemoteProjectRepository,
) : AgentTool {
    override val name: String = "remote_project_build"
    override val description: String =
        "构建远程项目。参数：{\"host\":\"节点名或IP，可省略\",\"path\":\"/远程项目根目录\",\"projectId\":\"已保存项目ID，可省略\",\"command\":\"可选，覆盖自动识别的构建命令\"}"
    override val metadata: AgentToolMetadata = AgentToolMetadata(
        category = "远程开发",
        group = ToolGroup.REMOTE,
        risk = ToolRisk.MEDIUM,
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val ctx = resolveProjectContext(repository, service, args)
            ?: return ToolResult.failure("缺少 path 或 projectId，或项目未保存")
        val explicit = args["command"]?.toString()?.trim()?.ifBlank { null }
        val command = explicit ?: ctx.info.buildCommand
            ?: return ToolResult.failure("无法确定该项目的构建命令，请先 remote_project_inspect 或显式传 command")
        return service.exec(ctx.host, "cd ${shellSingleQuote(ctx.path)} && $command")
    }
}

@Singleton
class RemoteProjectTestTool @Inject constructor(
    private val service: RemoteSshAgentService,
    private val repository: RemoteProjectRepository,
) : AgentTool {
    override val name: String = "remote_project_test"
    override val description: String =
        "运行远程项目测试。参数：{\"host\":\"节点名或IP，可省略\",\"path\":\"/远程项目根目录\",\"projectId\":\"已保存项目ID，可省略\",\"command\":\"可选，覆盖自动识别的测试命令\"}"
    override val metadata: AgentToolMetadata = AgentToolMetadata(
        category = "远程开发",
        group = ToolGroup.REMOTE,
        risk = ToolRisk.MEDIUM,
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val ctx = resolveProjectContext(repository, service, args)
            ?: return ToolResult.failure("缺少 path 或 projectId，或项目未保存")
        val explicit = args["command"]?.toString()?.trim()?.ifBlank { null }
        val command = explicit ?: ctx.info.testCommand
            ?: return ToolResult.failure("无法确定该项目的测试命令，请先 remote_project_inspect 或显式传 command")
        return service.exec(ctx.host, "cd ${shellSingleQuote(ctx.path)} && $command")
    }
}

@Singleton
class RemoteProjectInstallTool @Inject constructor(
    private val service: RemoteSshAgentService,
    private val repository: RemoteProjectRepository,
) : AgentTool {
    override val name: String = "remote_project_install"
    override val description: String =
        "安装远程项目依赖（会影响远程环境，默认需人工确认）。参数：{\"host\":\"节点名或IP，可省略\",\"path\":\"/远程项目根目录\",\"projectId\":\"已保存项目ID，可省略\",\"command\":\"可选，覆盖自动识别的安装命令\"}"
    override val metadata: AgentToolMetadata = AgentToolMetadata(
        category = "远程开发",
        group = ToolGroup.REMOTE,
        risk = ToolRisk.HIGH,
        sensitive = true,
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val ctx = resolveProjectContext(repository, service, args)
            ?: return ToolResult.failure("缺少 path 或 projectId，或项目未保存")
        val explicit = args["command"]?.toString()?.trim()?.ifBlank { null }
        val command = explicit ?: ctx.info.installCommand
            ?: return ToolResult.failure("无法确定该项目的安装命令，请先 remote_project_inspect 或显式传 command")
        return service.exec(ctx.host, "cd ${shellSingleQuote(ctx.path)} && $command")
    }
}

private fun shellSingleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

private fun probeCommand(path: String): String {
    val safe = shellSingleQuote(path)
    return """
        if [ -f $safe/settings.gradle ] || [ -f $safe/build.gradle ] || [ -f $safe/settings.gradle.kts ] || [ -f $safe/build.gradle.kts ]; then
          echo gradle
        elif [ -f $safe/package.json ]; then
          echo node
        elif [ -f $safe/pyproject.toml ] || [ -f $safe/requirements.txt ] || [ -f $safe/setup.py ]; then
          echo python
        elif [ -f $safe/go.mod ]; then
          echo go
        elif [ -d $safe ]; then
          echo generic
        else
          echo unknown
        fi
    """.trimIndent()
}

private suspend fun detectInfo(
    service: RemoteSshAgentService,
    host: String?,
    path: String,
): RemoteProjectInfo {
    val result = service.exec(host, probeCommand(path))
    val stdout = result.data["stdout"]?.toString().orEmpty()
    return RemoteProjectDetector.detect(stdout.lines().lastOrNull()?.trim().orEmpty())
}

private data class ResolvedProject(
    val host: String?,
    val path: String,
    val info: RemoteProjectInfo,
)

private suspend fun resolveProjectContext(
    repository: RemoteProjectRepository,
    service: RemoteSshAgentService,
    args: Map<String, Any?>,
): ResolvedProject? {
    val projectId = args["projectId"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
    val saved = projectId?.let { repository.getByProjectId(it) }
    val host = saved?.nodeHost ?: args["host"]?.toString()
    val path = saved?.rootPath ?: args["path"]?.toString()?.trim().orEmpty()
    if (path.isBlank()) return null
    val info = if (saved != null) {
        RemoteProjectDetector.detect(saved.repoType)
    } else {
        detectInfo(service, host, path)
    }
    return ResolvedProject(host, path, info)
}
