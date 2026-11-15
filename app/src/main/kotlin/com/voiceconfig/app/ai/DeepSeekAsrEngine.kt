package com.voiceconfig.app.ai

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.runBlocking

@Singleton
class DeepSeekAsrEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiKeyStore: ApiKeyStore,
    private val speechClient: DeepSeekSpeechClient,
) : AsrEngine {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var stopRequested = false
    @Volatile private var running = false

    override fun isModelAvailable(): Boolean =
        apiKeyStore.speechCloudConsent &&
            apiKeyStore.deepSeekApiKey.isNotBlank()

    override fun warmUp() = Unit

    override fun recognize(
        maxDurationMs: Long,
        onPartialResult: ((String) -> Unit)?,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (running) {
            onError("语音识别正在进行中")
            return
        }
        running = true
        stopRequested = false
        val effectiveMaxDurationMs = minOf(maxDurationMs, 120_000L)
        onPartialResult?.invoke("录音中…")
        Thread {
            val file = File(context.cacheDir, "deepseek_asr_${System.currentTimeMillis()}.wav")
            var record: AudioRecord? = null
            try {
                val sampleRate = 16_000
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                val bufferSize = maxOf(minBuf * 4, sampleRate)
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
                record = recorder
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("无法初始化麦克风")
                }
                recorder.startRecording()
                FileOutputStream(file).use { out ->
                    writeWavHeader(out, 0)
                    val buf = ShortArray(minBuf)
                    val pcm = ByteArray(minBuf * 2)
                    val start = System.currentTimeMillis()
                    var silenceMs = 0
                    var heardSpeech = false
                    while (!stopRequested && System.currentTimeMillis() - start < effectiveMaxDurationMs) {
                        val read = recorder.read(buf, 0, buf.size)
                        if (read <= 0) continue
                        var peak = 0
                        var i = 0
                        while (i < read) {
                            val sample = buf[i].toInt()
                            if (abs(sample) > peak) peak = abs(sample)
                            pcm[i * 2] = (sample and 0xff).toByte()
                            pcm[i * 2 + 1] = ((sample shr 8) and 0xff).toByte()
                            i++
                        }
                        out.write(pcm, 0, read * 2)
                        if (peak > 1_500) {
                            heardSpeech = true
                            silenceMs = 0
                        } else if (heardSpeech) {
                            silenceMs += read * 1000 / sampleRate
                            if (silenceMs >= 1_200) break
                        }
                    }
                    out.fd.sync()
                }
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                record = null
                val length = (file.length() - 44).coerceAtLeast(0).toInt()
                FileOutputStream(file, true).use { } // ensure closed
                patchWavSize(file, length)
                if (length < 3200) {
                    mainHandler.post { onError("录音太短，请再说一次") }
                    return@Thread
                }
                mainHandler.post { onPartialResult?.invoke("识别中…") }
                val result = runBlocking {
                    speechClient.transcribe(file)
                }
                result.onSuccess { text -> mainHandler.post { onResult(text) } }
                    .onFailure { e -> mainHandler.post { onError(e.message ?: "DeepSeek ASR 失败") } }
            } catch (e: Exception) {
                mainHandler.post { onError(e.message ?: "DeepSeek ASR 失败") }
            } finally {
                runCatching { record?.stop() }
                runCatching { record?.release() }
                file.delete()
                running = false
            }
        }.start()
    }

    override fun cancel() {
        stopRequested = true
    }

    override fun recognizeFile(
        wavPath: String,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        language: String?,
    ) {
        Thread {
            val result = runBlocking {
                speechClient.transcribe(File(wavPath))
            }
            result.onSuccess { mainHandler.post { onResult(it) } }
                .onFailure { mainHandler.post { onError(it.message ?: "DeepSeek ASR 失败") } }
        }.start()
    }

    private fun writeWavHeader(out: FileOutputStream, dataLength: Int) {
        val sampleRate = 16_000
        val channels = 1
        val bits = 16
        val byteRate = sampleRate * channels * bits / 8
        fun le16(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
        fun le32(v: Int) {
            out.write(v and 0xff); out.write((v shr 8) and 0xff)
            out.write((v shr 16) and 0xff); out.write((v shr 24) and 0xff)
        }
        out.write("RIFF".toByteArray()); le32(36 + dataLength); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(channels); le32(sampleRate)
        le32(byteRate); le16(channels * bits / 8); le16(bits); out.write("data".toByteArray()); le32(dataLength)
    }

    private fun patchWavSize(file: File, dataLength: Int) {
        val raf = java.io.RandomAccessFile(file, "rw")
        try {
            raf.seek(4); writeLe32(raf, 36 + dataLength)
            raf.seek(40); writeLe32(raf, dataLength)
        } finally { raf.close() }
    }

    private fun writeLe32(raf: java.io.RandomAccessFile, v: Int) {
        raf.write(v and 0xff); raf.write((v shr 8) and 0xff)
        raf.write((v shr 16) and 0xff); raf.write((v shr 24) and 0xff)
    }
}
