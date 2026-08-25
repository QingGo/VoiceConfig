package com.voiceconfig.app.agent

import com.voiceconfig.data.local.repository.RemoteNode
import javax.inject.Inject
import javax.inject.Singleton

data class SkillDistribution(
    val skill: AgentSkill,
    val node: RemoteNode,
    val reason: String = "",
)

/**
 * R4 Skill 分发器：
 * - 只分发自“已审核 + 启用”的 Skill；
 * - 按节点能力（允许的远程命令）过滤；
 * - 可生成节点可执行的 command-skill payload。
 */
@Singleton
class AgentSkillDistributor @Inject constructor() {

    fun distribute(skills: List<AgentSkill>, node: RemoteNode): List<SkillDistribution> {
        val caps = nodeCapabilities(node)
        return skills
            .filter { it.status == AgentSkillStatus.APPROVED && it.enabled }
            .mapNotNull { skill ->
                val unsupported = skill.requiredCapabilities.filter { it !in caps }
                if (unsupported.isEmpty()) {
                    SkillDistribution(skill, node, "capabilities match")
                } else {
                    null
                }
            }
    }

    fun nodeCapabilities(node: RemoteNode): Set<String> {
        val caps = linkedSetOf<String>()
        node.allowedCommands.forEach { cmd ->
            caps += "cmd:$cmd"
        }
        if (node.allowedCommands.isNotEmpty()) {
            caps += "RemoteReadOnly"
        }
        return caps
    }

    fun toRemoteSkillPayload(skill: AgentSkill, node: RemoteNode): Map<String, Any?> {
        val steps = skill.steps.mapNotNull { step ->
            val command = step.toolName
            if (command in node.allowedCommands) {
                mapOf(
                    "command" to command,
                    "purpose" to step.purpose,
                    "expected" to step.expected,
                    "verification" to step.verification,
                    "fallback" to step.fallback,
                )
            } else {
                null
            }
        }
        return mapOf(
            "id" to skill.id,
            "name" to skill.name,
            "text" to skill.text,
            "steps" to steps,
        )
    }
}
