package com.voiceconfig.app.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 条件触发器接收器：把系统广播转交给 [ConditionTriggerHandler]。
 */
@AndroidEntryPoint
class ConditionTriggerReceiver : BroadcastReceiver() {

    @Inject lateinit var handler: ConditionTriggerHandler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        scope.launch {
            try {
                handler.handle(context, intent)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Condition trigger failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ConditionTrigger"
        const val ACTION_LOCATION_PROXIMITY_ALERT = "com.voiceconfig.app.action.LOCATION_PROXIMITY_ALERT"
        const val EXTRA_RULE_ID = "extra_rule_id"
    }
}
