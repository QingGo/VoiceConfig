package com.voiceconfig.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import com.voiceconfig.app.ai.AsrModelManager
import com.voiceconfig.app.ai.LocalAsrManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Debug-only ASR benchmark activity.
 *
 * Usage:
 * adb push test.wav /sdcard/test.wav
 * adb shell am start -n com.voiceconfig.app/.AsrBenchmarkActivity \
 *   --es wav /sdcard/test.wav --es model sherpa-ctc-2025
 *
 * Result is printed to logcat with tag "AsrBenchmark".
 */
@AndroidEntryPoint
class AsrBenchmarkActivity : ComponentActivity() {

    @Inject lateinit var localAsrManager: LocalAsrManager
    @Inject lateinit var modelManager: AsrModelManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wavPath = intent.getStringExtra(EXTRA_WAV) ?: run {
            Log.e(TAG, "missing wav path")
            finish()
            return
        }
        val modelId = intent.getStringExtra(EXTRA_MODEL) ?: modelManager.selectedModel().id
        val model = modelManager.models.firstOrNull { it.id == modelId } ?: run {
            Log.e(TAG, "unknown model: $modelId")
            finish()
            return
        }
        val threadsOverride = intent.getStringExtra(EXTRA_THREADS)?.toIntOrNull()?.coerceIn(1, 16)
        val warm = intent.getBooleanExtra(EXTRA_WARM, false)
        val language = intent.getStringExtra(EXTRA_LANG)
        val provider = intent.getStringExtra(EXTRA_PROVIDER)
        val startMs = System.currentTimeMillis()
        Log.i(TAG, "benchmark start model=${model.id} wav=$wavPath warm=$warm threads=${threadsOverride ?: "default"} language=${language ?: "default"} provider=${provider ?: "default"}")
        Thread {
            if (warm) {
                val warmStart = System.currentTimeMillis()
                localAsrManager.prepareModel(model)
                Log.i(TAG, "WARMUP model=${model.id} warmupMs=${System.currentTimeMillis() - warmStart}")
            }
            localAsrManager.recognizeFile(
                model = model,
                wavPath = wavPath,
                onResult = { text ->
                    val totalMs = System.currentTimeMillis() - startMs
                    Log.i(TAG, "RESULT model=${model.id} text=$text totalMs=$totalMs")
                    finish()
                },
                onError = { message ->
                    val totalMs = System.currentTimeMillis() - startMs
                    Log.e(TAG, "ERROR model=${model.id} message=$message totalMs=$totalMs")
                    finish()
                },
                threadsOverride = threadsOverride,
                useCachedEngine = warm,
                language = language,
                provider = provider,
            )
        }.start()
    }

    companion object {
        private const val TAG = "AsrBenchmark"
        private const val EXTRA_WAV = "wav"
        private const val EXTRA_MODEL = "model"
        private const val EXTRA_THREADS = "threads"
        private const val EXTRA_WARM = "warm"
        private const val EXTRA_LANG = "lang"
        private const val EXTRA_PROVIDER = "provider"
    }
}
