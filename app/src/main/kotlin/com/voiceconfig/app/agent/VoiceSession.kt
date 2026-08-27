package com.voiceconfig.app.agent

enum class VoiceSessionState {
    IDLE,
    LISTENING,
    CLARIFYING,
    EXECUTING,
    AWAITING_USER,
    COMPLETED,
}

data class VoiceSession(
    val id: String = "",
    val goal: String = "",
    val state: VoiceSessionState = VoiceSessionState.IDLE,
    val lastAssistantText: String = "",
    val pendingConfirmation: String? = null,
)

@javax.inject.Singleton
class VoiceSessionManager @javax.inject.Inject constructor() {
    private var session: VoiceSession = VoiceSession()

    fun current(): VoiceSession = session

    fun begin(goal: String): VoiceSession {
        session = VoiceSession(
            id = "voice_${System.currentTimeMillis()}",
            goal = goal,
            state = VoiceSessionState.EXECUTING,
        )
        return session
    }

    fun markClarifying(question: String): VoiceSession {
        session = session.copy(
            state = VoiceSessionState.CLARIFYING,
            lastAssistantText = question,
        )
        return session
    }

    fun markExecuting(): VoiceSession {
        session = session.copy(state = VoiceSessionState.EXECUTING)
        return session
    }

    fun waitUser(reason: String): VoiceSession {
        session = session.copy(
            state = VoiceSessionState.AWAITING_USER,
            pendingConfirmation = reason,
        )
        return session
    }

    fun complete(): VoiceSession {
        session = session.copy(state = VoiceSessionState.COMPLETED, pendingConfirmation = null)
        return session
    }

    fun reset() {
        session = VoiceSession()
    }
}
