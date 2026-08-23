package com.voiceconfig.app.ai

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.HomophoneReplacerConfig
import com.k2fsa.sherpa.onnx.OfflineCohereTranscribeModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 非流式 LLM-ASR 引擎，目前支持：
 * - Qwen3-ASR 0.6B ONNX int8
 * - Cohere Transcribe ONNX int8
 */
class OfflineLlmAsrEngine(
    private val context: Context,
    private val modelDir: String,
    private val modelKind: AsrModelKind,
    private val numThreads: Int = 2,
    private val defaultLanguage: String = "",
    private val provider: String = "cpu",
) : AsrEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var recognizer: OfflineRecognizer? = null
    private var currentCancel: (() -> Unit)? = null

    companion object {
        private const val TIMING_TAG = "VoiceConfigAsrTiming"
    }

    override fun isModelAvailable(): Boolean {
        val required = when (modelKind) {
            AsrModelKind.QWEN3_ASR_OFFLINE -> listOf(
                "$modelDir/conv_frontend.onnx",
                "$modelDir/encoder.int8.onnx",
                "$modelDir/decoder.int8.onnx",
                "$modelDir/tokenizer/merges.txt",
                "$modelDir/tokenizer/vocab.json",
                "$modelDir/tokenizer/tokenizer_config.json",
            )
            AsrModelKind.COHERE_TRANSCRIBE_OFFLINE -> listOf(
                "$modelDir/encoder.int8.onnx",
                "$modelDir/encoder.int8.onnx.data",
                "$modelDir/decoder.int8.onnx",
                "$modelDir/tokens.txt",
            )
            else -> emptyList()
        }
        return required.all { File(it).exists() }
    }

    override fun cancel() {
        synchronized(lock) {
            currentCancel?.invoke()
        }
    }

    override fun warmUp() {
        getRecognizer()
    }

    override fun recognize(
        maxDurationMs: Long,
        onPartialResult: ((String) -> Unit)?,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val stopRequested = AtomicBoolean(false)
        val cancelFn: () -> Unit = { stopRequested.set(true) }
        synchronized(lock) {
            currentCancel = cancelFn
        }
        Thread {
            try {
                val sampleRate = 16_000
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                val bufferSize = maxOf(minBuf * 8, sampleRate * 2 * 2)
                val record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
                record.startRecording()
                val start = System.currentTimeMillis()
                val shortBuf = ShortArray(minBuf)
                val samples = ArrayList<Float>(sampleRate * 30)
                var speechStarted = false
                var lastVoiceAt = start
                var silenceMs = 0L

                while (!stopRequested.get() && System.currentTimeMillis() - start < maxDurationMs) {
                    val read = record.read(shortBuf, 0, shortBuf.size)
                    if (read <= 0) continue
                    var energy = 0.0
                    for (i in 0 until read) {
                        val v = shortBuf[i] / 32768f
                        samples.add(v)
                        energy += abs(v.toDouble())
                    }
                    val rms = energy / read
                    if (rms > 0.01) {
                        speechStarted = true
                        lastVoiceAt = System.currentTimeMillis()
                        silenceMs = 0
                    } else if (speechStarted) {
                        silenceMs = System.currentTimeMillis() - lastVoiceAt
                        if (silenceMs >= 1_200) {
                            break
                        }
                    }
                }

                runCatching { record.stop() }
                runCatching { record.release() }

                if (stopRequested.get()) {
                    mainHandler.post { onError("已取消") }
                    return@Thread
                }
                if (!speechStarted || samples.isEmpty()) {
                    mainHandler.post { onError("没有听清，请再说一次") }
                    return@Thread
                }

                val recordEndAt = System.currentTimeMillis()
                val rec = getRecognizer()
                val modelReadyAt = System.currentTimeMillis()
                val stream = rec.createStream()
                configureStream(stream, defaultLanguage)
                stream.acceptWaveform(samples.toFloatArray(), sampleRate)
                rec.decode(stream)
                val text = rec.getResult(stream).text
                val finishAt = System.currentTimeMillis()
                Log.i(
                    TIMING_TAG,
                    "${modelKind.name.lowercase()} total=${finishAt - start}ms " +
                        "speechStart=${lastVoiceAt - start}ms " +
                        "recordEnd=${recordEndAt - start}ms " +
                        "modelInit=${modelReadyAt - recordEndAt}ms " +
                        "decode=${finishAt - modelReadyAt}ms " +
                        "final=$text",
                )
                stream.release()

                if (text.isNotBlank()) {
                    mainHandler.post { onResult(text) }
                } else {
                    mainHandler.post { onError("没有听清，请再说一次") }
                }
            } catch (e: Exception) {
                mainHandler.post { onError(e.message ?: "离线大模型识别失败") }
            } finally {
                synchronized(lock) {
                    if (currentCancel === cancelFn) {
                        currentCancel = null
                    }
                }
            }
        }.start()
    }

    override fun recognizeFile(
        wavPath: String,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        language: String?,
    ) {
        Thread {
            try {
                val requestStart = System.currentTimeMillis()
                val samples = readWav(wavPath)
                val readWavMs = System.currentTimeMillis() - requestStart
                val rec = getRecognizer()
                val modelReadyAt = System.currentTimeMillis()
                val stream = rec.createStream()
                configureStream(stream, language ?: defaultLanguage)
                stream.acceptWaveform(samples, 16_000)
                rec.decode(stream)
                val text = rec.getResult(stream).text
                val finishAt = System.currentTimeMillis()
                Log.i(
                    TIMING_TAG,
                    "${modelKind.name.lowercase()}-file total=${finishAt - requestStart}ms " +
                        "readWav=${readWavMs}ms " +
                        "modelInit=${modelReadyAt - requestStart - readWavMs}ms " +
                        "decode=${finishAt - modelReadyAt}ms " +
                        "text=$text",
                )
                stream.release()
                if (text.isNotBlank()) {
                    mainHandler.post { onResult(text) }
                } else {
                    mainHandler.post { onError("没有识别到文字") }
                }
            } catch (e: Exception) {
                mainHandler.post { onError(e.message ?: "文件识别失败") }
            }
        }.start()
    }

    private fun configureStream(stream: OfflineStream, language: String) {
        if (modelKind == AsrModelKind.COHERE_TRANSCRIBE_OFFLINE) {
            val lang = language.ifBlank { defaultLanguage.ifBlank { "zh" } }
            stream.setOption("language", lang)
            stream.setOption("use_punct", "true")
            stream.setOption("use_itn", "true")
        }
    }

    private fun readWav(path: String): FloatArray = AsrWavReader.readToMono16k(path)

    private fun getRecognizer(): OfflineRecognizer {
        synchronized(lock) {
            recognizer?.let { return it }
            val offlineModelConfig = OfflineModelConfig().apply {
                when (modelKind) {
                    AsrModelKind.QWEN3_ASR_OFFLINE -> {
                        qwen3Asr = OfflineQwen3AsrModelConfig(
                            "$modelDir/conv_frontend.onnx",
                            "$modelDir/encoder.int8.onnx",
                            "$modelDir/decoder.int8.onnx",
                            "$modelDir/tokenizer",
                            512,
                            128,
                            1e-6f,
                            0.8f,
                            42,
                            "",
                        )
                        modelType = "qwen3_asr"
                    }
                    AsrModelKind.COHERE_TRANSCRIBE_OFFLINE -> {
                        cohereTranscribe = OfflineCohereTranscribeModelConfig(
                            "$modelDir/encoder.int8.onnx",
                            "$modelDir/decoder.int8.onnx",
                            defaultLanguage,
                            true,
                            true,
                        )
                        tokens = "$modelDir/tokens.txt"
                        modelType = "cohere-transcribe"
                    }
                    else -> error("Unsupported offline LLM ASR kind: $modelKind")
                }
                numThreads = numThreads
                debug = false
                provider = provider
            }
            val config = OfflineRecognizerConfig().apply {
                featConfig = FeatureConfig(16_000, 128, 0f)
                modelConfig = offlineModelConfig
                hr = HomophoneReplacerConfig("", "", "")
                decodingMethod = "greedy_search"
                maxActivePaths = 4
                hotwordsFile = ""
                hotwordsScore = 1.5f
                ruleFsts = ""
                ruleFars = ""
                blankPenalty = 0f
            }
            val created = if (modelDir.startsWith("/") || modelDir.contains(":")) {
                SherpaOnnxFileLoader.newOfflineRecognizerFromFile(config)
            } else {
                OfflineRecognizer(context.assets, config)
            }
            recognizer = created
            return created
        }
    }
}
