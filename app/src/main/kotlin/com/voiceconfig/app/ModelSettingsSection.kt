package com.voiceconfig.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.voiceconfig.app.ui.VoiceSectionCard

@Composable
internal fun ModelSettingsSection(
    isConfigured: Boolean,
    currentModel: String,
    draftApiKey: String,
    onDraftApiKeyChange: (String) -> Unit,
    draftModel: String,
    onDraftModelChange: (String) -> Unit,
    showEditor: Boolean,
    onShowEditorChange: (Boolean) -> Unit,
    showKey: Boolean,
    onShowKeyChange: (Boolean) -> Unit,
) {
    VoiceSectionCard(title = "模型与密钥", defaultExpanded = true) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isConfigured) "已配置" else "未配置",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isConfigured) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                if (isConfigured) {
                    Text(
                        text = currentModel.ifBlank { "deepseek-v4-flash-vision-exp" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = { onShowEditorChange(!showEditor) }) {
                Text(if (showEditor) "收起" else "编辑")
            }
        }
        if (showEditor) {
            OutlinedTextField(
                value = draftApiKey,
                onValueChange = onDraftApiKeyChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("DeepSeek API Key") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { onShowKeyChange(!showKey) }) {
                        Text(if (showKey) "隐藏" else "显示")
                    }
                },
            )
            OutlinedTextField(
                value = draftModel,
                onValueChange = onDraftModelChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("模型（默认 deepseek-v4-flash-vision-exp）") },
                singleLine = true,
            )
        }
    }
}
