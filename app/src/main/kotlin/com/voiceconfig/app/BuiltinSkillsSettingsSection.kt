package com.voiceconfig.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voiceconfig.app.agent.AgentSkill
import com.voiceconfig.app.agent.AgentSkillStatus

@Composable
fun BuiltinSkillsSettingsSection(
    skills: List<AgentSkill>,
) {
    val approved = skills.filter { it.status == AgentSkillStatus.APPROVED && it.enabled }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("已沉淀技能", style = MaterialTheme.typography.titleMedium)
            Text(
                "Agent 会优先参考这些已审核路径，但每一步仍会结合当前界面重新验证。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (approved.isEmpty()) {
                Text("暂无 APPROVED 技能", style = MaterialTheme.typography.bodySmall)
            } else {
                approved.take(8).forEach { skill ->
                    Text(
                        "• ${skill.name}（${skill.steps.size} 步）",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
