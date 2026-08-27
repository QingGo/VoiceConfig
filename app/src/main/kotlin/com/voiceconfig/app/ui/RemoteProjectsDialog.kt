package com.voiceconfig.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voiceconfig.data.local.repository.RemoteProjectRecord

@Composable
fun RemoteProjectsDialog(
    projects: List<RemoteProjectRecord>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("远程项目") },
        text = {
            if (projects.isEmpty()) {
                Text("暂无已保存的远程项目。可通过 Agent 调用 remote_project_inspect 自动保存。")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "已保存 ${projects.size} 个远程项目",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(projects.size) { index ->
                            val project = projects[index]
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = "${project.name} · ${project.repoType}",
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Text(
                                        text = "${project.nodeHost} · ${project.rootPath}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (project.buildCommand != null || project.testCommand != null) {
                                        Text(
                                            text = listOfNotNull(
                                                project.buildCommand?.let { "构建: $it" },
                                                project.testCommand?.let { "测试: $it" },
                                            ).joinToString(" | "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}
