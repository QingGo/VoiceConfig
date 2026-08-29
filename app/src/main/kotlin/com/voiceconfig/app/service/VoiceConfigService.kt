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
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.voiceconfig.app.MainActivity
import com.voiceconfig.app.R
import com.voiceconfig.app.agent.TaskPlanStore
import com.voiceconfig.app.ai.TtsSpeaker
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
    @Inject lateinit var ttsSpeaker: TtsSpeaker

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayWindowManager: WindowManager? = null
    private var globalRecognizer: SpeechRecognizer? = null
    private var globalListening = false
    private var globalVoiceTimeout: Runnable? = null
    private var voiceConfirmationView: View? = null
    private var voiceConfirmationParams: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    wakeWordDetector.stop()
                    stopGlobalVoice()
                }
                Intent.ACTION_SCREEN_ON -> {
                    startWakeWordIfEnabled()
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
            ACTION_HIDE_GLOBAL_BALL -> hideGlobalBall()
            ACTION_SHOW_GLOBAL_BALL -> showGlobalBallIfAllowed()
            else -> showGlobalBallIfAllowed()
        }
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
        stopGlobalVoice()
        removeGlobalBall()
        dismissVoiceConfirmation()
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
                    startGlobalVoice()
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
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
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
        if (!apiKeyStore.overlayBallEnabled) return
        if (overlayView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return
        val prefs = getSharedPreferences("voiceconfig_overlay", Context.MODE_PRIVATE)
        val density = resources.displayMetrics.density
        val size = (60 * density).toInt()
        val text = TextView(this).apply {
            this.text = "言"
            textSize = 24f
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
        var longPressTriggered = false
        val openAppRunnable = Runnable {
            longPressTriggered = true
            val intent = Intent(this@VoiceConfigService, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("global_ball", true)
            }
            runCatching { startActivity(intent) }
        }
        text.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    initialX = params.x
                    initialY = params.y
                    moved = false
                    longPressTriggered = false
                    mainHandler.postDelayed(openAppRunnable, 600)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) {
                        moved = true
                        mainHandler.removeCallbacks(openAppRunnable)
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    runCatching { wm.updateViewLayout(text, params) }
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(openAppRunnable)
                    if (!moved && !longPressTriggered) {
                        startGlobalVoice()
                    } else if (moved) {
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

    private fun hideGlobalBall() {
        removeGlobalBall()
    }

    private fun removeGlobalBall() {
        val view = overlayView ?: return
        val wm = overlayWindowManager ?: return
        runCatching { wm.removeView(view) }
        overlayView = null
        overlayParams = null
        overlayWindowManager = null
    }

    private fun startGlobalVoice() {
        if (globalListening) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        wakeWordDetector.stop()
        globalListening = true
        setOverlayListening(true)
        val recognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(this) }.getOrNull() ?: run {
            globalListening = false
            setOverlayListening(false)
            startWakeWordIfEnabled()
            return
        }
        globalRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                finishGlobalVoice(sendResult = false)
            }
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                finishGlobalVoice(sendResult = true, text = text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    runCatching {
                        overlayView?.let { view ->
                            if (view is TextView) {
                                view.text = text.take(4)
                                view.textSize = 12f
                            }
                        }
                    }
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching { recognizer.startListening(intent) }
            .onFailure {
                finishGlobalVoice(sendResult = false)
            }
        val timeout = Runnable {
            if (globalListening) {
                finishGlobalVoice(sendResult = false)
            }
        }
        globalVoiceTimeout = timeout
        mainHandler.postDelayed(timeout, 12_000)
    }

    private fun finishGlobalVoice(sendResult: Boolean, text: String = "") {
        globalVoiceTimeout?.let { mainHandler.removeCallbacks(it) }
        globalVoiceTimeout = null
        globalListening = false
        runCatching { globalRecognizer?.stopListening() }
        runCatching { globalRecognizer?.destroy() }
        globalRecognizer = null
        setOverlayListening(false)
        if (sendResult && text.isNotBlank()) {
            showVoiceConfirmation(text)
        }
        startWakeWordIfEnabled()
    }

    private fun stopGlobalVoice() {
        globalVoiceTimeout?.let { mainHandler.removeCallbacks(it) }
        globalVoiceTimeout = null
        globalListening = false
        runCatching { globalRecognizer?.stopListening() }
        runCatching { globalRecognizer?.destroy() }
        globalRecognizer = null
        setOverlayListening(false)
    }

    private fun setOverlayListening(listening: Boolean) {
        mainHandler.post {
            val view = overlayView as? TextView ?: return@post
            val params = overlayParams ?: return@post
            val wm = overlayWindowManager ?: return@post
            val density = resources.displayMetrics.density
            if (listening) {
                view.text = "听"
                view.textSize = 20f
                view.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xCCE53935.toInt())
                }
                params.width = (150 * density).toInt()
                params.height = (64 * density).toInt()
            } else {
                view.text = "言"
                view.textSize = 24f
                view.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xCC4F46E5.toInt())
                }
                params.width = (60 * density).toInt()
                params.height = (60 * density).toInt()
            }
            runCatching { wm.updateViewLayout(view, params) }
        }
    }

    private fun showVoiceConfirmation(text: String) {
        dismissVoiceConfirmation()
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return
        val density = resources.displayMetrics.density
        val panelWidth = (300 * density).toInt()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (16 * density).toInt(),
                (12 * density).toInt(),
                (16 * density).toInt(),
                (12 * density).toInt(),
            )
            background = GradientDrawable().apply {
                cornerRadius = 16 * density
                setColor(0xF2FFFFFF.toInt())
            }
            elevation = 12f
        }
        val title = TextView(this).apply {
            this.text = "识别到"
            textSize = 13f
            setTextColor(Color.GRAY)
        }
        val body = TextView(this).apply {
            this.text = text
            textSize = 17f
            setTextColor(Color.BLACK)
            setPadding(0, (6 * density).toInt(), 0, (8 * density).toInt())
        }
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val execute = Button(this).apply {
            this.text = "执行"
        }
        val cancel = Button(this).apply {
            this.text = "取消"
        }
        execute.setOnClickListener {
            val intent = Intent(this@VoiceConfigService, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("global_voice_text", text)
            }
            dismissVoiceConfirmation()
            runCatching { ttsSpeaker.speak("好的，正在处理") }
            runCatching { startActivity(intent) }
        }
        cancel.setOnClickListener {
            dismissVoiceConfirmation()
        }
        buttonRow.addView(execute, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttonRow.addView(cancel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(title)
        panel.addView(body)
        panel.addView(buttonRow)
        val params = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }
        runCatching {
            wm.addView(panel, params)
            voiceConfirmationView = panel
            voiceConfirmationParams = params
        }
    }

    private fun dismissVoiceConfirmation() {
        val view = voiceConfirmationView ?: return
        val wm = overlayWindowManager ?: getSystemService(WINDOW_SERVICE) as? WindowManager ?: return
        runCatching { wm.removeView(view) }
        voiceConfirmationView = null
        voiceConfirmationParams = null
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
        const val ACTION_HIDE_GLOBAL_BALL = "com.voiceconfig.app.action.HIDE_GLOBAL_BALL"
        const val ACTION_SHOW_GLOBAL_BALL = "com.voiceconfig.app.action.SHOW_GLOBAL_BALL"

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
