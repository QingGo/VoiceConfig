package com.voiceconfig.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voiceconfig.app.ai.LocalAsrManager
import com.voiceconfig.app.ui.VoiceSectionCard
import kotlinx.coroutines.launch

@Composable
internal fun AsrSettingsSection(
    localAsrManager: LocalAsrManager?,
) {
    val scope = rememberCoroutineScope()
    var showExperimentalAsr by remember { mutableStateOf(false) }
    var asrSelectedId by remember { mutableStateOf(localAsrManager?.selectedModel()?.id ?: "") }
    var asrDownloadingId by remember { mutableStateOf<String?>(null) }
    var asrDownloadProgress by remember { mutableStateOf(0f) }
    var asrErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    VoiceSectionCard(title = "语音识别", defaultExpanded = false) {
        if (localAsrManager != null) {
            Text(
                text = "推荐：${localAsrManager.recommendedModel().displayName}（性能最佳，需下载）\n默认内置：${localAsrManager.defaultModel().displayName}（安装包小，开箱可用）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "模型列表", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = { showExperimentalAsr = !showExperimentalAsr }) {
                    Text(if (showExperimentalAsr) "隐藏实验模型" else "显示实验模型")
                }
            }
            localAsrManager.visibleModels(
                includeExperimental = showExperimentalAsr,
                includeHidden = false,
            ).forEach { model ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = asrSelectedId == model.id,
                            onClick = {
                                if (localAsrManager.isDownloaded(model)) {
                                    localAsrManager.selectModel(model.id)
                                    asrSelectedId = model.id
                                    scope.launch { localAsrManager.warmUp() }
                                }
                            },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = model.displayName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "${localAsrManager.modelSizeText(model)} · ${model.description}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (model.builtin || localAsrManager.isDownloaded(model)) {
                            TextButton(
                                onClick = {
                                    localAsrManager.selectModel(model.id)
                                    asrSelectedId = model.id
                                    scope.launch { localAsrManager.warmUp() }
                                },
                                enabled = asrSelectedId != model.id,
                            ) {
                                Text(if (asrSelectedId == model.id) "使用中" else "使用")
                            }
                        } else {
                            if (asrDownloadingId == model.id) {
                                Column(
                                    modifier = Modifier.width(110.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    LinearProgressIndicator(
                                        progress = { asrDownloadProgress },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Text(
                                        text = "下载中 ${(asrDownloadProgress * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            } else {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            asrDownloadingId = model.id
                                            asrErrors = asrErrors - model.id
                                            runCatching {
                                                localAsrManager.downloadModel(model) { progress ->
                                                    asrDownloadProgress = progress
                                                }
                                            }.onFailure { e ->
                                                asrErrors = asrErrors + (model.id to (e.message ?: "下载失败"))
                                            }
                                            asrDownloadingId = null
                                            localAsrManager.selectModel(model.id)
                                            asrSelectedId = model.id
                                            scope.launch { localAsrManager.warmUp() }
                                        }
                                    },
                                ) {
                                    Text("下载")
                                }
                            }
                        }
                    }
                    asrErrors[model.id]?.let { error ->
                        if (asrDownloadingId != model.id) {
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}
