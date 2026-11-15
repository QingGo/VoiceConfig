package com.voiceconfig.app.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiKeyStoreTest {

    @Test
    fun `legacy flash model is normalized to deepseek flash`() {
        assertEquals("deepseek-flash", ApiKeyStore.normalizeModel("deepseek-v4-flash"))
    }

    @Test
    fun `legacy vision model is normalized to deepseek flash`() {
        assertEquals("deepseek-flash", ApiKeyStore.normalizeModel("deepseek-v4-flash-vision-exp"))
    }

    @Test
    fun `blank model is normalized to deepseek flash`() {
        assertEquals("deepseek-flash", ApiKeyStore.normalizeModel(""))
    }

    @Test
    fun `custom model stays unchanged`() {
        assertEquals("my-model", ApiKeyStore.normalizeModel("my-model"))
    }
}
