package com.voiceconfig.app.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Base64
import com.voiceconfig.app.service.AgentAccessibilityService
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
    override val description: String = "截取当前屏幕并返回带坐标网格的画面，适合需要看界面、图片、小程序按钮位置时使用；参数：{\"gridStep\":100,\"maxDimension\":1440}（可选，网格间隔像素默认200，maxDimension=1440表示最长边缩到1440，点击时以网格数字代表的原始屏幕坐标为准）"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val timingMs = linkedMapOf<String, Any?>()
        val totalStartMs = System.currentTimeMillis()

        // 优先 Shizuku 截屏；没有 Shizuku 时使用 AccessibilityService.takeScreenshot。
        val useAccessibilityScreenshot = !shizuku.isAvailable()
        var image: String
        var dimension: Pair<Int, Int>? = null
        if (useAccessibilityScreenshot) {
            val a11yBase64 = AgentAccessibilityService.captureScreenshot()
            if (a11yBase64 == null) {
                return ToolResult.failure("read_screen 需要 Shizuku 授权或无障碍截屏能力")
            }
            image = a11yBase64
            dimension = decodeImageSize(image)
            timingMs["source"] = "accessibility"
        } else {
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
            image = base64.stdout.replace("\n", "").replace("\r", "").trim()
            val sizeStartMs = System.currentTimeMillis()
            val sizeResult = shizuku.execute("wm", "size")
            timingMs["wm_size_ms"] = System.currentTimeMillis() - sizeStartMs
            dimension = Regex("""(\d+)\s*x\s*(\d+)""")
                .find(sizeResult.stdout)
                ?.let { it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull() }
                ?.takeIf { it.first != null && it.second != null }
                ?.let { it.first!! to it.second!! }
        }

        val gridStep = (args["gridStep"] as? Number)?.toInt()?.coerceIn(50, 500) ?: 200
        val maxDimension = (args["maxDimension"] as? Number)?.toInt()?.coerceIn(0, 4096) ?: DEFAULT_MAX_DIMENSION
        val gridStartMs = System.currentTimeMillis()
        var gridImage = addCoordinateGrid(image, gridStep, maxDimension)
        val annotations = (args["annotations"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()
        val annotate = (args["annotate"] as? Boolean) ?: annotations.isNotEmpty()
        if (annotate && annotations.isNotEmpty() && dimension != null && dimension.first != null && dimension.second != null) {
            val annotateStartMs = System.currentTimeMillis()
            gridImage = annotateImage(gridImage, annotations, dimension.first ?: 0, dimension.second ?: 0)
            timingMs["annotate_ms"] = System.currentTimeMillis() - annotateStartMs
        }
        timingMs["grid_encode_ms"] = System.currentTimeMillis() - gridStartMs
        timingMs["total_ms"] = System.currentTimeMillis() - totalStartMs
        synchronized(this) {
            cachedBase64 = gridImage
            cachedAtMs = System.currentTimeMillis()
            cachedWidth = dimension?.first ?: 0
            cachedHeight = dimension?.second ?: 0
        }
        val sourcePath = if (useAccessibilityScreenshot) "accessibility_takeScreenshot" else "/data/local/tmp/voiceconfig_screen.png"
        return ToolResult.success(
            "已截取当前屏幕并叠加坐标网格（${gridImage.length} 字符 base64）。网格数字为原始屏幕坐标，请使用网格数字而非图片像素位置.",
            mapOf(
                "image_base64" to gridImage,
                "path" to sourcePath,
                "width" to (dimension?.first ?: 0),
                "height" to (dimension?.second ?: 0),
                "has_grid" to true,
                "timingMs" to timingMs,
            ),
        )
    }

    private fun decodeImageSize(base64: String): Pair<Int, Int>? {
        return runCatching {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
        }.getOrNull()
    }

    private fun annotateImage(
        imageBase64: String,
        annotations: List<Map<*, *>>,
        originalWidth: Int,
        originalHeight: Int,
    ): String {
        return runCatching {
            val bytes = Base64.decode(imageBase64, Base64.DEFAULT)
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching imageBase64
            val out = src.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(out)
            val scaleX = out.width.toFloat() / originalWidth
            val scaleY = out.height.toFloat() / originalHeight
            val paint = Paint().apply {
                color = Color.argb(220, 0, 120, 255)
                style = Paint.Style.STROKE
                strokeWidth = 4f
            }
            val textPaint = Paint().apply {
                color = Color.argb(255, 0, 0, 255)
                textSize = 28f
                typeface = Typeface.DEFAULT_BOLD
            }
            annotations.forEachIndexed { index, ann ->
                val boundsValue = ann["bounds"]
                val rect = when (boundsValue) {
                    is String -> parseBoundsString(boundsValue)?.let {
                        android.graphics.Rect(
                            (it[0] * scaleX).toInt(),
                            (it[1] * scaleY).toInt(),
                            (it[2] * scaleX).toInt(),
                            (it[3] * scaleY).toInt(),
                        )
                    }
                    is List<*> -> {
                        val nums = boundsValue.mapNotNull { (it as? Number)?.toInt() }
                        if (nums.size >= 4) android.graphics.Rect(
                            (nums[0] * scaleX).toInt(),
                            (nums[1] * scaleY).toInt(),
                            (nums[2] * scaleX).toInt(),
                            (nums[3] * scaleY).toInt(),
                        ) else null
                    }
                    else -> null
                }
                if (rect != null) {
                    canvas.drawRect(rect, paint)
                    val label = (ann["id"] ?: (index + 1)).toString()
                    canvas.drawText(label, rect.left + 4f, rect.top + 30f, textPaint)
                }
            }
            val baos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            out.recycle()
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        }.getOrElse { imageBase64 }
    }

    private fun parseBoundsString(bounds: String): IntArray? {
        val m = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""").find(bounds) ?: return null
        return intArrayOf(
            m.groupValues[1].toIntOrNull() ?: return null,
            m.groupValues[2].toIntOrNull() ?: return null,
            m.groupValues[3].toIntOrNull() ?: return null,
            m.groupValues[4].toIntOrNull() ?: return null,
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
            out.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            out.recycle()
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        }.getOrElse { imageBase64 }
    }
    companion object {
        private const val CACHE_TTL_MS = 800L
        private const val DEFAULT_MAX_DIMENSION = 1440
    }
}
