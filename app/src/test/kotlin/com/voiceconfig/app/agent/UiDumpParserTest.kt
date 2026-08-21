package com.voiceconfig.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiDumpParserTest {

    @Test
    fun `parses node attributes`() {
        val xml = """
            <hierarchy rotation="0">
              <node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.example" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,0][1080,2400]">
                <node index="1" text="打卡" resource-id="com.example:id/checkin" class="android.widget.Button" package="com.example" content-desc="上班打卡" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[360,1200][720,1400]"/>
              </node>
            </hierarchy>
        """.trimIndent()

        val nodes = UiDumpParser.parse(xml)
        assertEquals(2, nodes.size)
        val button = nodes[1]
        assertEquals("打卡", button.text)
        assertEquals("com.example:id/checkin", button.resourceId)
        assertTrue(button.clickable)
        assertEquals("[360,1200][720,1400]", button.bounds)
    }

    @Test
    fun `summarize keeps only meaningful nodes`() {
        val xml = """
            <node index="0" text="" resource-id="" class="android.widget.FrameLayout" clickable="false" enabled="true" focusable="false" bounds="[0,0][1080,2400]">
              <node index="1" text="企业微信" resource-id="com.tencent.wework:id/title" class="android.widget.TextView" clickable="false" enabled="true" focusable="false" bounds="[0,100][500,200]"/>
              <node index="2" text="" resource-id="" class="android.widget.FrameLayout" clickable="false" enabled="true" focusable="false" bounds="[0,0][1,1]"/>
            </node>
        """.trimIndent()
        val summary = UiDumpParser.summarize(xml)
        assertTrue(summary.contains("企业微信"))
        assertFalse(summary.contains("FrameLayout"))
    }

    private fun assertFalse(condition: Boolean) {
        if (condition) throw AssertionError("Expected false")
    }
}
