package com.voiceconfig.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import android.accessibilityservice.GestureDescription
import android.graphics.Path

data class AccessibilityUiSnapshot(
    val text: String,
    val contentDesc: String,
    val resourceId: String,
    val className: String,
    val bounds: String,
    val clickable: Boolean,
    val focusable: Boolean,
    val packageName: String = "",
)

/**
 * 无 Shizuku 时的 AccessibilityService 降级通道。
 *
 * 至少实现“可读”：读取当前窗口文本/描述/坐标。
 * 同时实现“可点”：按文本或坐标点击可点击节点。
 *
 * 用户需要在系统设置中手动开启“言控”的无障碍服务。
 */
class AgentAccessibilityService : AccessibilityService() {

    @Volatile
    private var activeRoot: AccessibilityNodeInfo? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "AgentAccessibilityService created, instance=$instance")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AgentAccessibilityService connected, instance=$instance")
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        activeRoot = rootInActiveWindow
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        activeRoot = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun snapshotText(): String {
        activeRoot = rootInActiveWindow
        val root = activeRoot ?: return "（无障碍服务未获取到当前窗口）"
        val sb = StringBuilder()
        collect(root, 0, sb)
        return sb.toString()
    }

    private fun collect(node: AccessibilityNodeInfo, depth: Int, sb: StringBuilder) {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (text.isNotBlank() || desc.isNotBlank()) {
            sb.append("  ".repeat(depth))
                .append("[")
                .append(text.ifBlank { desc })
                .append("] ")
                .append(bounds.toShortString())
                .append("\n")
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collect(it, depth + 1, sb) }
        }
    }

    private fun collectNodes(node: AccessibilityNodeInfo, depth: Int, out: MutableList<AccessibilityUiSnapshot>) {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val id = node.viewIdResourceName.orEmpty()
        val cls = node.className?.toString().orEmpty()
        if (text.isNotBlank() || desc.isNotBlank() || id.isNotBlank() || node.isClickable || node.isFocusable) {
            out += AccessibilityUiSnapshot(
                text = text,
                contentDesc = desc,
                resourceId = id,
                className = cls,
                bounds = "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]",
                clickable = node.isClickable,
                focusable = node.isFocusable,
                packageName = node.packageName?.toString().orEmpty(),
            )
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectNodes(it, depth + 1, out) }
        }
    }

    private fun snapshotNodes(): List<AccessibilityUiSnapshot> {
        activeRoot = rootInActiveWindow
        val root = activeRoot ?: return emptyList()
        val out = mutableListOf<AccessibilityUiSnapshot>()
        collectNodes(root, 0, out)
        return out
    }

    private fun clickByText(text: String): Boolean {
        activeRoot = rootInActiveWindow
        val root = activeRoot ?: return false
        val node = findText(root, text) ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun clickByBounds(x: Int, y: Int): Boolean {
        activeRoot = rootInActiveWindow
        val root = activeRoot ?: return false
        val node = findClickableAt(root, x, y) ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun inputByText(text: String): Boolean {
        activeRoot = rootInActiveWindow
        val root = activeRoot ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val target = focused ?: findFirstEditable(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun pasteIntoFocused(): Boolean {
        activeRoot = rootInActiveWindow
        val root = activeRoot ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val target = focused ?: findFirstEditable(root) ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditable(child)
            if (found != null) return found
        }
        return null
    }

    private fun findText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        if (nodeText.contains(text, ignoreCase = true) || desc.contains(text, ignoreCase = true)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findText(child, text)
            if (found != null) return found
        }
        return null
    }

    private fun findClickableAt(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (node.isClickable && bounds.contains(x, y)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findClickableAt(child, x, y)
            if (found != null) return found
        }
        return null
    }

    private fun gestureTap(x: Int, y: Int): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x.toFloat(), y.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 120))
            .build()
        return runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }

    private fun gestureSwipe(
        x1: Int, y1: Int,
        x2: Int, y2: Int,
        durationMs: Int,
    ): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.toLong().coerceAtLeast(1)))
            .build()
        return runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }

    private fun clickByResourceId(resourceId: String): Boolean {
        activeRoot = rootInActiveWindow
        val root = activeRoot ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId(resourceId)
        val node = nodes?.firstOrNull() ?: return false
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        val cx = (rect.left + rect.right) / 2
        val cy = (rect.top + rect.bottom) / 2
        return gestureTap(cx, cy)
    }

    companion object {
        private const val TAG = "AgentAccessibilityService"
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        fun gestureTap(x: Int, y: Int): Boolean? = instance?.gestureTap(x, y)

        fun gestureSwipe(
            x1: Int, y1: Int,
            x2: Int, y2: Int,
            durationMs: Int,
        ): Boolean? = instance?.gestureSwipe(x1, y1, x2, y2, durationMs)

        fun pressBack(): Boolean? = instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

        fun pressHome(): Boolean? = instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

        fun clickResourceId(resourceId: String): Boolean? = instance?.clickByResourceId(resourceId)

        fun currentSnapshot(): String? = instance?.snapshotText()

        fun currentPackageName(): String? {
            val svc = instance ?: return null
            svc.activeRoot = svc.rootInActiveWindow
            return svc.activeRoot?.packageName?.toString()?.ifBlank { null }
        }

        fun currentNodes(): List<AccessibilityUiSnapshot> = instance?.snapshotNodes() ?: emptyList()

        /** 通过 AccessibilityService 的 takeScreenshot API 截屏（无需 Shizuku）。返回 JPEG base64。 */
        suspend fun captureScreenshot(): String? {
            val svc = instance ?: return null
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            return suspendCancellableCoroutine { cont ->
                val executor = Executors.newSingleThreadExecutor()
                var completed = false
                fun complete(value: String?) {
                    if (!completed) {
                        completed = true
                        cont.resume(value)
                    }
                    runCatching { executor.shutdown() }
                }
                svc.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    executor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            try {
                                val hw = screenshot.hardwareBuffer
                                val bitmap = Bitmap.wrapHardwareBuffer(hw, screenshot.colorSpace)
                                    ?.copy(Bitmap.Config.ARGB_8888, false)
                                hw.close()
                                if (bitmap == null) {
                                    complete(null)
                                    return
                                }
                                val baos = ByteArrayOutputStream()
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                                bitmap.recycle()
                                complete(Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP))
                            } catch (e: Exception) {
                                Log.e(TAG, "accessibility screenshot convert failed", e)
                                complete(null)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "accessibility screenshot failed error=$errorCode")
                            complete(null)
                        }
                    },
                )
            }
        }

        fun clickText(text: String): Boolean? = instance?.clickByText(text)

        fun clickPoint(x: Int, y: Int): Boolean? = instance?.clickByBounds(x, y)

        fun inputText(text: String): Boolean? = instance?.inputByText(text)

        fun paste(): Boolean? = instance?.pasteIntoFocused()
    }
}
