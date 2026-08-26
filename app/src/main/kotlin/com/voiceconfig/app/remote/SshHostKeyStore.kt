package com.voiceconfig.app.remote

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 保存已信任的 SSH 主机指纹，用于 TOFU / 防止中间人。
 */
@Singleton
class SshHostKeyStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("voiceconfig_ssh_hostkeys", Context.MODE_PRIVATE)

    fun get(host: String, port: Int): String? {
        check(host) ?: return null
        return prefs.getString(key(host, port), null)
    }

    fun save(host: String, port: Int, fingerprint: String) {
        prefs.edit().putString(key(host, port), fingerprint).apply()
    }

    fun clear(host: String, port: Int) {
        prefs.edit().remove(key(host, port)).apply()
    }

    private fun key(host: String, port: Int) = "${host}:$port"
    private fun check(host: String) = host.ifBlank { null }
}
