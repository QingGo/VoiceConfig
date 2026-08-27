package com.voiceconfig.app.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeAssistantClientTest {

    @Test
    fun `parse states extracts entity domain name and state`() {
        val json = """
            [
              {
                "entity_id": "climate.living_room",
                "state": "heat",
                "attributes": {"friendly_name": "客厅空调", "temperature": 26}
              },
              {
                "entity_id": "light.bedroom",
                "state": "on",
                "attributes": {"friendly_name": "卧室灯"}
              }
            ]
        """.trimIndent()
        val devices = HomeAssistantClient.parseStates(json)
        assertEquals(2, devices.size)
        val climate = devices[0]
        assertEquals("climate.living_room", climate.entityId)
        assertEquals("climate", climate.domain)
        assertEquals("客厅空调", climate.friendlyName)
        assertEquals("heat", climate.state)
        assertEquals(26, (climate.attributes["temperature"] as Number).toInt())
    }

    @Test
    fun `parse states handles blank or invalid input`() {
        assertEquals(0, HomeAssistantClient.parseStates("").size)
        assertEquals(0, HomeAssistantClient.parseStates("not json").size)
    }
}
