package com.voiceconfig.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppRoutesTest {

    @Test
    fun `top-level routes are stable and unique`() {
        val routes = listOf(
            AppRoutes.CONVERSATION,
            AppRoutes.AUTOMATION,
            AppRoutes.PROFILE,
        )
        assertEquals(3, routes.distinct().size)
        assertEquals("conversation", AppRoutes.CONVERSATION)
        assertEquals("automation", AppRoutes.AUTOMATION)
        assertEquals("profile", AppRoutes.PROFILE)
    }

    @Test
    fun `secondary routes are stable`() {
        assertEquals("shopping", AppRoutes.SHOPPING)
        assertEquals("home_assistant", AppRoutes.HOME_ASSISTANT)
    }
}
