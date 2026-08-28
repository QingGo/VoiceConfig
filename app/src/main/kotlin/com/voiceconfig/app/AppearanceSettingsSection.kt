package com.voiceconfig.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.voiceconfig.app.ui.VoiceSectionCard

@Composable
internal fun AppearanceSettingsSection(
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
) {
    VoiceSectionCard(title = "外观", defaultExpanded = false) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = themeMode == "system", onClick = { onThemeModeChange("system") })
            Text("跟随系统")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = themeMode == "light", onClick = { onThemeModeChange("light") })
            Text("浅色")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = themeMode == "dark", onClick = { onThemeModeChange("dark") })
            Text("深色")
        }
    }
}
