package com.voiceconfig.app.ai

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.HomophoneReplacerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File
import java.io.DataInputStream
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * SenseVoice-small 离线识别引擎（非流式）。
 * 录音到静音后一次性识别，适合“说完再出结果”的对比测试。
 */
class SenseVoiceAsrEngine(
    private val context: Context,
    private val modelDir: String,
) : AsrEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var recognizer: OfflineRecognizer? = null
    private var currentCancel: (() -> Unit)? = null

    override fun isModelAvailable(): Boolean =
        listOf("$modelDir/model.int8.onnx", "$modelDir/tokens.txt").all { File(it).exists() }

    override fun cancel() {
        synchronized(lock) {
            currentCancel?.invoke()
        }
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
                var speechStarted = false
                var silenceMs = 0L
                var lastVoiceAt = start

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

                val rec = getRecognizer()
                val stream = rec.createStream()
                stream.acceptWaveform(samples.toFloatArray(), sampleRate)
                rec.decode(stream)
                val text = rec.getResult(stream).text
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
    ) {
        Thread {
            try {
                val samples = readWav(wavPath)
                val rec = getRecognizer()
                val stream = rec.createStream()
                stream.acceptWaveform(samples, 16_000)
                rec.decode(stream)
                val text = rec.getResult(stream).text
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

    private fun readWav(path: String): FloatArray {
        val input = DataInputStream(FileInputStream(path))
        try {
            require(readString(input, 4) == "RIFF")
            readLeInt(input)
            require(readString(input, 4) == "WAVE")
            var channels = 1
            var sampleRate = 16_000
            var bitsPerSample = 16
            var data: ByteArray? = null
            while (input.available() > 0) {
                val id = readString(input, 4)
                val size = readLeInt(input)
                when (id) {
                    "fmt " -> {
                        readLeShort(input) // audio format
                        channels = readLeShort(input)
                        sampleRate = readLeInt(input)
                        readLeInt(input) // byte rate
                        readLeShort(input) // block align
                        bitsPerSample = readLeShort(input)
                        if (size > 16) input.skipBytes(size - 16)
                    }
                    "data" -> {
                        data = ByteArray(size)
                        input.readFully(data)
                    }
                    else -> input.skipBytes(size)
                }
            }
            val bytes = data ?: error("no data chunk")
            val bytesPerSample = bitsPerSample / 8
            val samples = ArrayList<Float>(bytes.size / bytesPerSample)
            var i = 0
            while (i + bytesPerSample <= bytes.size) {
                val low = bytes[i].toInt() and 0xff
                val high = if (bytesPerSample > 1) bytes[i + 1].toInt() else 0
                val sample = (high shl 8 or low).toShort().toFloat() / 32768f
                if (channels == 1) {
                    samples.add(sample)
                } else if ((i / bytesPerSample) % channels == 0) {
                    samples.add(sample)
                }
                i += bytesPerSample
            }
            val raw = samples.toFloatArray()
            return if (sampleRate == 16_000) raw else resample(raw, sampleRate, 16_000)
        } finally {
            input.close()
        }
    }

    private fun readLeInt(input: DataInputStream): Int {
        val b0 = input.readUnsignedByte()
        val b1 = input.readUnsignedByte()
        val b2 = input.readUnsignedByte()
        val b3 = input.readUnsignedByte()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun readLeShort(input: DataInputStream): Int {
        val b0 = input.readUnsignedByte()
        val b1 = input.readUnsignedByte()
        return b0 or (b1 shl 8)
    }

    private fun readString(input: DataInputStream, len: Int): String {
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate) return input
        val ratio = fromRate.toDouble() / toRate.toDouble()
        val outSize = (input.size / ratio).toInt()
        val out = FloatArray(outSize)
        for (i in out.indices) {
            val src = (i * ratio).toInt()
            out[i] = input[src.coerceIn(input.indices)]
        }
        return out
    }

    private fun getRecognizer(): OfflineRecognizer {
        synchronized(lock) {
            recognizer?.let { return it }
            val offlineModelConfig = OfflineModelConfig().apply {
                senseVoice = OfflineSenseVoiceModelConfig(
                    "$modelDir/model.int8.onnx",
                    "zh",
                    true,
                )
                tokens = "$modelDir/tokens.txt"
                numThreads = 2
                debug = false
                provider = "cpu"
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
