package com.voiceconfig.app.ai

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class AsrModelTier {
    PRODUCTION,
    EXPERIMENTAL,
    HIDDEN,
}

enum class AsrModelKind {
    SHERPA_STREAMING_TRANSDUCER,
    SHERPA_STREAMING_ZIPFORMER2_TRANSDUCER,
    SHERPA_STREAMING_PARAFORMER,
    SHERPA_STREAMING_CTC,
    SENSEVOICE_OFFLINE,
    QWEN3_ASR_OFFLINE,
    COHERE_TRANSCRIBE_OFFLINE,
    TRANSCRIBE_CPP_OFFLINE,
}

data class AsrModelFile(
    val name: String,
    val url: String,
    val size: Long,
    val mirrorUrls: List<String> = emptyList(),
    val sha256: String? = null,
)

data class AsrModel(
    val id: String,
    val displayName: String,
    val kind: AsrModelKind,
    val description: String,
    val builtin: Boolean = false,
    val assetDir: String? = null,
    val files: List<AsrModelFile> = emptyList(),
    val threads: Int = 2,
    val modelingUnit: String = "cjkchar",
    val modelType: String = "zipformer",
    val language: String = "",
    val provider: String = "cpu",
    val tier: AsrModelTier = AsrModelTier.PRODUCTION,
)

@Singleton
class AsrModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("voice_config_asr", Context.MODE_PRIVATE)

    val models: List<AsrModel> = listOf(
        AsrModel(
            id = "sherpa-zh-14m-2023",
            displayName = "Sherpa Zipformer 14M（内置）",
            kind = AsrModelKind.SHERPA_STREAMING_TRANSDUCER,
            description = "默认内置流式模型，约 1400 万参数 / 25MB，保证安装包小且开箱可用。若追求更高识别准确率，建议下载下方 Paraformer 中英流式模型。",
            builtin = true,
            assetDir = "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23",
        ),
        AsrModel(
            id = "sherpa-ctc-2025",
            displayName = "Sherpa Zipformer Small CTC 2025",
            kind = AsrModelKind.SHERPA_STREAMING_CTC,
            description = "Small 级 2025 新流式模型（参数多于 14M），int8 文件约 26MB，识别性能通常更好。",
            threads = 4,
            files = listOf(
                AsrModelFile(
                    name = "model.int8.onnx",
                    url = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01/resolve/main/model.int8.onnx",
                    mirrorUrls = listOf(
                        "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01/resolve/main/model.int8.onnx",
                    ),
                    size = 26_342_340L,
                ),
                AsrModelFile(
                    name = "tokens.txt",
                    url = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01/resolve/main/tokens.txt",
                    mirrorUrls = listOf(
                        "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01/resolve/main/tokens.txt",
                    ),
                    size = 13_366L,
                ),
                AsrModelFile(
                    name = "bbpe.model",
                    url = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01/resolve/main/bbpe.model",
                    mirrorUrls = listOf(
                        "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01/resolve/main/bbpe.model",
                    ),
                    size = 255_180L,
                ),
            ),
        ),
        AsrModel(
            id = "sherpa-paraformer-bilingual-zh-en",
            displayName = "Sherpa Paraformer 中英流式",
            kind = AsrModelKind.SHERPA_STREAMING_PARAFORMER,
            description = "推荐的高性能中英双语流式模型，int8 约 237MB，支持普通话/河南话/天津话/四川话等；识别准确率通常优于内置模型，需额外下载。",
            threads = 4,
            files = listOf(
                AsrModelFile(
                    name = "encoder.int8.onnx",
                    url = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main/encoder.int8.onnx",
                    mirrorUrls = listOf(
                        "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main/encoder.int8.onnx",
                    ),
                    size = 165_462_184L,
                ),
                AsrModelFile(
                    name = "decoder.int8.onnx",
                    url = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main/decoder.int8.onnx",
                    mirrorUrls = listOf(
                        "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main/decoder.int8.onnx",
                    ),
                    size = 71_664_561L,
                ),
                AsrModelFile(
                    name = "tokens.txt",
                    url = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main/tokens.txt",
                    mirrorUrls = listOf(
                        "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main/tokens.txt",
                    ),
                    size = 75_756L,
                ),
            ),
        ),
        AsrModel(
            id = "sherpa-multilingual-2025",
            displayName = "Sherpa 多语流式 2025（中英默认）",
            kind = AsrModelKind.SHERPA_STREAMING_ZIPFORMER2_TRANSDUCER,
            description = "默认中英混合流式模型，支持中英日韩阿俄泰越等；真机已验证中文和英文。",
            threads = 4,
            modelingUnit = "bpe",
            modelType = "zipformer2",
            files = listOf(
                AsrModelFile(
                    name = "encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx",
                    url = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10/resolve/main/encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx",
                    mirrorUrls = listOf(
                        "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10/resolve/main/encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx",
                    ),
                    size = 296_583_597L,
                ),
                AsrModelFile(
                    name = "decoder-epoch-75-avg-11-chunk-16-left-128.onnx",
                    url = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10/resolve/main/decoder-epoch-75-avg-11-chunk-16-left-128.onnx",
                    mirrorUrls = listOf(
                        "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10/resolve/main/decoder-epoch-75-avg-11-chunk-16-left-128.onnx",
                    ),
                    size = 33_837_085L,
                ),
                AsrModelFile(
                    name = "joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx",
                    url = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10/resolve/main/joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx",
                    mirrorUrls = listOf(
                        "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10/resolve/main/joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx",
                    ),
                    size = 8_257_421L,
                ),
                AsrModelFile(
                    name = "bpe.model",
                    url = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10/resolve/main/bpe.model",
                    mirrorUrls = listOf(
                        "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10/resolve/main/bpe.model",
                    ),
                    size = 476_049L,
                ),
                AsrModelFile(
                    name = "tokens.txt",
                    url = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10/resolve/main/tokens.txt",
                    mirrorUrls = listOf(
                        "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10/resolve/main/tokens.txt",
                    ),
                    size = 195_244L,
                ),
            ),
        ),
        AsrModel(
            id = "sensevoice-small",
            displayName = "SenseVoice Small（非流式）",
            kind = AsrModelKind.SENSEVOICE_OFFLINE,
            description = "非流式，中文短句准确率高，但模型较大（约 239MB）。",
            tier = AsrModelTier.EXPERIMENTAL,
            files = listOf(
                AsrModelFile(
                    name = "model.int8.onnx",
                    url = "https://huggingface.co/twmht/sherpa-onnx-sense-voice-small/resolve/main/model.int8.onnx",
                    mirrorUrls = listOf(
                        "https://hf-mirror.com/twmht/sherpa-onnx-sense-voice-small/resolve/main/model.int8.onnx",
                    ),
                    size = 239_233_841L,
                ),
                AsrModelFile(
                    name = "tokens.txt",
                    url = "https://huggingface.co/twmht/sherpa-onnx-sense-voice-small/resolve/main/tokens.txt",
                    mirrorUrls = listOf(
                        "https://hf-mirror.com/twmht/sherpa-onnx-sense-voice-small/resolve/main/tokens.txt",
                    ),
                    size = 315_894L,
                ),
            ),
        ),
        AsrModel(
            id = "qwen3-asr-0.6b-int8",
            displayName = "Qwen3-ASR 0.6B int8（非流式，实验性）",
            kind = AsrModelKind.QWEN3_ASR_OFFLINE,
            description = "Qwen3-ASR 0.6B ONNX int8，中英混合准确率高；模型较大，约 987MB。",
            threads = 4,
            tier = AsrModelTier.EXPERIMENTAL,
            files = listOf(
                AsrModelFile(
                    name = "conv_frontend.onnx",
                    url = "https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main/conv_frontend.onnx",
                    mirrorUrls = listOf(
                        "https://hf-mirror.com/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main/conv_frontend.onnx",
                    ),
                    size = 44_148_281L,
                ),
                AsrModelFile(
                    name = "encoder.int8.onnx",
                    url = "https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main/encoder.int8.onnx",
                    mirrorUrls = listOf(
                        "https://hf-mirror.com/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main/encoder.int8.onnx",
                    ),
                    size = 182_491_662L,
                ),
                AsrModelFile(
                    name = "decoder.int8.onnx",
                    url = "https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main/decoder.int8.onnx",
                    mirrorUrls = listOf(
                        "https://hf-mirror.com/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main/decoder.int8.onnx",
                    ),
                    size = 755_914_231L,
                ),
                AsrModelFile(
                    name = "tokenizer/merges.txt",
                    url = "https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main/tokenizer/merges.txt",
                    mirrorUrls = listOf(
                        "https://hf-mirror.com/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main/tokenizer/merges.txt",
                    ),
                    size = 1_671_853L,
                ),
                AsrModelFile(
                    name = "tokenizer/vocab.json",
                    url = "https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main/tokenizer/vocab.json",
                    mirrorUrls = listOf(
                        "https://hf-mirror.com/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main/tokenizer/vocab.json",
                    ),
                    size = 2_776_833L,
                ),
                AsrModelFile(
                    name = "tokenizer/tokenizer_config.json",
                    url = "https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main/tokenizer/tokenizer_config.json",
                    mirrorUrls = listOf(
                        "https://hf-mirror.com/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main/tokenizer/tokenizer_config.json",
                    ),
                    size = 12_487L,
                ),
            ),
        ),
        AsrModel(
            id = "qwen3-asr-0.6b-q4-km-gguf",
            displayName = "Qwen3-ASR 0.6B Q4_K_M（transcribe.cpp 离线）",
            kind = AsrModelKind.TRANSCRIBE_CPP_OFFLINE,
            description = "Qwen3-ASR 0.6B GGUF Q4_K_M，通过 transcribe.cpp 运行；约 562MB，实机中文总耗时约 3.4s，是当前最优的高精度离线路径。",
            threads = 4,
            language = "",
            provider = "cpu",
            files = listOf(
                AsrModelFile(
                    name = "Qwen3-ASR-0.6B-Q4_K_M.gguf",
                    url = "https://huggingface.co/handy-computer/Qwen3-ASR-0.6B-gguf/resolve/main/Qwen3-ASR-0.6B-Q4_K_M.gguf",
                    mirrorUrls = listOf(
                        "https://hf-mirror.com/handy-computer/Qwen3-ASR-0.6B-gguf/resolve/main/Qwen3-ASR-0.6B-Q4_K_M.gguf",
                    ),
                    size = 589_560_480L,
                    sha256 = "5b58f32a58ffa2c8783e0b0963485623e286e6272d953dfc9e28bc3447dee0c0",
                ),
            ),
        ),
    )

    fun selectedModel(): AsrModel {
        val preferredId = prefs.getString(KEY_SELECTED, DEFAULT_MODEL_ID) ?: DEFAULT_MODEL_ID
        val selected = models.firstOrNull { it.id == preferredId }
        if (selected != null && (selected.builtin || isDownloaded(selected))) {
            return selected
        }
        // 默认模型尚未下载时回退到内置模型，保证本地 ASR 仍可用。
        return models.firstOrNull { it.builtin } ?: models.first()
    }

    fun setSelectedModel(id: String) {
        prefs.edit().putString(KEY_SELECTED, id).apply()
    }

    fun modelDir(model: AsrModel): String =
        if (model.builtin) {
            model.assetDir ?: ""
        } else {
            File(context.filesDir, "models/${model.id}").absolutePath
        }

    fun isDownloaded(model: AsrModel): Boolean =
        if (model.builtin) {
            true
        } else {
            model.files.all { file ->
                File(context.filesDir, "models/${model.id}/${file.name}").exists()
            }
        }

    fun modelSizeText(model: AsrModel): String {
        val total = model.files.sumOf { it.size }
        return when {
            total > 0 -> "%.1f MB".format(total / 1024f / 1024f)
            model.builtin -> "内置"
            else -> "未知"
        }
    }

    suspend fun download(model: AsrModel, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "models/${model.id}")
            dir.mkdirs()
            val total = model.files.sumOf { it.size }.toFloat()
            var downloaded = 0L
            fun reportProgress() {
                if (total > 0f) onProgress(downloaded / total)
            }
            model.files.forEach { file ->
                val target = File(dir, file.name)
                target.parentFile?.mkdirs()
                if (target.exists() && ((file.size == 0L && target.length() > 0L) || target.length() == file.size)) {
                    val actualHash = file.sha256?.let { sha256Of(target) }
                    if (actualHash == null || actualHash.equals(file.sha256, ignoreCase = true)) {
                        downloaded += file.size
                        reportProgress()
                        return@forEach
                    }
                    // Hash mismatch: remove and re-download below.
                    target.delete()
                }
                val candidates = (file.mirrorUrls + file.url).distinct()
                var lastError: Exception? = null
                var completed = false
                for (candidate in candidates) {
                    if (completed) break
                    var conn: HttpURLConnection? = null
                    try {
                        conn = URL(candidate).openConnection() as HttpURLConnection
                        conn.connectTimeout = 15_000
                        conn.readTimeout = 60_000
                        conn.setRequestProperty("User-Agent", "VoiceConfig")
                        val input = conn.inputStream
                        val output = target.outputStream()
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            downloaded += read
                            reportProgress()
                        }
                        output.close()
                        input.close()
                        val actualHash = file.sha256?.let { sha256Of(target) }
                        if (actualHash != null && !actualHash.equals(file.sha256, ignoreCase = true)) {
                            target.delete()
                            throw RuntimeException("SHA-256 校验失败: ${file.name}")
                        }
                        completed = true
                    } catch (e: Exception) {
                        lastError = e
                        target.delete()
                    } finally {
                        conn?.disconnect()
                    }
                }
                if (!completed) {
                    throw lastError ?: RuntimeException("下载失败: ${file.name}")
                }
            }
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                if (read > 0) digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun deleteModel(model: AsrModel) {
        if (model.builtin) return
        File(context.filesDir, "models/${model.id}").deleteRecursively()
    }

    companion object {
        private const val KEY_SELECTED = "selected_asr_model"
        const val DEFAULT_MODEL_ID = "sherpa-zh-14m-2023"
        const val RECOMMENDED_MODEL_ID = "sherpa-paraformer-bilingual-zh-en"
    }
}
