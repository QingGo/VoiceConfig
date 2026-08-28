package com.voiceconfig.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
            val context = LocalContext.current
            Text(
                text = "版本 ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.clickable {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText("言控版本", "言控 ${BuildConfig.VERSION_NAME}"),
                    )
                },
            )
            TextButton(
                onClick = {
                    context.getSharedPreferences("voiceconfig_ux", Context.MODE_PRIVATE)
                        .edit()
                        .remove("onboarding_done")
                        .apply()
                    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        context.startActivity(intent)
                    }
                },
            ) {
                Text("重新观看引导")
            }
        }
    }
}
