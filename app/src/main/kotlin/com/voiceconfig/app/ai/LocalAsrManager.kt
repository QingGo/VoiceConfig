package com.voiceconfig.app.ai

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class AsrEngineStatus {
    COLD,
    WARMING_UP,
    READY,
    UNAVAILABLE,
}

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
    private val engineLock = Any()
    private val _engineStatus = MutableStateFlow(AsrEngineStatus.COLD)
    val engineStatus: StateFlow<AsrEngineStatus> = _engineStatus.asStateFlow()

    fun availableModels(): List<AsrModel> = modelManager.models

    fun selectedModel(): AsrModel = modelManager.selectedModel()

    fun selectModel(id: String) {
        modelManager.setSelectedModel(id)
        engine = null
        _engineStatus.value = AsrEngineStatus.COLD
    }

    fun isDownloaded(model: AsrModel): Boolean = modelManager.isDownloaded(model)

    fun modelSizeText(model: AsrModel): String = modelManager.modelSizeText(model)

    fun deleteModel(model: AsrModel) {
        modelManager.deleteModel(model)
        engine = null
        _engineStatus.value = AsrEngineStatus.COLD
    }

    suspend fun downloadModel(model: AsrModel, onProgress: (Float) -> Unit) {
        modelManager.download(model, onProgress)
        engine = null
        _engineStatus.value = AsrEngineStatus.COLD
    }

    suspend fun warmUp() {
        withContext(Dispatchers.IO) {
            warmUpSync()
        }
    }

    fun warmUpSync() {
        if (engine == null) {
            _engineStatus.value = AsrEngineStatus.WARMING_UP
        }
        val created = currentEngine()
        if (created.isModelAvailable()) {
            created.warmUp()
            _engineStatus.value = AsrEngineStatus.READY
        } else {
            _engineStatus.value = AsrEngineStatus.UNAVAILABLE
        }
    }

    /** 为指定模型预热并缓存，用于 benchmark 验证 warm 路径；不改变用户选中项。 */
    fun prepareModel(model: AsrModel) {
        _engineStatus.value = AsrEngineStatus.WARMING_UP
        val created = createEngine(model)
        synchronized(engineLock) {
            engine = created
        }
        if (created.isModelAvailable()) {
            created.warmUp()
            _engineStatus.value = AsrEngineStatus.READY
        } else {
            _engineStatus.value = AsrEngineStatus.UNAVAILABLE
        }
    }

    fun isModelAvailable(): Boolean = engine?.isModelAvailable() == true

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
        threadsOverride: Int? = null,
        useCachedEngine: Boolean = false,
        language: String? = null,
        provider: String? = null,
    ) {
        val engine = if (useCachedEngine) {
            currentEngine()
        } else {
            createEngine(model, threadsOverride, provider)
        }
        engine.recognizeFile(wavPath, onResult, onError, language)
    }

    private fun createEngine(
        model: AsrModel,
        threadsOverride: Int? = null,
        providerOverride: String? = null,
    ): AsrEngine {
        val dir = modelManager.modelDir(model)
        val threads = threadsOverride ?: model.threads
        val provider = providerOverride ?: model.provider
        return when (model.kind) {
            AsrModelKind.SHERPA_STREAMING_TRANSDUCER,
            AsrModelKind.SHERPA_STREAMING_ZIPFORMER2_TRANSDUCER,
            AsrModelKind.SHERPA_STREAMING_PARAFORMER,
            AsrModelKind.SHERPA_STREAMING_CTC,
            -> SherpaOnnxAsrEngine(
                context,
                dir,
                model.kind,
                threads,
                model.modelingUnit,
                model.modelType,
                provider,
            )

            AsrModelKind.SENSEVOICE_OFFLINE -> SenseVoiceAsrEngine(context, dir, threads, provider)
            AsrModelKind.QWEN3_ASR_OFFLINE,
            AsrModelKind.COHERE_TRANSCRIBE_OFFLINE,
            -> OfflineLlmAsrEngine(
                context,
                dir,
                model.kind,
                threads,
                model.language,
                provider,
            )
        }
    }

    private fun currentEngine(): AsrEngine {
        engine?.let { return it }
        synchronized(engineLock) {
            engine?.let { return it }
            val created = createEngine(modelManager.selectedModel())
            engine = created
            return created
        }
    }
}
