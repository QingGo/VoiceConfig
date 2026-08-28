package com.voiceconfig.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.voiceconfig.app.ui.VoiceSectionCard

@Composable
internal fun PermissionSettingsSection() {
    VoiceSectionCard(title = "权限与系统", defaultExpanded = false) {
        PermissionCheckSection(modifier = Modifier.fillMaxWidth())
    }
}
