package com.github.ponyhuang.agentacpplugin.services.acp

import com.agentclientprotocol.model.SessionUpdate
import com.github.ponyhuang.agentacpplugin.services.session.TurnCompletionReason

interface SessionUpdateIngress {
    fun onSessionUpdate(sessionId: String, update: SessionUpdate)

    fun onPromptFinished(sessionId: String, reason: TurnCompletionReason)

    fun onPromptFailed(sessionId: String, message: String)
}
