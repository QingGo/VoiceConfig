package com.voiceconfig.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.voiceconfig.app.ui.VoiceSectionCard

@Composable
internal fun IntelligenceSettingsSection(
    onOpenShopping: () -> Unit,
) {
    VoiceSectionCard(title = "智能能力", defaultExpanded = false) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("购物研究清单", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "价格 / 评分 / 口碑对比与采购状态",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onOpenShopping) {
                Text("打开")
            }
        }
    }
}
