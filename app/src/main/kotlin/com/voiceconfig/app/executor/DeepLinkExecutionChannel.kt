package com.voiceconfig.app.executor

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.voiceconfig.core.executor.ExecutionChannel
import com.voiceconfig.core.executor.ExecutionRequest
import com.voiceconfig.core.executor.ExecutionResult
import com.voiceconfig.core.model.ExecutionMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeepLinkExecutionChannel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ExecutionChannel {

    override val supportedMode: ExecutionMode = ExecutionMode.DEEP_LINK

    override fun canExecute(request: ExecutionRequest): Boolean =
        !request.task.deepLink.isNullOrBlank()

    override fun execute(request: ExecutionRequest): ExecutionResult {
        val deepLink = request.task.deepLink ?: return ExecutionResult.failure(
            mode = supportedMode,
            errorCode = "NO_DEEP_LINK",
        )
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ExecutionResult.success(supportedMode)
        } catch (e: Exception) {
            ExecutionResult.failure(
                mode = supportedMode,
                errorCode = "DEEP_LINK_FAILED",
                message = e.message,
            )
        }
    }
}
