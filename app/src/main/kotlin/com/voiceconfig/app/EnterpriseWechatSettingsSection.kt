package com.voiceconfig.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * 微信/企业微信设置：
 * - 个人微信默认禁止自动化，避免账号风控
 * - 企业微信官方 API 配置用于合规的自动发送
 */
@Composable
fun EnterpriseWechatSettingsSection(
    wechatAutomationEnabled: Boolean,
    onWechatAutomationChange: (Boolean) -> Unit,
    wecomCorpId: String,
    onWecomCorpIdChange: (String) -> Unit,
    wecomAgentId: String,
    onWecomAgentIdChange: (String) -> Unit,
    wecomSecret: String,
    onWecomSecretChange: (String) -> Unit,
    wecomTestMessage: String?,
    onTestWecom: () -> Unit,
) {
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
            Text("微信 / 企业微信", style = MaterialTheme.typography.titleMedium)
            Text(
                "个人微信没有官方自动化 API，自动操作可能触发风控或封号。默认禁止个人微信 UI 自动化。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("微信小号风险模式", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "仅限专属小号 + 真实手机 + 人工最终确认；企业微信不受影响",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = wechatAutomationEnabled,
                    onCheckedChange = onWechatAutomationChange,
                )
            }

            Spacer(Modifier.height(4.dp))
            Text("企业微信官方 API（合规自动发送）", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = wecomCorpId,
                onValueChange = onWecomCorpIdChange,
                label = { Text("CorpId") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = wecomAgentId,
                onValueChange = onWecomAgentIdChange,
                label = { Text("AgentId") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = wecomSecret,
                onValueChange = onWecomSecretChange,
                label = { Text("Secret") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onTestWecom,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("测试企业微信 API 凭证")
            }
            wecomTestMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (message.contains("失败") || message.contains("请检查")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
    }
}
