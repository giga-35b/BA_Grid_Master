package com.bagridmaster.app.assistant

import com.bagridmaster.app.model.ImageInputMode

enum class AssistantPhase { STOPPED, CONSENT, CAPTURE, OVERLAY, RUNNING }

data class AssistantStatus(
    val requestId: Long = 0,
    val mode: ImageInputMode = ImageInputMode.SCREEN_CAPTURE,
    val phase: AssistantPhase = AssistantPhase.STOPPED,
    val message: String? = null,
) {
    val starting: Boolean get() = phase in setOf(AssistantPhase.CONSENT, AssistantPhase.CAPTURE, AssistantPhase.OVERLAY)
}

/** Session IDs prevent a late consent result or a dying service from reviving an old session. */
class AssistantWorkflow {
    var status = AssistantStatus()
        private set

    fun begin(mode: ImageInputMode): Long {
        status = AssistantStatus(status.requestId + 1, mode,
            if (mode == ImageInputMode.SCREEN_CAPTURE) AssistantPhase.CONSENT else AssistantPhase.OVERLAY)
        return status.requestId
    }

    fun advance(id: Long, from: AssistantPhase, to: AssistantPhase): Boolean {
        if (status.requestId != id || status.phase != from) return false
        status = status.copy(phase = to)
        return true
    }

    fun isCurrent(id: Long): Boolean = id == status.requestId && status.phase != AssistantPhase.STOPPED

    fun stop(message: String? = null) {
        status = status.copy(phase = AssistantPhase.STOPPED, message = message)
    }
}
