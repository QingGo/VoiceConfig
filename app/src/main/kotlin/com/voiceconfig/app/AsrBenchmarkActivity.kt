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
        Log.i(TAG, "benchmark start model=${model.id} wav=$wavPath")
        localAsrManager.recognizeFile(
            model = model,
            wavPath = wavPath,
            onResult = { text ->
                Log.i(TAG, "RESULT model=${model.id} text=$text")
                finish()
            },
            onError = { message ->
                Log.e(TAG, "ERROR model=${model.id} message=$message")
                finish()
            },
        )
    }

    companion object {
        private const val TAG = "AsrBenchmark"
        private const val EXTRA_WAV = "wav"
        private const val EXTRA_MODEL = "model"
    }
}
