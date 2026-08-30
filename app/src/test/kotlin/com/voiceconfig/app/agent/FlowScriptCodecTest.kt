package com.voiceconfig.app.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowScriptCodecTest {

    @Test
    fun `builtin script roundtrip preserves actions and safety fields`() {
        val original = BuiltinFlowScripts.luckinStandardIce
        val json = FlowScriptCodec.toJsonString(original)
        val decoded = FlowScriptCodec.parse(json)
        assertNotNull(decoded)
        assertEquals(original.id, decoded!!.id)
        assertEquals(original.name, decoded.name)
        assertEquals(original.steps.size, decoded.steps.size)
        assertEquals(original.terminalMarkers, decoded.terminalMarkers)
        assertEquals(original.forbiddenActionTokens, decoded.forbiddenActionTokens)
        assertEquals(original.parameters, decoded.parameters)
        assertEquals(original.maxIterations, decoded.maxIterations)
        assertEquals(original.steps.map { it.id }, decoded.steps.map { it.id })
        assertEquals(original.steps.map { it.label }, decoded.steps.map { it.label })
        assertTrue(FlowScriptCodec.isValid(decoded))
    }

    @Test
    fun `parse rejects unknown format and malformed input`() {
        assertNull(FlowScriptCodec.parse("not json"))
        assertNull(FlowScriptCodec.parse("""{"format":"other","id":"x","name":"x","steps":[]}"""))
    }

    @Test
    fun `validation catches duplicate ids empty steps and missing candidates`() {
        val script = FlowScript(
            id = "test",
            name = "测试",
            steps = listOf(
                FlowStep(id = "same", whenContains = listOf("A"), action = FlowAction.TapText(emptyList())),
                FlowStep(id = "same", whenContains = listOf("B"), action = FlowAction.TapText(listOf("B"))),
            ),
        )
        val errors = FlowScriptCodec.validate(script)
        assertTrue(errors.any { it.contains("重复") })
        assertTrue(errors.any { it.contains("tap_text 候选项不能为空") })
    }

    @Test
    fun `all action types serialize and deserialize`() {
        val script = FlowScript(
            id = "actions",
            name = "动作",
            steps = listOf(
                FlowStep(id = "1", whenContains = listOf("a"), action = FlowAction.TapText(listOf("a"))),
                FlowStep(id = "2", whenContains = listOf("b"), action = FlowAction.TapId(listOf("id:b"))),
                FlowStep(id = "3", whenContains = listOf("c"), action = FlowAction.Back),
                FlowStep(id = "4", whenContains = listOf("d"), action = FlowAction.DismissPopups),
                FlowStep(id = "5", whenContains = listOf("e"), action = FlowAction.TapTextOrBack(listOf("e"))),
                FlowStep(id = "6", whenContains = listOf("f"), action = FlowAction.InputText("你好")),
                FlowStep(id = "7", whenContains = listOf("g"), action = FlowAction.Wait(300)),
            ),
        )
        val decoded = FlowScriptCodec.parse(FlowScriptCodec.toJsonString(script))
        assertNotNull(decoded)
        assertEquals(7, decoded!!.steps.size)
        assertTrue(decoded.steps[0].action is FlowAction.TapText)
        assertTrue(decoded.steps[1].action is FlowAction.TapId)
        assertTrue(decoded.steps[2].action is FlowAction.Back)
        assertTrue(decoded.steps[3].action is FlowAction.DismissPopups)
        assertTrue(decoded.steps[4].action is FlowAction.TapTextOrBack)
        assertTrue(decoded.steps[5].action is FlowAction.InputText)
        assertTrue(decoded.steps[6].action is FlowAction.Wait)
        assertEquals("你好", (decoded.steps[5].action as FlowAction.InputText).text)
        assertEquals(300L, (decoded.steps[6].action as FlowAction.Wait).ms)
    }

    @Test
    fun `imported json keeps source and status`() {
        val json = FlowScriptCodec.toJson(BuiltinFlowScripts.luckinStandardIce)
        val imported = FlowScriptCodec.fromJson(json)!!
            .copy(source = "import", status = FlowScriptStatus.PENDING)
        val decoded = FlowScriptCodec.parse(FlowScriptCodec.toJsonString(imported))
        assertEquals(FlowScriptStatus.PENDING, decoded!!.status)
        assertEquals("import", decoded.source)
    }
    @Test
    fun `parameters roundtrip and template expansion data is preserved`() {
        val script = FlowScript(
            id = "param_test",
            name = "参数测试",
            parameters = mapOf("drink" to "标准美式", "temperature" to "冰"),
            steps = listOf(
                FlowStep(
                    id = "drink",
                    whenContains = listOf("{drink}"),
                    action = FlowAction.TapText(listOf("{drink}")),
                ),
            ),
        )
        val decoded = FlowScriptCodec.parse(FlowScriptCodec.toJsonString(script))
        assertNotNull(decoded)
        assertEquals(script.parameters, decoded!!.parameters)
        assertEquals(listOf("{drink}"), decoded.steps.first().whenContains)
        val tap = decoded.steps.first().action as FlowAction.TapText
        assertEquals(listOf("{drink}"), tap.candidates)
    }


}
