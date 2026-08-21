package com.voiceconfig.app.scheduler

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import com.voiceconfig.core.model.TriggerCondition
import com.voiceconfig.core.model.TriggerRule
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 管理条件触发器的系统级注册。
 *
 * 目前支持位置触发器：使用 [LocationManager.addProximityAlert] 在进入/离开指定
 * 半径时发送广播给 [ConditionTriggerReceiver]。
 */
@Singleton
class TriggerRuleScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    fun schedule(rule: TriggerRule) {
        if (rule.condition.type != TriggerCondition.TriggerType.LOCATION) return
        val lat = rule.condition.latitude ?: return
        val lng = rule.condition.longitude ?: return
        val radius = (rule.condition.radiusMeters ?: 100).toFloat()
        val locationManager = locationManager ?: return
        runCatching {
            locationManager.addProximityAlert(
                lat,
                lng,
                radius,
                -1L,
                createPendingIntent(rule.id),
            )
        }
    }

    fun cancel(rule: TriggerRule) {
        if (rule.condition.type != TriggerCondition.TriggerType.LOCATION) return
        locationManager?.removeProximityAlert(createPendingIntent(rule.id))
    }

    fun cancel(ruleId: Long) {
        locationManager?.removeProximityAlert(createPendingIntent(ruleId))
    }

    fun restoreAll(rules: List<TriggerRule>) {
        rules.forEach { schedule(it) }
    }

    private fun createPendingIntent(ruleId: Long): PendingIntent {
        val intent = Intent(context, ConditionTriggerReceiver::class.java).apply {
            action = ConditionTriggerReceiver.ACTION_LOCATION_PROXIMITY_ALERT
            putExtra(ConditionTriggerReceiver.EXTRA_RULE_ID, ruleId)
        }
        return PendingIntent.getBroadcast(
            context,
            ruleId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
