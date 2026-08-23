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
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * SenseVoice-small 离线识别引擎（非流式）。
 * 录音到静音后一次性识别，适合“说完再出结果”的对比测试。
 */
class SenseVoiceAsrEngine(
    private val context: Context,
    private val modelDir: String,
    private val numThreads: Int = 2,
    private val provider: String = "cpu",
) : AsrEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var recognizer: OfflineRecognizer? = null
    private var currentCancel: (() -> Unit)? = null

    companion object {
        private const val TIMING_TAG = "VoiceConfigAsrTiming"
    }

    override fun isModelAvailable(): Boolean =
        listOf("$modelDir/model.int8.onnx", "$modelDir/tokens.txt").all { File(it).exists() }

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
                val shortBuf = ShortArray(minBuf)
                val samples = ArrayList<Float>(sampleRate * 30)
                val start = System.currentTimeMillis()
                var speechStartedAt: Long? = null
                var speechStarted = false
                var silenceMs = 0L
                var lastVoiceAt = start
                var endpointAt: Long? = null

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
                        if (speechStartedAt == null) speechStartedAt = System.currentTimeMillis()
                        lastVoiceAt = System.currentTimeMillis()
                        silenceMs = 0
                    } else if (speechStarted) {
                        silenceMs = System.currentTimeMillis() - lastVoiceAt
                        if (silenceMs >= 1_200) {
                            endpointAt = System.currentTimeMillis()
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
                val decodeStartAt = recordEndAt
                val rec = getRecognizer()
                val modelReadyAt = System.currentTimeMillis()
                val stream = rec.createStream()
                stream.acceptWaveform(samples.toFloatArray(), sampleRate)
                rec.decode(stream)
                val text = rec.getResult(stream).text
                val finishAt = System.currentTimeMillis()
                Log.i(
                    TIMING_TAG,
                    "sensevoice total=${finishAt - start}ms " +
                        "speechStart=${speechStartedAt?.minus(start) ?: -1}ms " +
                        "recordEnd=${recordEndAt - start}ms " +
                        "modelInit=${modelReadyAt - decodeStartAt}ms " +
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
                mainHandler.post { onError(e.message ?: "SenseVoice 识别失败") }
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
                stream.acceptWaveform(samples, 16_000)
                rec.decode(stream)
                val text = rec.getResult(stream).text
                val finishAt = System.currentTimeMillis()
                Log.i(
                    TIMING_TAG,
                    "sensevoice-file total=${finishAt - requestStart}ms readWav=${readWavMs}ms " +
                        "modelInit=${modelReadyAt - requestStart - readWavMs}ms decode=${finishAt - modelReadyAt}ms " +
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

    private fun readWav(path: String): FloatArray = AsrWavReader.readToMono16k(path)

    private fun getRecognizer(): OfflineRecognizer {
        synchronized(lock) {
            recognizer?.let { return it }
            val offlineModelConfig = OfflineModelConfig().apply {
                senseVoice = OfflineSenseVoiceModelConfig(
                    "$modelDir/model.int8.onnx",
                    "auto",
                    true,
                )
                tokens = "$modelDir/tokens.txt"
                numThreads = numThreads
                debug = false
                provider = provider
                modelType = "sensevoice"
                modelingUnit = "cjkchar"
                bpeVocab = ""
            }
            val config = OfflineRecognizerConfig().apply {
                featConfig = FeatureConfig(16_000, 80, 0f)
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
