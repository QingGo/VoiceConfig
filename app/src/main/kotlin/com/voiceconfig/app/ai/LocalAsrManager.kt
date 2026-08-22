package com.voiceconfig.app.ai

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地 ASR 门面：根据设置选择具体引擎。
 */
@Singleton
class LocalAsrManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: AsrModelManager,
) {
    @Volatile
    private var engine: AsrEngine? = null

    fun availableModels(): List<AsrModel> = modelManager.models

    fun selectedModel(): AsrModel = modelManager.selectedModel()

    fun selectModel(id: String) {
        modelManager.setSelectedModel(id)
        engine = null
    }

    fun isDownloaded(model: AsrModel): Boolean = modelManager.isDownloaded(model)

    fun modelSizeText(model: AsrModel): String = modelManager.modelSizeText(model)

    fun deleteModel(model: AsrModel) {
        modelManager.deleteModel(model)
        engine = null
    }

    suspend fun downloadModel(model: AsrModel, onProgress: (Float) -> Unit) {
        modelManager.download(model, onProgress)
        engine = null
    }

    suspend fun warmUp() {
        withContext(Dispatchers.IO) {
            currentEngine()
        }
    }

    fun isModelAvailable(): Boolean = currentEngine().isModelAvailable()

    fun cancel() {
        currentEngine().cancel()
    }

    fun recognize(
        maxDurationMs: Long = 30_000,
        onPartialResult: ((String) -> Unit)? = null,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        currentEngine().recognize(
            maxDurationMs = maxDurationMs,
            onPartialResult = onPartialResult,
            onResult = onResult,
            onError = onError,
        )
    }

    fun recognizeFile(
        model: AsrModel,
        wavPath: String,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        createEngine(model).recognizeFile(wavPath, onResult, onError)
    }

    private fun createEngine(model: AsrModel): AsrEngine {
        val dir = modelManager.modelDir(model)
        return when (model.kind) {
            AsrModelKind.SHERPA_STREAMING_TRANSDUCER,
            AsrModelKind.SHERPA_STREAMING_CTC,
            -> SherpaOnnxAsrEngine(context, dir, model.kind, model.threads)

            AsrModelKind.SENSEVOICE_OFFLINE -> SenseVoiceAsrEngine(context, dir, model.threads)
        }
    }

    private fun currentEngine(): AsrEngine {
        engine?.let { return it }
        val created = createEngine(modelManager.selectedModel())
        engine = created
        return created
    }
}
