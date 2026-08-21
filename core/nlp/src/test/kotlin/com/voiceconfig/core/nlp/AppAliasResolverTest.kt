package com.voiceconfig.core.nlp

import com.voiceconfig.core.model.AppAlias
import com.voiceconfig.core.model.AppAlias.AliasSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppAliasResolverTest {

    @Test
    fun `resolve builtin wecom`() {
        val resolver = AppAliasResolver()
        val resolved = resolver.resolve("企业微信")
        assertNotNull(resolved)
        assertEquals("com.tencent.wework", resolved!!.packageName)
    }

    @Test
    fun `resolve installed app label`() {
        val resolver = AppAliasResolver(
            installedAppsProvider = {
                listOf(InstalledApp(packageName = "com.example.app", label = "示例应用"))
            },
        )
        val resolved = resolver.resolve("示例应用")
        assertNotNull(resolved)
        assertEquals("com.example.app", resolved!!.packageName)
    }

    @Test
    fun `blank returns null`() {
        assertNull(AppAliasResolver().resolve("  "))
    }

    @Test
    fun `add user alias then resolve`() {
        val resolver = AppAliasResolver()
        resolver.addUserAlias("内部工具", "com.example.internal")
        val resolved = resolver.resolve("内部工具")
        assertNotNull(resolved)
        assertEquals("com.example.internal", resolved!!.packageName)
    }

    @Test
    fun `resolve from provider`() {
        val resolver = AppAliasResolver(
            userAliasesProvider = {
                mapOf(
                    "内部工具" to AppAlias(
                        alias = "内部工具",
                        packageName = "com.example.internal",
                        source = AliasSource.USER,
                    ),
                )
            },
        )
        val resolved = resolver.resolve("内部工具")
        assertNotNull(resolved)
        assertEquals("com.example.internal", resolved!!.packageName)
    }

    @Test
    fun `builtin wechat wins over installed wechat listening`() {
        val resolver = AppAliasResolver(
            installedAppsProvider = {
                listOf(
                    InstalledApp(packageName = "com.tencent.wechat.listen", label = "微信听书", activityName = "com.tencent.wechat.listen.MainActivity"),
                    InstalledApp(packageName = "com.tencent.mm", label = "微信", activityName = "com.tencent.mm.ui.LauncherUI"),
                )
            },
        )
        val resolved = resolver.resolve("微信")
        assertNotNull(resolved)
        assertEquals("com.tencent.mm", resolved!!.packageName)
    }
}
