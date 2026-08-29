package com.voiceconfig.app.service

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.voiceconfig.app.MainActivity
import com.voiceconfig.app.ai.ApiKeyStore
import com.voiceconfig.app.ai.TtsSpeaker
import com.voiceconfig.app.voice.GlobalVoicePhase
import com.voiceconfig.app.voice.GlobalVoiceSession
import com.voiceconfig.app.voice.GlobalVoiceUiState
import com.voiceconfig.app.voice.VoiceCommandSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Singleton
class GlobalOverlayController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: GlobalVoiceSession,
    private val ttsSpeaker: TtsSpeaker,
    private val apiKeyStore: ApiKeyStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayWindowManager: WindowManager? = null
    private var confirmationView: View? = null
    private var confirmationParams: WindowManager.LayoutParams? = null

    init {
        scope.launch {
            session.state.collect { state ->
                render(state)
            }
        }
    }

    fun show() {
        if (!apiKeyStore.overlayBallEnabled) {
            hide()
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            Settings.canDrawOverlays(context)
        ) {
            ensureBall()
        }
        session.show()
    }

    fun hide() {
        removeBall()
        dismissConfirmation()
        session.hide()
    }

    fun dispose() {
        hide()
    }

    private fun ensureBall() {
        if (overlayView != null) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val prefs = context.getSharedPreferences("voiceconfig_overlay", Context.MODE_PRIVATE)
        val density = context.resources.displayMetrics.density
        val size = (60 * density).toInt()
        val text = TextView(context).apply {
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
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("global_ball", true)
            }
            runCatching { context.startActivity(intent) }
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
                        session.startListening(VoiceCommandSource.GLOBAL_BALL)
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

    private fun render(state: GlobalVoiceUiState) {
        when (state.phase) {
            GlobalVoicePhase.HIDDEN -> {
                removeBall()
                dismissConfirmation()
            }
            GlobalVoicePhase.IDLE -> {
                if (overlayView != null) {
                    setBallListening(false)
                }
                dismissConfirmation()
            }
            GlobalVoicePhase.LISTENING -> {
                setBallListening(true)
                state.partialText.takeIf { it.isNotBlank() }?.let { text ->
                    (overlayView as? TextView)?.let { view ->
                        view.text = text
                        view.textSize = 12f
                    }
                }
                dismissConfirmation()
            }
            GlobalVoicePhase.CONFIRMING -> {
                setBallListening(false)
                state.recognizedText?.let { showConfirmation(it) }
            }
        }
    }

    private fun setBallListening(listening: Boolean) {
        val view = overlayView as? TextView ?: return
        val params = overlayParams ?: return
        val wm = overlayWindowManager ?: return
        val density = context.resources.displayMetrics.density
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

    private fun showConfirmation(text: String) {
        if (confirmationView != null) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val density = context.resources.displayMetrics.density
        val panelWidth = (300 * density).toInt()
        val panel = LinearLayout(context).apply {
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
        val title = TextView(context).apply {
            this.text = "识别到"
            textSize = 13f
            setTextColor(Color.GRAY)
        }
        val body = TextView(context).apply {
            this.text = text
            textSize = 17f
            setTextColor(Color.BLACK)
            setPadding(0, (6 * density).toInt(), 0, (8 * density).toInt())
        }
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val execute = Button(context).apply {
            this.text = "执行"
        }
        val cancel = Button(context).apply {
            this.text = "取消"
        }
        execute.setOnClickListener {
            val confirmed = session.confirm()
            dismissConfirmation()
            if (confirmed != null) {
                runCatching { ttsSpeaker.speak("好的，正在处理") }
                val intent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                runCatching { context.startActivity(intent) }
            }
        }
        cancel.setOnClickListener {
            session.cancel()
            dismissConfirmation()
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
            confirmationView = panel
            confirmationParams = params
        }
    }

    private fun dismissConfirmation() {
        val view = confirmationView ?: return
        val wm = overlayWindowManager ?: context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        runCatching { wm.removeView(view) }
        confirmationView = null
        confirmationParams = null
    }

    private fun removeBall() {
        val view = overlayView ?: return
        val wm = overlayWindowManager ?: return
        runCatching { wm.removeView(view) }
        overlayView = null
        overlayParams = null
        overlayWindowManager = null
    }
}
