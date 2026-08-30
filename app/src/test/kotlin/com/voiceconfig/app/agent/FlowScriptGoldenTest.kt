package com.voiceconfig.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowScriptGoldenTest {

    @Test
    fun `all builtin flow scripts pass validation and JSON roundtrip`() {
        BuiltinFlowScripts.all.forEach { script ->
            val errors = FlowScriptCodec.validate(script)
            assertTrue("${script.id} 校验应通过：$errors", errors.isEmpty())

            val decoded = FlowScriptCodec.parse(FlowScriptCodec.toJsonString(script))
            assertEquals("${script.id} JSON roundtrip 应一致", script, decoded)
        }
    }

    @Test
    fun `all template placeholders in builtin scripts are declared in parameters`() {
        BuiltinFlowScripts.all.forEach { script ->
            val declared = script.parameters.keys
            val used = mutableSetOf<String>()
            script.steps.forEach { step ->
                step.whenContains.forEach { collectRefs(it, used) }
                step.whenNotContains.forEach { collectRefs(it, used) }
                when (val action = step.action) {
                    is FlowAction.TapText -> action.candidates.forEach { collectRefs(it, used) }
                    is FlowAction.TapId -> action.resourceIds.forEach { collectRefs(it, used) }
                    is FlowAction.TapTextOrBack -> action.candidates.forEach { collectRefs(it, used) }
                    is FlowAction.InputText -> collectRefs(action.text, used)
                    is FlowAction.Wait -> Unit
                    FlowAction.Back, FlowAction.DismissPopups -> Unit
                }
            }
            script.terminalMarkers.forEach { collectRefs(it, used) }
            val undeclared = used - declared
            assertTrue("${script.id} 存在未声明参数：$undeclared", undeclared.isEmpty())
        }
    }

    private fun collectRefs(text: String, into: MutableSet<String>) {
        val regex = Regex("""\{([^}]+)\}""")
        regex.findAll(text).forEach { into.add(it.groupValues[1]) }
    }
}
