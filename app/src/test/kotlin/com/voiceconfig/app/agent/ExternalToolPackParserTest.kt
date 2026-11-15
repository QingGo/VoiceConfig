package com.voiceconfig.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalToolPackParserTest {
    @Test
    fun `parses valid tool pack`() {
        val raw = """
        {
          "schema_version":"voiceconfig.toolpack/1",
          "id":"fan",
          "name":"FAN",
          "base_url":"https://example.com",
          "auth":{"type":"bearer"},
          "tools":[{
            "name":"tv_status",
            "description":"status",
            "input_schema":{"type":"object","properties":{},"additionalProperties":false},
            "request":{"method":"POST","path":"/rpc","body_template":{"jsonrpc":"2.0"}},
            "risk":"read","confirmation":"none","timeout_ms":8000
          }]
        }
        """.trimIndent()
        val pack = ExternalToolPackParser.parse(raw).getOrThrow()
        assertEquals("fan", pack.id)
        assertEquals(1, pack.tools.size)
        assertEquals("tv_status", pack.tools.first().name)
    }

    @Test
    fun `rejects insecure public http url`() {
        val raw = """{"schema_version":"voiceconfig.toolpack/1","id":"x","name":"x","base_url":"http://example.com","tools":[]}"""
        assertTrue(ExternalToolPackParser.parse(raw).isFailure)
    }
}
