package com.voiceconfig.app

import androidx.compose.runtime.Composable
import com.voiceconfig.app.ui.VoiceSectionCard

@Composable
internal fun DeveloperModeSettingsSection(
    developerMode: Boolean,
    onDeveloperModeChange: (Boolean) -> Unit,
) {
    VoiceSectionCard(title = "开发者模式", defaultExpanded = false) {
        SwitchRow(
            title = "显示高级能力",
            subtitle = "SSH、远程节点、审计、调试日志仅在需要时开启",
            checked = developerMode,
            onCheckedChange = onDeveloperModeChange,
        )
    }
}
