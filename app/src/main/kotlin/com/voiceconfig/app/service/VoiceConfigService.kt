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
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.voiceconfig.app.MainActivity
import com.voiceconfig.app.R
import com.voiceconfig.app.scheduler.ConditionTriggerHandler
import com.voiceconfig.app.scheduler.TriggerRuleScheduler
import kotlinx.coroutines.delay
import com.voiceconfig.core.scheduler.TaskScheduler
import com.voiceconfig.data.local.repository.TaskRepository
import com.voiceconfig.data.local.repository.TriggerRuleRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 前台保活服务。
 *
 * 同时动态注册 Wi-Fi / 电量广播，避免 Android 12+ 对隐式广播的后台限制，
 * 让条件触发器在服务存活期间可靠触发。
 */
@AndroidEntryPoint
class VoiceConfigService : Service() {

    @Inject lateinit var conditionTriggerHandler: ConditionTriggerHandler
    @Inject lateinit var taskRepository: TaskRepository
    @Inject lateinit var taskScheduler: TaskScheduler
    @Inject lateinit var triggerRuleRepository: TriggerRuleRepository
    @Inject lateinit var triggerRuleScheduler: TriggerRuleScheduler
    @Inject lateinit var accessibilityKeepAlive: AccessibilityKeepAlive

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
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
        if (!receiverRegistered) {
            registerConditionReceiver()
        }
        restoreSchedules()
        startAccessibilityKeepAliveLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    private fun startAccessibilityKeepAliveLoop() {
        scope.launch {
            // 启动后等待 Shizuku binder 就绪，再尝试一次，并周期性补写。
            delay(2_000)
            accessibilityKeepAlive.ensureEnabled()
            while (true) {
                delay(30_000)
                accessibilityKeepAlive.ensureEnabled()
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
        private const val CHANNEL_ID = "voice_config_keep_alive"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, VoiceConfigService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
