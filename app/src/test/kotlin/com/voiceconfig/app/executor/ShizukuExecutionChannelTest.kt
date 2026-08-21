package com.voiceconfig.app.executor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuExecutionChannelTest {

    @Test
    fun `normal launch output is not a failure`() {
        assertFalse(ShizukuExecutionChannel.isLaunchFailure(""))
        assertFalse(ShizukuExecutionChannel.isLaunchFailure("Starting: Intent { cmp=com.tencent.wework/.launch.LaunchSplashActivity }"))
    }

    @Test
    fun `real errors are failures`() {
        assertTrue(ShizukuExecutionChannel.isLaunchFailure("Error type 3"))
        assertTrue(ShizukuExecutionChannel.isLaunchFailure("Error: Activity class does not exist"))
        assertTrue(ShizukuExecutionChannel.isLaunchFailure("Unable to resolve activity"))
        assertTrue(ShizukuExecutionChannel.isLaunchFailure("Activity not started"))
    }

    @Test
    fun `brought to front warning is not a failure`() {
        val output = "Warning: Activity not started, its current task has been brought to the front"
        assertFalse(ShizukuExecutionChannel.isLaunchFailure(output))
    }

    @Test
    fun `delivered to running top-most instance warning is not a failure`() {
        val output = "Warning: Activity not started, intent has been delivered to currently running top-most instance."
        assertFalse(ShizukuExecutionChannel.isLaunchFailure(output))
    }
}
