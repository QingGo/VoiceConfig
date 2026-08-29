package com.voiceconfig.app.ai

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.getKwsModelConfig
import com.k2fsa.sherpa.onnx.getKeywordsFile
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicBoolean

/**
 * sherpa-onnx 本地关键词唤醒器。
 *
 * 使用 KeywordSpotter + AudioRecord 做低功耗本地唤醒；模型未内置时
 * [isAvailable] 返回 false，上层自动降级到系统 WakeWordDetector。
 */
@Singleton
class LocalSherpaKeywordSpotter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    interface Listener {
        fun onKeyword(keyword: String)
        fun onError(error: Exception)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null

    val isAvailable: Boolean
        get() = try {
            val dir = KWS_ASSET_DIR
            val files = context.assets.list(dir)?.toSet().orEmpty()
            REQUIRED_FILES.all { it in files }
        } catch (_: Exception) {
            false
        }

    fun start(listener: Listener): Boolean {
        if (!isAvailable || running.get()) return false
        return try {
            Log.i(TAG, "local KWS starting, asset dir=$KWS_ASSET_DIR")
            val config = buildConfig()
            val created = KeywordSpotter(context.assets, config)
            val createdStream = created.createStream("")
            val record = createAudioRecord() ?: error("无法创建 AudioRecord")
            spotter = created
            stream = createdStream
            audioRecord = record
            running.set(true)
            record.startRecording()
            worker = Thread {
                audioLoop(created, createdStream, record, listener)
            }.also { it.name = "sherpa-kws"; it.start() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "local KWS start failed", e)
            stop()
            false
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        Log.i(TAG, "local KWS stopped")
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { stream?.release() }
        stream = null
        runCatching { spotter?.release() }
        spotter = null
        worker?.interrupt()
        worker = null
    }

    private fun buildConfig(): KeywordSpotterConfig {
        val modelConfig = getKwsModelConfig(KWS_MODEL_INDEX)
            ?: error("KWS model config unavailable")
        val keywordsFile = getKeywordsFile(KWS_MODEL_INDEX)
        return KeywordSpotterConfig(
            featConfig = com.k2fsa.sherpa.onnx.FeatureConfig(
                sampleRate = SAMPLE_RATE,
                featureDim = 80,
                dither = 0f,
            ),
            modelConfig = modelConfig,
            maxActivePaths = 4,
            keywordsFile = keywordsFile,
            keywordsScore = 1.5f,
            keywordsThreshold = 0.25f,
            numTrailingBlanks = 2,
        )
    }

    private fun createAudioRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return null
        return AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 2, SAMPLE_RATE / 2),
        )
    }

    private fun audioLoop(
        spotter: KeywordSpotter,
        stream: OnlineStream,
        record: AudioRecord,
        listener: Listener,
    ) {
        val shortBuf = ShortArray(1024)
        val floatBuf = FloatArray(1024)
        try {
            while (running.get()) {
                val read = record.read(shortBuf, 0, shortBuf.size)
                if (read <= 0) continue
                for (i in 0 until read) {
                    floatBuf[i] = shortBuf[i] / 32768f
                }
                stream.acceptWaveform(floatBuf.copyOf(read), SAMPLE_RATE)
                while (spotter.isReady(stream)) {
                    spotter.decode(stream)
                }
                val result = runCatching { spotter.getResult(stream) }.getOrNull()
                val keyword = result?.keyword?.trim().orEmpty()
                if (keyword.isNotEmpty()) {
                    mainHandler.post { listener.onKeyword(keyword) }
                    spotter.reset(stream)
                }
            }
        } catch (e: Exception) {
            if (running.get()) {
                mainHandler.post { listener.onError(e) }
            }
        } finally {
            if (running.get()) {
                stop()
            }
        }
    }

    companion object {
        private const val TAG = "LocalKWS"
        private const val SAMPLE_RATE = 16_000
        private const val KWS_MODEL_INDEX = 0
        private const val KWS_ASSET_DIR = "sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01"
        private val REQUIRED_FILES = setOf(
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.int8.onnx",
            "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt",
            "keywords.txt",
        )
    }
}
