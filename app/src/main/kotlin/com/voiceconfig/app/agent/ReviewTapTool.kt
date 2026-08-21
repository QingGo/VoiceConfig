package com.voiceconfig.app.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 点击前预览工具：在截图上标出将要点击的位置，返回给多模态模型确认。
 *
 * 模型通常会先调用 review_tap 查看点击位置，再决定是否调用 tap 或调整坐标。
 */
@Singleton
class ReviewTapTool @Inject constructor(
    private val shizuku: ShizukuCommandRunner,
) : AgentTool {

    override val name: String = "review_tap"
    override val description: String = "点击前预览：返回标有点击位置红色标记的当前屏幕截图，参数：{\"x\":500,\"y\":800}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val x = (args["x"] as? Number)?.toInt() ?: return ToolResult.failure("缺少参数 x")
        val y = (args["y"] as? Number)?.toInt() ?: return ToolResult.failure("缺少参数 y")
        if (!shizuku.isAvailable()) {
            return ToolResult.failure("review_tap 需要 Shizuku 授权")
        }
        val path = "/data/local/tmp/voiceconfig_review_tap.png"
        val capture = shizuku.execute("screencap", "-p", path)
        if (!capture.ok) {
            return ToolResult.failure("截屏失败：${capture.stderr.trim().ifBlank { "exit=${capture.exitCode}" }}")
        }
        val base64 = shizuku.execute("base64", "-w0", path)
        if (!base64.ok || base64.stdout.isBlank()) {
            return ToolResult.failure("读取截图失败：${base64.stderr.trim().ifBlank { "exit=${base64.exitCode}" }}")
        }
        val image = base64.stdout.replace("\n", "").replace("\r", "").trim()
        val marked = markTap(image, x, y)
        return ToolResult.success(
            "已生成点击预览，红色标记位置 (${x}, ${y})",
            mapOf("image_base64" to marked, "x" to x, "y" to y),
        )
    }

    private fun markTap(imageBase64: String, x: Int, y: Int): String {
        return runCatching {
            val bytes = Base64.decode(imageBase64, Base64.DEFAULT)
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching imageBase64
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawBitmap(src, 0f, 0f, null)
            val paint = Paint().apply {
                color = Color.RED
                strokeWidth = 12f
                style = Paint.Style.STROKE
            }
            val r = 60
            canvas.drawCircle(x.toFloat(), y.toFloat(), r.toFloat(), paint)
            canvas.drawLine((x - 100).toFloat(), y.toFloat(), (x + 100).toFloat(), y.toFloat(), paint)
            canvas.drawLine(x.toFloat(), (y - 100).toFloat(), x.toFloat(), (y + 100).toFloat(), paint)
            val baos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.PNG, 90, baos)
            out.recycle()
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        }.getOrElse { imageBase64 }
    }
}
