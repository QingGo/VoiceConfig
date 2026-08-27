package com.voiceconfig.app.agent

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteSshExecTool @Inject constructor(
    private val service: RemoteSshAgentService,
) : AgentTool {
    override val name: String = "remote_ssh_exec"
    override val description: String = "在已连接的远程节点执行 shell 命令。参数：{\"host\":\"节点名或IP，可省略\",\"command\":\"要执行的命令\"}"
    override val metadata: AgentToolMetadata = AgentToolMetadata(
        category = "远程开发",
        group = ToolGroup.ADVANCED,
        risk = ToolRisk.SENSITIVE,
        sensitive = true,
    )
    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val command = args["command"]?.toString()?.trim().orEmpty()
        if (command.isBlank()) return ToolResult.failure("缺少参数 command")
        return service.exec(args["host"]?.toString(), command)
    }
}

@Singleton
class RemoteSshReadTool @Inject constructor(
    private val service: RemoteSshAgentService,
) : AgentTool {
    override val name: String = "remote_ssh_read"
    override val description: String = "读取远程文本文件。参数：{\"host\":\"节点名或IP，可省略\",\"path\":\"/绝对路径\"}"
    override val metadata: AgentToolMetadata = AgentToolMetadata(
        category = "远程开发",
        group = ToolGroup.ADVANCED,
        risk = ToolRisk.READ_ONLY,
    )
    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val path = args["path"]?.toString()?.trim().orEmpty()
        if (path.isBlank()) return ToolResult.failure("缺少参数 path")
        return service.read(args["host"]?.toString(), path)
    }
}

@Singleton
class RemoteSshWriteTool @Inject constructor(
    private val service: RemoteSshAgentService,
) : AgentTool {
    override val name: String = "remote_ssh_write"
    override val description: String = "写入远程文本文件。参数：{\"host\":\"节点名或IP，可省略\",\"path\":\"/绝对路径\",\"content\":\"文件内容\"}"
    override val metadata: AgentToolMetadata = AgentToolMetadata(
        category = "远程开发",
        group = ToolGroup.ADVANCED,
        risk = ToolRisk.HIGH,
        sensitive = true,
    )
    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val path = args["path"]?.toString()?.trim().orEmpty()
        if (path.isBlank()) return ToolResult.failure("缺少参数 path")
        val content = args["content"]?.toString() ?: return ToolResult.failure("缺少参数 content")
        return service.write(args["host"]?.toString(), path, content)
    }
}

@Singleton
class RemoteSshListTool @Inject constructor(
    private val service: RemoteSshAgentService,
) : AgentTool {
    override val name: String = "remote_ssh_list"
    override val description: String = "列出远程目录。参数：{\"host\":\"节点名或IP，可省略\",\"path\":\"/绝对路径\"}"
    override val metadata: AgentToolMetadata = AgentToolMetadata(
        category = "远程开发",
        group = ToolGroup.ADVANCED,
        risk = ToolRisk.READ_ONLY,
    )
    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val path = args["path"]?.toString()?.trim().orEmpty().ifBlank { "/" }
        return service.list(args["host"]?.toString(), path)
    }
}

@Singleton
class RemoteSshSearchTool @Inject constructor(
    private val service: RemoteSshAgentService,
) : AgentTool {
    override val name: String = "remote_ssh_search"
    override val description: String = "在远程目录中搜索文本。参数：{\"host\":\"节点名或IP，可省略\",\"pattern\":\"搜索词\",\"path\":\"/绝对路径\"}"
    override val metadata: AgentToolMetadata = AgentToolMetadata(
        category = "远程开发",
        group = ToolGroup.ADVANCED,
        risk = ToolRisk.READ_ONLY,
    )
    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val pattern = args["pattern"]?.toString()?.trim().orEmpty()
        if (pattern.isBlank()) return ToolResult.failure("缺少参数 pattern")
        val path = args["path"]?.toString()?.trim().orEmpty().ifBlank { "/home" }
        return service.search(args["host"]?.toString(), pattern, path)
    }
}
