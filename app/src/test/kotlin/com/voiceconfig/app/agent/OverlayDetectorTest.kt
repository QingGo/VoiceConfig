package com.voiceconfig.app.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayDetectorTest {

    private fun node(
        text: String = "",
        id: String = "",
        cls: String = "android.view.View",
        clickable: Boolean = false,
        desc: String = "",
    ) = UiDumpParser.UiNode(
        text = text,
        contentDesc = desc,
        resourceId = id,
        className = cls,
        bounds = "[0,0][100,100]",
        clickable = clickable,
        focusable = false,
        enabled = true,
    )

    @Test
    fun `permission dialog is not mistaken for functional picker`() {
        val analysis = OverlayDetector.analyze(
            listOf(
                node(text = "允许“言控”使用相机？", cls = "android.app.AlertDialog"),
                node(text = "允许", cls = "android.widget.Button", clickable = true),
                node(text = "拒绝", cls = "android.widget.Button", clickable = true),
            ),
        )
        assertEquals(OverlayDetector.OverlayKind.PERMISSION_OVERLAY, analysis.kind)
    }

    @Test
    fun `terminal confirm page is detected`() {
        val analysis = OverlayDetector.analyze(
            listOf(
                node(text = "确认支付", cls = "android.widget.TextView"),
                node(text = "立即支付", cls = "android.widget.Button", clickable = true),
            ),
        )
        assertEquals(OverlayDetector.OverlayKind.TERMINAL_CONFIRM, analysis.kind)
    }

    @Test
    fun `closeable overlay on terminal page still dismissable`() {
        val analysis = OverlayDetector.analyze(
            listOf(
                node(text = "免密支付", cls = "android.widget.TextView"),
                node(text = "关闭", id = "com.lucky.luckyclient:id/close_iv", cls = "android.widget.ImageButton", clickable = true),
            ),
        )
        assertEquals(OverlayDetector.OverlayKind.PROMO_OVERLAY, analysis.kind)
    }

    @Test
    fun `functional picker with multiple options remains functional`() {
        val analysis = OverlayDetector.analyze(
            listOf(
                node(text = "选择门店", cls = "android.app.Dialog"),
                node(text = "国贸店", clickable = true),
                node(text = "中关村店", clickable = true),
            ),
        )
        assertEquals(OverlayDetector.OverlayKind.FUNCTIONAL_PICKER, analysis.kind)
    }
}
