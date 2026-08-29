package com.voiceconfig.app

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.voiceconfig.app.agent.AgentSession
import com.voiceconfig.app.agent.ShizukuCommandRunner
import com.voiceconfig.app.agent.TaskPlan
import com.voiceconfig.app.ai.InstalledAppProvider
import com.voiceconfig.app.ai.AsrEngineStatus
import com.voiceconfig.app.ai.LocalAsrManager
import com.voiceconfig.app.service.AccessibilityKeepAlive
import com.voiceconfig.app.service.VoiceConfigService
import com.voiceconfig.app.ui.theme.SuccessGreen
import com.voiceconfig.app.ui.AgentNavigation
import com.voiceconfig.app.ui.AppDestination
import com.voiceconfig.app.ui.AgentPage
import com.voiceconfig.app.ui.HomeAssistantPage
import com.voiceconfig.app.ui.OnboardingScreen
import com.voiceconfig.app.ui.ShoppingResearchPage
import com.voiceconfig.app.ui.theme.VoiceConfigTheme
import com.voiceconfig.app.ui.theme.WarningOrange
import com.voiceconfig.core.model.ActionType
import com.voiceconfig.core.model.ExecutionLog
import com.voiceconfig.core.model.ExecutionMode
import com.voiceconfig.core.model.ExecutionStatus
import com.voiceconfig.core.model.Task
import com.voiceconfig.core.model.Template
import com.voiceconfig.core.model.TaskDraft
import com.voiceconfig.core.model.ScheduleSpec
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var localAsrManager: LocalAsrManager
    @Inject lateinit var installedAppProvider: InstalledAppProvider
    @Inject lateinit var agentSession: AgentSession
    @Inject lateinit var shizukuCommandRunner: ShizukuCommandRunner
    @Inject lateinit var accessibilityKeepAlive: AccessibilityKeepAlive

    private val viewModel: MainViewModel by viewModels()
    private val profileViewModel: ProfileViewModel by viewModels()
    private val agentViewModel: AgentViewModel by viewModels()

    private val debugAgentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra("text")?.takeIf { it.isNotBlank() } ?: return
            val send = intent.getBooleanExtra("send", false)
            val newSession = intent.getBooleanExtra("newSession", false)
            AgentTestBridge.submit(AgentTestBridge.Command(text = text, send = send, newSession = newSession))
        }
    }

    private val debugForceNoShizukuReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val enabled = intent?.getBooleanExtra("enabled", false) ?: false
            shizukuCommandRunner.debugForceUnavailable = enabled
        }
    }

    private val debugTaskPlanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.getStringExtra("action")?.takeIf { it.isNotBlank() } ?: return
            val planId = intent?.getStringExtra("planId")?.takeIf { it.isNotBlank() }
            when (action) {
                "resume" -> {
                    if (planId != null) agentViewModel.resumeTaskPlan(planId) else agentViewModel.resumeLastTask()
                }
                "cancel" -> {
                    if (planId != null) agentViewModel.cancelTaskPlan(planId) else agentViewModel.cancelUnfinishedTaskPlans()
                }
                "cancelAll" -> agentViewModel.cancelUnfinishedTaskPlans()
            }
        }
    }

    private val debugAsrReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra("text")?.takeIf { it.isNotBlank() } ?: return
            val parse = intent.getBooleanExtra("parse", true)
            AgentTestBridge.submitHomeSpeech(AgentTestBridge.HomeSpeech(text = text, parse = parse))
        }
    }

    private val debugAsrFileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val wavPath = intent?.getStringExtra("wav")?.takeIf { it.isNotBlank() } ?: return
            val modelId = intent?.getStringExtra("model") ?: localAsrManager.selectedModel().id
            val parse = intent.getBooleanExtra("parse", true)
            val model = localAsrManager.availableModels().firstOrNull { it.id == modelId } ?: return
            Thread {
                localAsrManager.recognizeFile(
                    model = model,
                    wavPath = wavPath,
                    onResult = { text ->
                        if (text.isNotBlank()) {
                            AgentTestBridge.submitHomeSpeech(AgentTestBridge.HomeSpeech(text = text, parse = parse))
                        }
                    },
                    onError = { message ->
                        android.util.Log.e("AsrDebugFile", "ASR file failed: $message")
                    },
                )
            }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                    debugAgentReceiver,
                    IntentFilter("com.voiceconfig.app.DEBUG_AGENT_INPUT"),
                    Context.RECEIVER_EXPORTED,
                )
                registerReceiver(
                    debugAsrReceiver,
                    IntentFilter("com.voiceconfig.app.DEBUG_ASR_RESULT"),
                    Context.RECEIVER_EXPORTED,
                )
                registerReceiver(
                    debugTaskPlanReceiver,
                    IntentFilter("com.voiceconfig.app.DEBUG_TASKPLAN_ACTION"),
                    Context.RECEIVER_EXPORTED,
                )
                registerReceiver(
                    debugForceNoShizukuReceiver,
                    IntentFilter("com.voiceconfig.app.DEBUG_FORCE_NO_SHIZUKU"),
                    Context.RECEIVER_EXPORTED,
                )
                registerReceiver(
                    debugAsrFileReceiver,
                    IntentFilter("com.voiceconfig.app.DEBUG_ASR_FILE"),
                    Context.RECEIVER_EXPORTED,
                )
            } else {
                registerReceiver(debugAgentReceiver, IntentFilter("com.voiceconfig.app.DEBUG_AGENT_INPUT"))
                registerReceiver(debugAsrReceiver, IntentFilter("com.voiceconfig.app.DEBUG_ASR_RESULT"))
                registerReceiver(debugTaskPlanReceiver, IntentFilter("com.voiceconfig.app.DEBUG_TASKPLAN_ACTION"))
                registerReceiver(debugForceNoShizukuReceiver, IntentFilter("com.voiceconfig.app.DEBUG_FORCE_NO_SHIZUKU"))
                registerReceiver(debugAsrFileReceiver, IntentFilter("com.voiceconfig.app.DEBUG_ASR_FILE"))
            }
        }
        viewModel.refreshCapabilityStatus()
        setContent {
            val capabilityStatus by viewModel.capabilityStatus.collectAsState()
            val themeMode by profileViewModel.themeMode.collectAsState()
            VoiceConfigTheme(themeMode = themeMode) {
                val prefs = LocalContext.current.getSharedPreferences("voiceconfig_ux", Context.MODE_PRIVATE)
                var onboardingDone by remember { mutableStateOf(prefs.getBoolean("onboarding_done", false)) }
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    if (!onboardingDone) {
                        OnboardingScreen(
                            onFinish = {
                                prefs.edit().putBoolean("onboarding_done", true).apply()
                                onboardingDone = true
                            },
                            capabilityStatus = capabilityStatus,
                        )
                    } else {
                        MainScreen(viewModel = viewModel)
                    }
                }
            }
        }
        window.decorView.post {
            VoiceConfigService.start(this)
            runCatching { accessibilityKeepAlive.ensureEnabled() }
            handleGlobalVoiceIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleGlobalVoiceIntent(intent)
    }

    private fun handleGlobalVoiceIntent(intent: Intent?) {
        val text = intent?.getStringExtra("global_voice_text")?.takeIf { it.isNotBlank() } ?: return
        intent.removeExtra("global_voice_text")
        viewModel.submitVoiceResult(
            text = text,
            asrEngine = "global-overlay",
            toAgent = true,
            autoParse = true,
        )
    }

    override fun onResume() {
        super.onResume()
        sendOverlayBallVisibility(VoiceConfigService.ACTION_HIDE_GLOBAL_BALL)
    }

    override fun onPause() {
        sendOverlayBallVisibility(VoiceConfigService.ACTION_SHOW_GLOBAL_BALL)
        super.onPause()
    }

    private fun sendOverlayBallVisibility(action: String) {
        val intent = Intent(this, VoiceConfigService::class.java).setAction(action)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(debugAgentReceiver) }
        runCatching { unregisterReceiver(debugAsrReceiver) }
        runCatching { unregisterReceiver(debugTaskPlanReceiver) }
        runCatching { unregisterReceiver(debugForceNoShizukuReceiver) }
        runCatching { unregisterReceiver(debugAsrFileReceiver) }
    }
}
