package com.voiceconfig.app.ai

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.HomophoneReplacerConfig
import com.k2fsa.sherpa.onnx.OnlineCtcFstDecoderConfig
import com.k2fsa.sherpa.onnx.OnlineLMConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineNeMoCtcModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import java.io.File
import java.io.DataInputStream
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * sherpa-onnx 流式识别引擎。
 * 支持：
 * - 旧版 Zipformer Transducer（encoder/decoder/joiner）
 * - 新版 Zipformer CTC（model.int8.onnx + tokens + bbpe）
 */
class SherpaOnnxAsrEngine(
    private val context: Context,
    private val modelDir: String,
    private val modelKind: AsrModelKind,
) : AsrEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var recognizer: OnlineRecognizer? = null
    private var currentCancel: (() -> Unit)? = null

    companion object {
        private const val TIMING_TAG = "VoiceConfigAsrTiming"
    }

    override fun isModelAvailable(): Boolean {
        val required = when (modelKind) {
            AsrModelKind.SHERPA_STREAMING_TRANSDUCER -> listOf(
                "$modelDir/tokens.txt",
                "$modelDir/encoder-epoch-99-avg-1.int8.onnx",
                "$modelDir/decoder-epoch-99-avg-1.int8.onnx",
                "$modelDir/joiner-epoch-99-avg-1.int8.onnx",
            )
            AsrModelKind.SHERPA_STREAMING_CTC -> listOf(
                "$modelDir/tokens.txt",
                "$modelDir/model.int8.onnx",
                "$modelDir/bbpe.model",
            )
            else -> emptyList()
        }
        return required.all { path ->
            if (path.startsWith("/") || path.contains(":")) {
                File(path).exists()
            } else {
                runCatching { context.assets.open(path).close() }.isSuccess
            }
        }
    }

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
                // 使用较大的 AudioRecord buffer，在模型初始化期间也能缓存用户开头的语音。
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
                // 模型初始化可能较慢，但 AudioRecord 已经开始缓存，避免丢失开头。
                val rec = getRecognizer()
                val modelReadyAtMs = System.currentTimeMillis()
                val stream = rec.createStream("")
                val shortBuf = ShortArray(minBuf)
                val floatBuf = FloatArray(minBuf)
                var lastEmittedPartial = ""
                var firstPartialAtMs: Long? = null
                var endpointAtMs: Long? = null

                while (!stopRequested.get() && System.currentTimeMillis() - start < maxDurationMs) {
                    val read = record.read(shortBuf, 0, shortBuf.size)
                    if (read > 0) {
                        for (i in 0 until read) {
                            floatBuf[i] = shortBuf[i] / 32768f
                        }
                        stream.acceptWaveform(floatBuf.copyOf(read), sampleRate)
                        while (rec.isReady(stream)) {
                            rec.decode(stream)
                            if (rec.isEndpoint(stream)) break
                        }
                        val text = rec.getResult(stream).text
                        if (text.isNotBlank() && text != lastEmittedPartial) {
                            lastEmittedPartial = text
                            if (firstPartialAtMs == null) firstPartialAtMs = System.currentTimeMillis()
                            val partial = text
                            mainHandler.post { onPartialResult?.invoke(partial) }
                        }
                        if (rec.isEndpoint(stream)) {
                            if (endpointAtMs == null) endpointAtMs = System.currentTimeMillis()
                            break
                        }
                    }
                }

                stream.inputFinished()
                while (rec.isReady(stream)) {
                    rec.decode(stream)
                }
                val finalText = rec.getResult(stream).text
                val finishAtMs = System.currentTimeMillis()

                Log.i(
                    TIMING_TAG,
                    "streaming total=${finishAtMs - start}ms " +
                        "modelInit=${modelReadyAtMs - start}ms " +
                        "firstPartial=${firstPartialAtMs?.minus(start) ?: -1}ms " +
                        "endpoint=${endpointAtMs?.minus(start) ?: -1}ms " +
                        "final=$finalText",
                )

                runCatching { record.stop() }
                runCatching { record.release() }
                stream.release()

                if (stopRequested.get()) {
                    mainHandler.post { onError("已取消") }
                } else if (finalText.isNotBlank()) {
                    mainHandler.post { onResult(finalText) }
                } else {
                    mainHandler.post { onError("没有听清，请再说一次") }
                }
            } catch (e: Exception) {
                mainHandler.post { onError(e.message ?: "本地语音识别失败") }
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
                val requestStart = System.currentTimeMillis()
                val samples = readWav(wavPath)
                val readWavMs = System.currentTimeMillis() - requestStart
                val rec = getRecognizer()
                val modelInitMs = System.currentTimeMillis() - requestStart - readWavMs
                val stream = rec.createStream("")
                stream.acceptWaveform(samples, 16_000)
                while (rec.isReady(stream)) {
                    rec.decode(stream)
                }
                stream.inputFinished()
                while (rec.isReady(stream)) {
                    rec.decode(stream)
                }
                val text = rec.getResult(stream).text
                val totalMs = System.currentTimeMillis() - requestStart
                Log.i(
                    TIMING_TAG,
                    "file total=${totalMs}ms readWav=${readWavMs}ms modelInit=${modelInitMs}ms text=$text",
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

    private fun getRecognizer(): OnlineRecognizer {
        synchronized(lock) {
            recognizer?.let { return it }
            val modelConfig = when (modelKind) {
                AsrModelKind.SHERPA_STREAMING_TRANSDUCER -> OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$modelDir/encoder-epoch-99-avg-1.int8.onnx",
                        decoder = "$modelDir/decoder-epoch-99-avg-1.int8.onnx",
                        joiner = "$modelDir/joiner-epoch-99-avg-1.int8.onnx",
                    ),
                    tokens = "$modelDir/tokens.txt",
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                    modelType = "zipformer",
                    modelingUnit = "cjkchar",
                    bpeVocab = "",
                )
                AsrModelKind.SHERPA_STREAMING_CTC -> OnlineModelConfig(
                    zipformer2Ctc = OnlineZipformer2CtcModelConfig("$modelDir/model.int8.onnx"),
                    tokens = "$modelDir/tokens.txt",
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                    modelType = "zipformer2_ctc",
                    modelingUnit = "cjkchar",
                    bpeVocab = "$modelDir/bbpe.model",
                )
                else -> error("Unsupported sherpa model kind")
            }
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(
                    sampleRate = 16_000,
                    featureDim = 80,
                    dither = 0f,
                ),
                modelConfig = modelConfig,
                lmConfig = OnlineLMConfig("", 0f),
                ctcFstDecoderConfig = OnlineCtcFstDecoderConfig("", 0),
                hr = HomophoneReplacerConfig("", "", ""),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(true, 2.4f, 0f),
                    rule2 = EndpointRule(true, 4.8f, 0f),
                    rule3 = EndpointRule(false, 6.0f, 0f),
                ),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
                maxActivePaths = 4,
                hotwordsFile = "",
                hotwordsScore = 1.5f,
                ruleFsts = "",
                ruleFars = "",
                blankPenalty = 0f,
            )
            val created = if (isFileModelDir(modelDir)) {
                SherpaOnnxFileLoader.newOnlineRecognizerFromFile(config)
            } else {
                OnlineRecognizer(context.assets, config)
            }
            recognizer = created
            return created
        }
    }

    private fun isFileModelDir(dir: String): Boolean =
        dir.startsWith("/") || dir.contains(":")
}
