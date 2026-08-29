package com.voiceconfig.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle
import android.util.Log
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
        val root = activeRoot ?: return emptyList()
        val out = mutableListOf<AccessibilityUiSnapshot>()
        collectNodes(root, 0, out)
        return out
    }

    private fun clickByText(text: String): Boolean {
        val root = activeRoot ?: return false
        val node = findText(root, text) ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun clickByBounds(x: Int, y: Int): Boolean {
        val root = activeRoot ?: return false
        val node = findClickableAt(root, x, y) ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun inputByText(text: String): Boolean {
        val root = activeRoot ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val target = focused ?: findFirstEditable(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun pasteIntoFocused(): Boolean {
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
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
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

        fun currentSnapshot(): String? = instance?.snapshotText()

        fun currentPackageName(): String? = instance?.activeRoot?.packageName?.toString()?.ifBlank { null }

        fun currentNodes(): List<AccessibilityUiSnapshot> = instance?.snapshotNodes() ?: emptyList()

        fun clickText(text: String): Boolean? = instance?.clickByText(text)

        fun clickPoint(x: Int, y: Int): Boolean? = instance?.clickByBounds(x, y)

        fun inputText(text: String): Boolean? = instance?.inputByText(text)

        fun paste(): Boolean? = instance?.pasteIntoFocused()
    }
}
