package com.voiceconfig.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.voiceconfig.app.MainActivity
import com.voiceconfig.app.R
import com.voiceconfig.app.agent.TaskPlanStore
import com.voiceconfig.app.scheduler.ConditionTriggerHandler
import com.voiceconfig.app.scheduler.TriggerRuleScheduler
import com.voiceconfig.app.voice.GlobalPowerPolicy
import com.voiceconfig.app.voice.GlobalVoiceSession
import com.voiceconfig.app.voice.GlobalWakeWordEngine
import com.voiceconfig.core.scheduler.TaskScheduler
import com.voiceconfig.data.local.repository.TaskRepository
import com.voiceconfig.data.local.repository.TriggerRuleRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
class VoiceKeepAliveService : Service() {

    @Inject lateinit var conditionTriggerHandler: ConditionTriggerHandler
    @Inject lateinit var taskRepository: TaskRepository
    @Inject lateinit var taskScheduler: TaskScheduler
    @Inject lateinit var triggerRuleRepository: TriggerRuleRepository
    @Inject lateinit var triggerRuleScheduler: TriggerRuleScheduler
    @Inject lateinit var accessibilityKeepAlive: AccessibilityKeepAlive
    @Inject lateinit var taskPlanStore: TaskPlanStore
    @Inject lateinit var overlayController: GlobalOverlayController
    @Inject lateinit var voiceSession: GlobalVoiceSession
    @Inject lateinit var wakeWordEngine: GlobalWakeWordEngine
    @Inject lateinit var powerPolicy: GlobalPowerPolicy

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    powerPolicy.setScreenOff(true)
                    wakeWordEngine.pause()
                    voiceSession.stop()
                }
                Intent.ACTION_SCREEN_ON -> {
                    powerPolicy.setScreenOff(false)
                    wakeWordEngine.resume()
                }
                Intent.ACTION_USER_PRESENT -> {
                    powerPolicy.setScreenOff(false)
                    wakeWordEngine.resume()
                }
                Intent.ACTION_BATTERY_LOW -> {
                    powerPolicy.setLowBattery(true)
                    wakeWordEngine.pause()
                    voiceSession.stop()
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    powerPolicy.setLowBattery(false)
                    wakeWordEngine.resume()
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    // 断开电源本身不强制恢复；若此前进入低电量仍保持暂停，
                    // 只有 POWER_CONNECTED 或亮屏会解除。
                }
            }
            scope.launch {
                runCatching { conditionTriggerHandler.handle(context, intent) }
            }
        }
    }
    private var receiverRegistered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerConditionReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        when (intent?.action) {
            ACTION_HIDE_GLOBAL_BALL -> overlayController.hide()
            ACTION_SHOW_GLOBAL_BALL -> overlayController.show()
            else -> overlayController.show()
        }
        if (!receiverRegistered) {
            registerConditionReceiver()
        }
        restoreSchedules()
        startAccessibilityKeepAliveLoop()
        notifyUnfinishedAgentPlans()
        applyInitialPowerPolicy()
        wakeWordEngine.startIfEnabled()
        return START_STICKY
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        wakeWordEngine.stop()
        voiceSession.stop()
        overlayController.dispose()
        super.onDestroy()
    }

    private fun applyInitialPowerPolicy() {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val low = level >= 0 && scale > 0 && level * 100 / scale <= 20
        powerPolicy.setLowBattery(low)
        if (low) {
            wakeWordEngine.pause()
        }
    }

    private fun startAccessibilityKeepAliveLoop() {
        scope.launch {
            delay(2_000)
            var lastState = accessibilityKeepAlive.refresh()
            Log.i(TAG, "accessibility keep-alive initial state=$lastState")
            while (true) {
                val delayMs = when (accessibilityKeepAlive.currentState()) {
                    AccessibilityKeepAliveState.CONNECTED -> 30_000L
                    AccessibilityKeepAliveState.CONNECTING -> 5_000L
                    AccessibilityKeepAliveState.DISCONNECTED -> 10_000L
                    AccessibilityKeepAliveState.CRASHED -> 5_000L
                }
                delay(delayMs)
                val state = accessibilityKeepAlive.refresh()
                if (state != lastState) {
                    Log.i(TAG, "accessibility keep-alive state changed $lastState -> $state")
                    lastState = state
                } else if (state == AccessibilityKeepAliveState.CONNECTING || state == AccessibilityKeepAliveState.CRASHED) {
                    Log.w(TAG, "accessibility keep-alive still $state, attempts=${accessibilityKeepAlive.refreshCount}, lastError=${accessibilityKeepAlive.lastError}")
                }
            }
        }
    }

    private fun notifyUnfinishedAgentPlans() {
        scope.launch {
            runCatching {
                val plans = taskPlanStore.loadActivePlans()
                if (plans.isEmpty()) return@launch
                val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                val contentIntent = PendingIntent.getActivity(
                    this@VoiceKeepAliveService,
                    1002,
                    Intent(this@VoiceKeepAliveService, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val notification = NotificationCompat.Builder(this@VoiceKeepAliveService, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("有 ${plans.size} 个未完成 Agent 任务")
                    .setContentText("打开言控可继续执行或放弃这些任务")
                    .setContentIntent(contentIntent)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
                manager.notify(1003, notification)
            }
        }
    }

    private fun restoreSchedules() {
        scope.launch {
            runCatching {
                taskScheduler.restoreAll(taskRepository.getEnabledTasks())
                triggerRuleScheduler.restoreAll(triggerRuleRepository.getEnabled())
            }
        }
    }

    private fun registerConditionReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("言控正在运行")
            .setContentText("定时任务与语音自动化服务保持运行中")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "言控保活服务",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "保持定时任务和语音自动化服务运行"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "VoiceKeepAliveService"
        private const val CHANNEL_ID = "voice_config_keep_alive"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_HIDE_GLOBAL_BALL = "com.voiceconfig.app.action.HIDE_GLOBAL_BALL"
        const val ACTION_SHOW_GLOBAL_BALL = "com.voiceconfig.app.action.SHOW_GLOBAL_BALL"

        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, VoiceKeepAliveService::class.java).apply {
                action?.let { setAction(it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
