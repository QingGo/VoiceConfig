package com.voiceconfig.app.ai

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * transcribe.cpp / GGUF 离线 ASR 引擎。
 *
 * 当前主要用于 Qwen3-ASR GGUF（Q4_K_M），在 Android 上通过自编译
 * libtranscribe_jni.so 调用 transcribe.cpp 的稳定 C ABI。
 */
class TranscribeCppAsrEngine(
    private val modelDir: String,
    private val numThreads: Int = 4,
    private val defaultLanguage: String = "",
    private val modelFileName: String = "Qwen3-ASR-0.6B-Q4_K_M.gguf",
) : AsrEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var handle: Long = 0L
    private var currentCancel: (() -> Unit)? = null
    @Volatile
    private var libraryLoaded: Boolean = false

    companion object {
        private const val TAG = "VoiceConfigAsrTiming"
        private const val LIBRARY_NAME = "transcribe_jni"
    }

    override fun isModelAvailable(): Boolean =
        File(modelDir, modelFileName).exists()

    override fun cancel() {
        synchronized(lock) {
            currentCancel?.invoke()
            if (handle != 0L) nativeCancel(handle)
        }
    }

    override fun warmUp() {
        getHandle()
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
                val samples = ArrayList<Float>(sampleRate * 30)
                val shortBuf = ShortArray(minBuf)
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
                        if (silenceMs >= 1_200) break
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

                val session = getHandle()
                val text = nativeTranscribe(session, samples.toFloatArray(), defaultLanguage)
                if (text.isNotBlank()) {
                    mainHandler.post { onResult(text) }
                } else {
                    mainHandler.post { onError("没有识别到文字") }
                }
            } catch (e: Exception) {
                mainHandler.post { onError(e.message ?: "transcribe.cpp 识别失败") }
            } finally {
                synchronized(lock) {
                    if (currentCancel === cancelFn) currentCancel = null
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
                val samples = AsrWavReader.readToMono16k(wavPath)
                val readWavMs = System.currentTimeMillis() - requestStart
                val modelInitStart = System.currentTimeMillis()
                val session = getHandle()
                val modelReadyAt = System.currentTimeMillis()
                val text = nativeTranscribe(session, samples, language ?: defaultLanguage)
                val finishAt = System.currentTimeMillis()
                Log.i(
                    TAG,
                    "transcribe-cpp-file total=${finishAt - requestStart}ms " +
                        "readWav=${readWavMs}ms modelInit=${modelReadyAt - modelInitStart}ms " +
                        "decode=${finishAt - modelReadyAt}ms text=$text",
                )
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

    private fun getHandle(): Long {
        synchronized(lock) {
            if (handle != 0L) return handle
            ensureNativeLibrary()
            val modelFile = File(modelDir, modelFileName)
            require(modelFile.exists()) { "GGUF model not found: ${modelFile.absolutePath}" }
            val opened = nativeOpen(modelFile.absolutePath, numThreads)
            require(opened != 0L) { "transcribe.cpp nativeOpen failed" }
            handle = opened
            return opened
        }
    }

    private fun ensureNativeLibrary() {
        if (libraryLoaded) return
        try {
            System.loadLibrary(LIBRARY_NAME)
            libraryLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException(
                "libtranscribe_jni.so 未找到；请先运行 scripts/build_transcribe_cpp_android.sh",
                e,
            )
        }
    }

    private external fun nativeOpen(modelPath: String, threads: Int): Long
    private external fun nativeClose(handle: Long)
    private external fun nativeCancel(handle: Long)
    private external fun nativeTranscribe(handle: Long, samples: FloatArray, language: String?): String

    @Suppress("DEPRECATION")
    protected fun finalize() {
        val h = synchronized(lock) { handle.also { handle = 0L } }
        if (h != 0L) {
            runCatching { nativeClose(h) }
        }
    }
}
