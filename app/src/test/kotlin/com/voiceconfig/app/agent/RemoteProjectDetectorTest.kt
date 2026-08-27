package com.voiceconfig.app.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteProjectDetectorTest {

    @Test
    fun `detect gradle project`() {
        val info = RemoteProjectDetector.detect("gradle")
        assertEquals(RemoteProjectType.GRADLE, info.type)
        assertEquals("./gradlew assembleDebug", info.buildCommand)
        assertEquals("./gradlew test", info.testCommand)
        assertEquals("./gradlew installDebug", info.installCommand)
    }

    @Test
    fun `detect node project`() {
        val info = RemoteProjectDetector.detect("node")
        assertEquals(RemoteProjectType.NODE, info.type)
        assertEquals("npm run build", info.buildCommand)
    }

    @Test
    fun `detect unknown project`() {
        val info = RemoteProjectDetector.detect("unknown")
        assertEquals(RemoteProjectType.UNKNOWN, info.type)
    }
}
