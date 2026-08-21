package com.voiceconfig.app.ai

import android.content.Context
import android.content.Intent
import com.voiceconfig.core.nlp.InstalledApp
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InstalledAppProvider @Inject constructor(
    @ApplicationContext context: Context,
) {
    val installedApps: List<InstalledApp> by lazy {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        runCatching {
            packageManager.queryIntentActivities(launcherIntent, 0)
                .mapNotNull { resolveInfo ->
                    val label = resolveInfo.loadLabel(packageManager)?.toString()?.trim().orEmpty()
                    if (label.isBlank()) {
                        null
                    } else {
                        InstalledApp(
                            packageName = resolveInfo.activityInfo.packageName,
                            label = label,
                            activityName = resolveInfo.activityInfo.name,
                        )
                    }
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
    }
}
