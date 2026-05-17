package com.pocketdaemon.pocket_daemon

import org.json.JSONObject

data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JSONObject? = null,
)

data class ProviderConfig(
    val role: String,
    val provider: String,
    val model: String,
    val voice: String?,
    val apiKey: String,
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
}

object AgentRoles {
    const val VOICE = "voice"
    const val EXPERT = "expert"
    const val SCHEDULER = "scheduler"
    const val MEMORY = "memory"

    val ALL = listOf(VOICE, EXPERT, SCHEDULER, MEMORY)
}
