package com.voiceconfig.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.voiceconfig.app.ui.VoiceSectionCard

@Composable
internal fun AboutSettingsSection() {
    VoiceSectionCard(title = "关于", defaultExpanded = false) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "言控",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "你的一句话，我帮你完成。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "版本 0.1.1",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
