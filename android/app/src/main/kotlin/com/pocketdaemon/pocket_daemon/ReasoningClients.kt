package com.pocketdaemon.pocket_daemon

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Builds the reasoning client for an agent role from the app's provider configuration. */
object ReasoningClients {
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * The role's configured provider, or whichever provider has a key when that one does not.
     * Throws when no usable key exists at all.
     */
    fun forRole(app: PocketDaemonApp, role: String): ReasoningClient {
        val config = app.agentConfig(role)
        if (config.apiKey.isBlank()) {
            throw ReasoningException("No API key for the ${config.provider} provider (role $role). Add one in settings.")
        }
        return forConfig(config)
    }

    fun forConfig(config: ProviderConfig): ReasoningClient = when (config.provider) {
        ProviderIds.ANTHROPIC -> AnthropicReasoningClient(config.apiKey, config.model, config.effort)
        ProviderIds.XAI -> OpenAiCompatReasoningClient(config.apiKey, config.model, http)
        else -> GeminiReasoningClient(config.apiKey, config.model, http)
    }
}
