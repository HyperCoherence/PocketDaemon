package com.pocketdaemon.pocket_daemon

import org.json.JSONObject

/** Live API function-call behaviors. */
object ToolBehavior {
    /** The model waits for the result before continuing. gemini-3.8-live no longer assumes this, so it is declared. */
    const val BLOCKING = "BLOCKING"

    /** The model keeps talking while the tool runs; the result is delivered per [ToolScheduling]. */
    const val NON_BLOCKING = "NON_BLOCKING"
}

/** How the model should treat a NON_BLOCKING tool result once it arrives. */
object ToolScheduling {
    const val INTERRUPT = "INTERRUPT"
    const val WHEN_IDLE = "WHEN_IDLE"
    const val SILENT = "SILENT"
}

/** Live API interaction status reported by async-reasoning models such as gemini-3.8-live. */
object InteractionStatus {
    /** Server is still processing input or reasoning; more output may follow a turnComplete. */
    const val IN_PROGRESS = "IN_PROGRESS"

    /** Server is idle and waiting for user input: the real end of the agent's turn. */
    const val REQUIRES_ACTION = "REQUIRES_ACTION"
}

data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JSONObject? = null,
    /** [ToolBehavior.BLOCKING] or [ToolBehavior.NON_BLOCKING]; null leaves the choice to the model's default. */
    val behavior: String? = null,
    /** [ToolScheduling] hint attached to a NON_BLOCKING result. INTERRUPT when omitted. */
    val scheduling: String? = null,
) {
    val nonBlocking: Boolean get() = behavior == ToolBehavior.NON_BLOCKING
}

data class ProviderConfig(
    val role: String,
    val provider: String,
    val model: String,
    val voice: String?,
    val apiKey: String,
    /** Thinking level to request from a Gemini live session, or null to leave thinking off. */
    val thinkingLevel: String? = null,
    /** Reasoning effort hint (low, medium, high) for providers that support it. */
    val effort: String? = null,
)

interface VoiceSessionClient {
    var onAgentAudio: (ByteArray) -> Unit
    val connected: Boolean
    val ready: Boolean
    val sessionHandle: String?

    fun connect()
    fun disconnect()
    fun sendAudio(pcm16k: ByteArray)
    fun sendText(text: String)
    fun sendImage(imageBase64: String, mimeType: String, caption: String? = null) {}
    fun commitAudio() {}
}

object ProviderIds {
    const val GEMINI = "gemini"
    const val XAI = "xai"
    const val ANTHROPIC = "anthropic"
}

object AgentRoles {
    const val VOICE = "voice"
    const val CHAT = "chat"
    const val EXPERT = "expert"
    const val SCHEDULER = "scheduler"
    const val MEMORY = "memory"
    const val FABLE = "fable"

    /** REST roles that run on whichever provider has a key. */
    val REASONING = listOf(CHAT, EXPERT, SCHEDULER, MEMORY)
    val ALL = listOf(VOICE) + REASONING + FABLE
}
