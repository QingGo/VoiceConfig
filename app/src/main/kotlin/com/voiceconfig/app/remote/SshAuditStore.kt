package com.voiceconfig.app.remote

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * 本地 SSH 审计：所有 SSH 命令、文件操作、引导与终端命令都追加到
 * files/ssh_audit.jsonl，便于事后排查。
 */
@Singleton
class SshAuditStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val file = File(context.filesDir, "ssh_audit.jsonl")

    @Synchronized
    fun record(
        host: String,
        port: Int,
        username: String,
        action: String,
        detail: String,
        ok: Boolean,
    ) {
        runCatching {
            val line = JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("host", host)
                .put("port", port)
                .put("username", username)
                .put("action", action)
                .put("detail", detail.take(2000))
                .put("ok", ok)
                .toString()
            file.appendText(line + "\n")
        }
    }
}
