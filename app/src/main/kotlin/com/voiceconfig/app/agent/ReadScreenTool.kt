package com.voiceconfig.app.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Base64
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 截取当前屏幕，返回带坐标网格的 PNG base64，供多模态视觉模型直接“看”画面。
 *
 * 使用 Shizuku 执行：
 * 1. screencap -p <path>
 * 2. base64 -w0 <path>
 *
 * 返回的 base64 会由 [AgentSession] 以 image_url 形式注入下一轮用户消息，
 * 让模型可以持续观察屏幕并决定下一步点击/输入。
 * 图片上会叠加坐标网格，帮助模型输出更准确的绝对坐标。
 */
@Singleton
class ReadScreenTool @Inject constructor(
    private val shizuku: ShizukuCommandRunner,
) : AgentTool {

    private var cachedBase64: String? = null
    private var cachedAtMs: Long = 0L
    private var cachedWidth: Int = 0
    private var cachedHeight: Int = 0

    override val name: String = "read_screen"
    override val description: String = "截取当前屏幕并返回带坐标网格的画面，适合需要看界面、图片、小程序按钮位置时使用；参数：{\"gridStep\":100,\"maxDimension\":1280}（可选，网格间隔像素默认200，最长边像素0表示不缩图）"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        if (!shizuku.isAvailable()) {
            return ToolResult.failure("read_screen 需要 Shizuku 授权")
        }
        val timingMs = linkedMapOf<String, Long>()
        val totalStartMs = System.currentTimeMillis()
        synchronized(this) {
            val now = System.currentTimeMillis()
            if (cachedBase64 != null && now - cachedAtMs < CACHE_TTL_MS) {
                timingMs["total_ms"] = System.currentTimeMillis() - totalStartMs
                return ToolResult.success(
                    "已返回短时间内的屏幕截图缓存（${cachedBase64?.length ?: 0} 字符 base64）",
                    mapOf(
                        "image_base64" to cachedBase64,
                        "path" to "/data/local/tmp/voiceconfig_screen.png",
                        "width" to cachedWidth,
                        "height" to cachedHeight,
                        "has_grid" to true,
                        "cached" to true,
                        "timingMs" to timingMs,
                    ),
                )
            }
        }
        val path = "/data/local/tmp/voiceconfig_screen.png"
        val captureStartMs = System.currentTimeMillis()
        val capture = shizuku.execute("screencap", "-p", path)
        timingMs["screencap_ms"] = System.currentTimeMillis() - captureStartMs
        if (!capture.ok) {
            return ToolResult.failure("截屏失败：${capture.stderr.trim().ifBlank { "exit=${capture.exitCode}" }}")
        }
        val base64StartMs = System.currentTimeMillis()
        val base64 = shizuku.execute("base64", "-w0", path)
        timingMs["base64_ms"] = System.currentTimeMillis() - base64StartMs
        if (!base64.ok || base64.stdout.isBlank()) {
            return ToolResult.failure("读取截图失败：${base64.stderr.trim().ifBlank { "exit=${base64.exitCode}" }}")
        }
        val image = base64.stdout.replace("\n", "").replace("\r", "").trim()
        val gridStep = (args["gridStep"] as? Number)?.toInt()?.coerceIn(50, 500) ?: 200
        val sizeStartMs = System.currentTimeMillis()
        val sizeResult = shizuku.execute("wm", "size")
        timingMs["wm_size_ms"] = System.currentTimeMillis() - sizeStartMs
        val dimension = Regex("""(\d+)\s*x\s*(\d+)""")
            .find(sizeResult.stdout)
            ?.let { it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull() }
        val maxDimension = (args["maxDimension"] as? Number)?.toInt()?.coerceIn(0, 4096) ?: 0
        val gridStartMs = System.currentTimeMillis()
        val gridImage = addCoordinateGrid(image, gridStep, maxDimension)
        timingMs["grid_encode_ms"] = System.currentTimeMillis() - gridStartMs
        timingMs["total_ms"] = System.currentTimeMillis() - totalStartMs
        synchronized(this) {
            cachedBase64 = gridImage
            cachedAtMs = System.currentTimeMillis()
            cachedWidth = dimension?.first ?: 0
            cachedHeight = dimension?.second ?: 0
        }
        return ToolResult.success(
            "已截取当前屏幕并叠加坐标网格（${gridImage.length} 字符 base64）",
            mapOf(
                "image_base64" to gridImage,
                "path" to path,
                "width" to (dimension?.first ?: 0),
                "height" to (dimension?.second ?: 0),
                "has_grid" to true,
                "timingMs" to timingMs,
            ),
        )
    }

    private fun addCoordinateGrid(imageBase64: String, gridStep: Int, maxDimension: Int = 0): String {
        return runCatching {
            val bytes = Base64.decode(imageBase64, Base64.DEFAULT)
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching imageBase64
            val scale = if (maxDimension > 0 && maxOf(src.width, src.height) > maxDimension) {
                maxDimension.toFloat() / maxOf(src.width, src.height)
            } else {
                1f
            }
            val outWidth = (src.width * scale).toInt().coerceAtLeast(1)
            val outHeight = (src.height * scale).toInt().coerceAtLeast(1)
            val out = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawBitmap(src, null, android.graphics.Rect(0, 0, outWidth, outHeight), null)

            val linePaint = Paint().apply {
                color = Color.argb(90, 255, 0, 0)
                strokeWidth = 3f
                style = Paint.Style.STROKE
            }
            val textPaint = Paint().apply {
                color = Color.argb(180, 255, 0, 0)
                textSize = 36f
                typeface = Typeface.DEFAULT_BOLD
            }
            val step = gridStep
            for (x in 0..src.width step step) {
                val sx = x * scale
                canvas.drawLine(sx, 0f, sx, outHeight.toFloat(), linePaint)
                canvas.drawText(x.toString(), sx + 6f, 64f, textPaint)
            }
            for (y in 0..src.height step step) {
                val sy = y * scale
                canvas.drawLine(0f, sy, outWidth.toFloat(), sy, linePaint)
                canvas.drawText(y.toString(), 8f, sy + 56f, textPaint)
            }

            val baos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.PNG, 90, baos)
            out.recycle()
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        }.getOrElse { imageBase64 }
    }
    companion object {
        private const val CACHE_TTL_MS = 800L
    }
}
