package com.voiceconfig.app.ai

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class AsrModelKind {
    SHERPA_STREAMING_TRANSDUCER,
    SHERPA_STREAMING_CTC,
    SENSEVOICE_OFFLINE,
}

data class AsrModelFile(
    val name: String,
    val url: String,
    val size: Long,
    val mirrorUrls: List<String> = emptyList(),
)

data class AsrModel(
    val id: String,
    val displayName: String,
    val kind: AsrModelKind,
    val description: String,
    val builtin: Boolean = false,
    val assetDir: String? = null,
    val files: List<AsrModelFile> = emptyList(),
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
            description = "当前内置流式模型，约 1400 万参数 / 25MB，稳定保底。",
            builtin = true,
            assetDir = "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23",
        ),
        AsrModel(
            id = "sherpa-ctc-2025",
            displayName = "Sherpa Zipformer Small CTC 2025",
            kind = AsrModelKind.SHERPA_STREAMING_CTC,
            description = "Small 级 2025 新流式模型（参数多于 14M），int8 文件约 26MB，识别性能通常更好。",
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
            id = "sensevoice-small",
            displayName = "SenseVoice Small（非流式）",
            kind = AsrModelKind.SENSEVOICE_OFFLINE,
            description = "非流式，中文短句准确率高，但模型较大（约 239MB）。",
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
    )

    fun selectedModel(): AsrModel {
        val id = prefs.getString(KEY_SELECTED, models.first().id) ?: models.first().id
        return models.firstOrNull { it.id == id } ?: models.first()
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
        return if (total > 0) {
            "%.1f MB".format(total / 1024f / 1024f)
        } else {
            "内置"
        }
    }

    suspend fun download(model: AsrModel, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "models/${model.id}")
            dir.mkdirs()
            val total = model.files.sumOf { it.size }.toFloat()
            var downloaded = 0L
            model.files.forEach { file ->
                val target = File(dir, file.name)
                if (target.exists() && target.length() == file.size) {
                    downloaded += file.size
                    onProgress(downloaded / total)
                    return@forEach
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
                            onProgress(downloaded / total)
                        }
                        output.close()
                        input.close()
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

    fun deleteModel(model: AsrModel) {
        if (model.builtin) return
        File(context.filesDir, "models/${model.id}").deleteRecursively()
    }

    companion object {
        private const val KEY_SELECTED = "selected_asr_model"
    }
}
