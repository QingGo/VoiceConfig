package com.voiceconfig.app.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiKeyStoreTest {

    @Test
    fun `legacy flash model is normalized to vision model`() {
        assertEquals(
            "deepseek-v4-flash-vision-exp",
            ApiKeyStore.normalizeModel("deepseek-v4-flash"),
        )
    }

    @Test
    fun `blank model is normalized to vision model`() {
        assertEquals(
            "deepseek-v4-flash-vision-exp",
            ApiKeyStore.normalizeModel(""),
        )
    }

    @Test
    fun `vision model stays unchanged`() {
        assertEquals(
            "deepseek-v4-flash-vision-exp",
            ApiKeyStore.normalizeModel("deepseek-v4-flash-vision-exp"),
        )
    }
}
