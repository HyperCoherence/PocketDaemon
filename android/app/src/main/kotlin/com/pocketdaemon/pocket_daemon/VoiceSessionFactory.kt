package com.pocketdaemon.pocket_daemon

object VoiceSessionFactory {
    fun create(
        config: ProviderConfig,
        systemPrompt: String,
        tools: List<ToolSpec>,
        googleSearch: Boolean,
        resumeHandle: String? = null,
        onAgentAudio: (ByteArray) -> Unit,
        onTranscript: (speaker: String, text: String) -> Unit,
        onToolCall: ((name: String, id: String, args: org.json.JSONObject) -> org.json.JSONObject)? = null,
        onTurnComplete: (() -> Unit)? = null,
        onInterrupted: (() -> Unit)? = null,
        onInteractionStatus: ((status: String) -> Unit)? = null,
        onReady: (() -> Unit)? = null,
        onSessionEnded: (reason: String?) -> Unit,
    ): VoiceSessionClient {
        return when (config.provider) {
            ProviderIds.XAI -> XaiVoiceSessionClient(
                apiKey = config.apiKey,
                model = config.model,
                systemPrompt = systemPrompt,
                voice = config.voice,
                tools = tools,
                searchTools = googleSearch,
                onAgentAudio = onAgentAudio,
                onTranscript = onTranscript,
                onToolCall = onToolCall,
                onTurnComplete = onTurnComplete,
                onInterrupted = onInterrupted,
                onReady = onReady,
                onSessionEnded = onSessionEnded,
            )
            else -> GeminiVoiceSessionClient(
                apiKey = config.apiKey,
                model = config.model,
                systemPrompt = systemPrompt,
                voice = config.voice,
                tools = tools,
                googleSearch = googleSearch,
                resumeHandle = resumeHandle,
                thinkingLevel = config.thinkingLevel,
                onAgentAudio = onAgentAudio,
                onTranscript = onTranscript,
                onToolCall = onToolCall,
                onTurnComplete = onTurnComplete,
                onInterrupted = onInterrupted,
                onInteractionStatus = onInteractionStatus,
                onReady = onReady,
                onSessionEnded = onSessionEnded,
            )
        }
    }
}
