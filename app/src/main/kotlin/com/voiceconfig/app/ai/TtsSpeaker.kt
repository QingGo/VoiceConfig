package com.voiceconfig.app.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@Singleton
class TtsSpeaker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiKeyStore: ApiKeyStore,
    private val speechClient: DeepSeekSpeechClient,
    private val streamClient: DeepSeekTtsStreamClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tts: TextToSpeech? = null
    private var ready = false
    private var currentJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var streamSession: DeepSeekTtsStreamSession? = null
    private var audioTrack: AudioTrack? = null
    @Volatile private var cloudSequence = 0L

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.CHINA)
                ready = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    fun speak(text: String) {
        val cleaned = SpeechTextCleaner.cleanForSpeech(text)
        if (cleaned.isBlank()) return
        when {
            apiKeyStore.ttsProvider == "DEEPSEEK" &&
                apiKeyStore.speechCloudConsent -> speakCloud(cleaned)
            else -> speakSystem(cleaned)
        }
    }

    private fun speakSystem(cleaned: String) {
        if (!ready) return
        val utteranceId = UUID.randomUUID().toString()
        tts?.speak(cleaned, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun speakCloud(text: String) {
        currentJob?.cancel()
        stopMedia()
        stopStreaming()
        val sequence = ++cloudSequence
        if (apiKeyStore.deepSeekTtsStreamingEnabled) {
            startStreaming(text, sequence)
        } else {
            currentJob = scope.launch { speakCloudHttp(text, sequence) }
        }
    }

    private fun startStreaming(text: String, sequence: Long) {
        val track = runCatching {
            val minBuffer = AudioTrack.getMinBufferSize(
                STREAM_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(STREAM_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer * 2, STREAM_SAMPLE_RATE))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrElse {
            fallbackFromStreaming(text)
            return
        }
        audioTrack = track
        runCatching { track.play() }.onFailure {
            fallbackFromStreaming(text)
            return
        }
        var totalPcmBytes = 0L
        streamSession = streamClient.start(
            text = text,
            voiceId = apiKeyStore.deepSeekTtsVoice,
            onAudio = { bytes ->
                if (sequence == cloudSequence) {
                    totalPcmBytes += bytes.size
                    runCatching { track.write(bytes, 0, bytes.size, AudioTrack.WRITE_BLOCKING) }
                }
            },
            onComplete = {
                if (sequence == cloudSequence) {
                    waitForPlaybackAndRelease(track, totalPcmBytes)
                }
            },
            onError = {
                if (sequence == cloudSequence) {
                    stopStreaming()
                    fallbackFromStreaming(text)
                }
            },
        )
    }

    private fun fallbackFromStreaming(text: String) {
        stopStreaming()
        if (!apiKeyStore.speechFallbackToSystem) return
        currentJob = scope.launch { speakCloudHttp(text, ++cloudSequence) }
    }

    private suspend fun speakCloudHttp(text: String, sequence: Long) {
        val chunks = splitForSpeech(text)
        for (chunk in chunks) {
            if (sequence != cloudSequence) return
            val audio = speechClient.synthesize(
                text = chunk,
                voiceId = apiKeyStore.deepSeekTtsVoice,
                format = apiKeyStore.deepSeekTtsFormat,
            ).getOrElse {
                if (apiKeyStore.speechFallbackToSystem) speakSystem(text)
                return
            }
            if (sequence != cloudSequence) return
            val playable = if (apiKeyStore.deepSeekTtsFormat.equals("pcm", ignoreCase = true)) {
                wrapPcmAsWav(audio, sampleRate = 24_000)
            } else audio
            playBytes(playable, "wav")
        }
    }

    private fun waitForPlaybackAndRelease(track: AudioTrack, totalPcmBytes: Long) {
        scope.launch {
            val totalFrames = totalPcmBytes / 2
            while (isActive && track.playbackHeadPosition.toLong() < totalFrames) {
                delay(100)
            }
            delay(150)
            if (audioTrack === track) {
                runCatching { track.stop() }
                runCatching { track.release() }
                audioTrack = null
            }
        }
    }
    private suspend fun playBytes(audio: ByteArray, format: String) {
        val ext = format.ifBlank { "mp3" }.replace(Regex("[^a-zA-Z0-9]"), "")
        val file = File(context.cacheDir, "tts_${UUID.randomUUID()}.$ext")
        file.writeBytes(audio)
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                val player = MediaPlayer()
                mediaPlayer = player
                player.setOnCompletionListener {
                    runCatching { it.release() }
                    if (mediaPlayer === it) mediaPlayer = null
                    file.delete()
                    if (cont.isActive) cont.resume(Unit)
                }
                player.setOnErrorListener { mp, _, _ ->
                    runCatching { mp.release() }
                    if (mediaPlayer === mp) mediaPlayer = null
                    file.delete()
                    if (cont.isActive) cont.resume(Unit)
                    true
                }
                runCatching {
                    player.setDataSource(file.absolutePath)
                    player.prepare()
                    player.start()
                }.onFailure {
                    runCatching { player.release() }
                    if (mediaPlayer === player) mediaPlayer = null
                    file.delete()
                    if (cont.isActive) cont.resume(Unit)
                }
                cont.invokeOnCancellation {
                    runCatching { player.stop() }
                    runCatching { player.release() }
                    if (mediaPlayer === player) mediaPlayer = null
                    file.delete()
                }
            }
        } finally {
            if (mediaPlayer == null) file.delete()
        }
    }

    fun stop() {
        cloudSequence++
        currentJob?.cancel()
        currentJob = null
        stopStreaming()
        stopMedia()
        tts?.stop()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun stopStreaming() {
        runCatching { streamSession?.close() }
        streamSession = null
        val track = audioTrack
        audioTrack = null
        runCatching { track?.stop() }
        runCatching { track?.release() }
    }
    private fun stopMedia() {
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
    }

    private fun wrapPcmAsWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val dataLength = pcm.size
        val out = java.io.ByteArrayOutputStream(44 + dataLength)
        fun le16(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
        fun le32(v: Int) {
            out.write(v and 0xff); out.write((v shr 8) and 0xff)
            out.write((v shr 16) and 0xff); out.write((v shr 24) and 0xff)
        }
        out.write("RIFF".toByteArray()); le32(36 + dataLength); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1); le32(sampleRate)
        le32(sampleRate * 2); le16(2); le16(16); out.write("data".toByteArray()); le32(dataLength)
        out.write(pcm)
        return out.toByteArray()
    }
    private fun splitForSpeech(text: String, maxChars: Int = 180): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val sentences = text.split(Regex("(?<=[。！？!?；;\\n])"))
        val result = mutableListOf<String>()
        val builder = StringBuilder()
        sentences.forEach { sentence ->
            if (builder.length + sentence.length > maxChars && builder.isNotEmpty()) {
                result += builder.toString()
                builder.clear()
            }
            builder.append(sentence)
        }
        if (builder.isNotEmpty()) result += builder.toString()
        return result.ifEmpty { listOf(text.take(maxChars)) }
    }
    private companion object {
        const val STREAM_SAMPLE_RATE = 24_000
    }
}

object SpeechTextCleaner {
    fun cleanForSpeech(text: String): String {
        return text
            .replace(Regex("""!\[([^\]]*)\]\([^)]*\)"""), "$1")
            .replace(Regex("""\[([^\]]+)\]\([^)]*\)"""), "$1")
            .replace(Regex("""^#{1,6}\s*""", RegexOption.MULTILINE), "")
            .replace(Regex("""^>\s?""", RegexOption.MULTILINE), "")
            .replace(Regex("""^[-*+]\s+""", RegexOption.MULTILINE), "")
            .replace("**", "")
            .replace("__", "")
            .replace("`", "")
            .replace("*", "")
            .replace("~~", "")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }
}
