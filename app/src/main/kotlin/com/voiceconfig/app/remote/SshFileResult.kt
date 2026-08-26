package com.voiceconfig.app.remote

data class SshFileResult(
    val ok: Boolean,
    val path: String,
    val content: String,
    val error: String? = null,
    val exitCode: Int? = null,
)
