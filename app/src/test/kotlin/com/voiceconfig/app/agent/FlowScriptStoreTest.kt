package com.voiceconfig.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowScriptStoreTest {

    private class FakeStorage : FlowScriptStorage {
        var stored: MutableList<FlowScript> = mutableListOf()
        override fun readCustom(): List<FlowScript> = stored.toList()
        override fun writeCustom(scripts: List<FlowScript>) {
            stored = scripts.toMutableList()
        }
    }

    @Test
    fun `builtins are always present and protected`() {
        val store = FlowScriptStore(FakeStorage())
        assertTrue(store.all().any { it.id == "builtin_luckin_standard_ice" })
        val builtin = store.get("builtin_luckin_standard_ice")!!
        assertFalse(store.delete(builtin.id))
        assertFalse(store.setEnabled(builtin.id, false))
        assertNull(store.save(builtin.copy(source = "builtin")))
    }

    @Test
    fun `import becomes pending and disabled until approved`() {
        val storage = FakeStorage()
        val store = FlowScriptStore(storage)
        val imported = store.importJson(
            FlowScriptCodec.toJsonString(BuiltinFlowScripts.luckinStandardIce),
            source = "file",
        )
        assertNotNull(imported)
        val script = store.get(imported!!.id)!!
        assertEquals(FlowScriptStatus.PENDING, script.status)
        assertFalse(script.enabled)
        assertEquals("file", script.source)
        assertFalse(store.approvedEnabled().any { it.id == imported.id })

        assertTrue(store.approve(imported.id))
        assertTrue(store.approvedEnabled().any { it.id == imported.id })
        assertTrue(store.get(imported.id)!!.enabled)
    }

    @Test
    fun `import invalid json returns null`() {
        val store = FlowScriptStore(FakeStorage())
        assertNull(store.importJson("not json"))
        assertNull(store.importJson("""{"format":"voiceconfig-flow-script","id":"x","name":"x","steps":[]}"""))
    }

    @Test
    fun `export all contains builtin and custom`() {
        val storage = FakeStorage()
        val store = FlowScriptStore(storage)
        store.save(
            FlowScript(
                id = "custom_1",
                name = "自定义",
                source = "user",
                steps = listOf(FlowStep(id = "s1", whenContains = listOf("X"), action = FlowAction.TapText(listOf("X")))),
            ),
        )
        val exported = store.exportAllJson()
        assertTrue(exported.contains("builtin_luckin_standard_ice"))
        assertTrue(exported.contains("custom_1"))
    }
    @Test
    fun `import array of scripts imports all as pending`() {
        val store = FlowScriptStore(FakeStorage())
        val json = """[${FlowScriptCodec.toJsonString(BuiltinFlowScripts.luckinStandardIce)}]"""
        val imported = store.importJson(json)
        assertNotNull(imported)
        val custom = store.all().filter { it.source != "builtin" }
        assertEquals(1, custom.size)
        assertEquals(FlowScriptStatus.PENDING, custom.first().status)
        assertFalse(custom.first().enabled)
    }


}
