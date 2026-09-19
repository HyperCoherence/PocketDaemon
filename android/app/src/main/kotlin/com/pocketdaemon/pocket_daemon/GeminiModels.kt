package com.pocketdaemon.pocket_daemon

/** Gemini model ids and the capability rules the Live client depends on. */
object GeminiModels {
    /** Default realtime voice model. Reasons on its own; rejects an explicit thinkingLevel. */
    const val DEFAULT_LIVE = "gemini-3.8-live"

    /** Live variant that accepts a configurable thinkingLevel. */
    const val LIVE_EXTENDED_THINKING = "gemini-3.8-live-extended-thinking"

    /** REST model used by the text chat, expert, scheduler, and memory roles. */
    const val DEFAULT_REASONING = "gemini-3.1-pro-preview"

    /** Older live models that config normalization upgrades to [DEFAULT_LIVE]. */
    val LEGACY_LIVE = setOf("gemini-3.1-flash-live-preview")

    val THINKING_LEVELS = listOf("minimal", "low", "medium", "high")
    const val DEFAULT_THINKING_LEVEL = "low"

    fun isLiveModel(model: String): Boolean = model.trim().contains("-live")

    /**
     * gemini-3.8-live handles reasoning itself and fails setup when thinkingLevel is sent.
     * The extended-thinking variant and older live previews accept one.
     */
    fun supportsThinkingLevel(model: String): Boolean {
        val trimmed = model.trim()
        if (trimmed.startsWith(DEFAULT_LIVE)) return trimmed.contains("extended-thinking")
        return true
    }

    /** Returns a canonical thinking level or null when the value is not one we know. */
    fun normalizeThinkingLevel(level: String?): String? {
        val normalized = level?.trim()?.lowercase() ?: return null
        return if (normalized in THINKING_LEVELS) normalized else null
    }
}
