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
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.voiceconfig.app.MainActivity
import com.voiceconfig.app.R
import com.voiceconfig.app.agent.TaskPlanStore
import com.voiceconfig.app.ai.WakeWordDetector
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
    @Inject lateinit var taskPlanStore: TaskPlanStore
    @Inject lateinit var apiKeyStore: com.voiceconfig.app.ai.ApiKeyStore
    @Inject lateinit var wakeWordDetector: WakeWordDetector

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayWindowManager: WindowManager? = null
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
        showGlobalBallIfAllowed()
        if (!receiverRegistered) {
            registerConditionReceiver()
        }
        restoreSchedules()
        startAccessibilityKeepAliveLoop()
        notifyUnfinishedAgentPlans()
        startWakeWordIfEnabled()
        return START_STICKY
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        wakeWordDetector.stop()
        removeGlobalBall()
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

    private fun notifyUnfinishedAgentPlans() {
        scope.launch {
            runCatching {
                val plans = taskPlanStore.loadActivePlans()
                if (plans.isEmpty()) return@launch
                val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                val contentIntent = PendingIntent.getActivity(
                    this@VoiceConfigService,
                    1002,
                    Intent(this@VoiceConfigService, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val notification = NotificationCompat.Builder(this@VoiceConfigService, CHANNEL_ID)
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

    private fun startWakeWordIfEnabled() {
        if (!apiKeyStore.wakeWordEnabled) {
            wakeWordDetector.stop()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return
        wakeWordDetector.start(
            listener = object : WakeWordDetector.Listener {
                override fun onWakeWord(text: String) {
                    val intent = Intent(this@VoiceConfigService, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("wake_word", text)
                    }
                    startActivity(intent)
                }

                override fun onError(error: Int) {
                    // 由 WakeWordDetector 自动重启；这里可记录日志。
                }
            },
            keywords = listOf("言控", "语音助手", "你好言控"),
        )
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

    private fun showGlobalBallIfAllowed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!Settings.canDrawOverlays(this)) return
        if (overlayView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return
        val prefs = getSharedPreferences("voiceconfig_overlay", Context.MODE_PRIVATE)
        val size = 56
        val text = TextView(this).apply {
            this.text = "言"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC4F46E5.toInt())
            }
            isClickable = true
            isFocusable = true
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", 24)
            y = prefs.getInt("y", 200)
        }
        var downX = 0f
        var downY = 0f
        var initialX = 0
        var initialY = 0
        var moved = false
        text.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    initialX = params.x
                    initialY = params.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    runCatching { wm.updateViewLayout(text, params) }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        val intent = Intent(this@VoiceConfigService, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra("global_ball", true)
                        }
                        runCatching { startActivity(intent) }
                    } else {
                        prefs.edit().putInt("x", params.x).putInt("y", params.y).apply()
                    }
                }
            }
            true
        }
        runCatching {
            wm.addView(text, params)
            overlayView = text
            overlayParams = params
            overlayWindowManager = wm
        }
    }

    private fun removeGlobalBall() {
        val view = overlayView ?: return
        val wm = overlayWindowManager ?: return
        runCatching { wm.removeView(view) }
        overlayView = null
        overlayParams = null
        overlayWindowManager = null
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
