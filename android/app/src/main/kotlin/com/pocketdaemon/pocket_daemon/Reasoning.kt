package com.pocketdaemon.pocket_daemon

import org.json.JSONArray
import org.json.JSONObject

/** One piece of a chat turn, independent of any provider's wire format. */
sealed class ReasoningPart {
    data class Text(val text: String) : ReasoningPart()
    data class Image(val base64: String, val mimeType: String) : ReasoningPart()
    data class ToolCall(val id: String, val name: String, val args: JSONObject) : ReasoningPart()
    data class ToolResult(val id: String, val name: String, val result: JSONObject) : ReasoningPart()
}

/**
 * A chat turn. [raw] keeps the producing provider's own representation of an assistant turn so
 * that provider can replay it verbatim (Gemini thought signatures, OpenAI tool_calls, Claude
 * content blocks). Any other provider rebuilds the turn from [parts].
 */
data class ReasoningMessage(
    val role: String,
    val parts: List<ReasoningPart>,
    val raw: Any? = null,
    val rawProvider: String? = null,
) {
    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"

        fun user(text: String) = ReasoningMessage(USER, listOf(ReasoningPart.Text(text)))
        fun assistant(text: String) = ReasoningMessage(ASSISTANT, listOf(ReasoningPart.Text(text)))
        fun toolResults(results: List<ReasoningPart.ToolResult>) = ReasoningMessage(USER, results)
    }

    val text: String
        get() = parts.filterIsInstance<ReasoningPart.Text>().joinToString("") { it.text }
}

data class ReasoningRequest(
    val system: String,
    val messages: List<ReasoningMessage>,
    val tools: List<ToolSpec> = emptyList(),
    /** Ask for the provider's own web grounding (Gemini google_search; Claude web search and fetch). */
    val webSearch: Boolean = false,
    /** When set, the provider is asked for a JSON object matching this schema. */
    val jsonSchema: JSONObject? = null,
    /** Anthropic effort hint: low, medium, or high. Ignored by other providers. */
    val effort: String? = null,
    val maxOutputTokens: Int = 4096,
)

object StopReasons {
    const val END_TURN = "end_turn"
    const val TOOL_USE = "tool_use"
    const val MAX_TOKENS = "max_tokens"
    const val REFUSAL = "refusal"
}

data class ReasoningResponse(
    val text: String,
    val toolCalls: List<ReasoningPart.ToolCall>,
    /** The assistant turn to append to history before sending tool results back. */
    val assistantMessage: ReasoningMessage,
    val stopReason: String,
    /** Source URLs the provider grounded on, when it reports them. */
    val sources: List<String> = emptyList(),
    /** Set when the provider declined the request (for example a Claude refusal). */
    val refusal: String? = null,
)

class ReasoningException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/** A text-in, text-or-tool-calls-out model call. One instance per provider and model. */
interface ReasoningClient {
    val provider: String
    val model: String

    @Throws(ReasoningException::class)
    fun generate(request: ReasoningRequest): ReasoningResponse
}

/** Helpers shared by the provider translators. Pure functions so they can be unit tested. */
object ReasoningSchemas {
    fun emptyObjectSchema(): JSONObject =
        JSONObject().put("type", "object").put("properties", JSONObject())

    /** Gemini's responseSchema is an OpenAPI subset with upper-case type names. */
    fun toGeminiSchema(schema: JSONObject): JSONObject {
        val out = JSONObject()
        val keys = schema.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = schema.get(key)
            out.put(
                key,
                when {
                    key == "type" && value is String -> value.uppercase()
                    key == "additionalProperties" -> continue
                    value is JSONObject -> toGeminiSchema(value)
                    value is JSONArray && key == "required" -> value
                    value is JSONArray -> JSONArray().also { arr ->
                        for (i in 0 until value.length()) {
                            val item = value.get(i)
                            arr.put(if (item is JSONObject) toGeminiSchema(item) else item)
                        }
                    }
                    else -> value
                },
            )
        }
        return out
    }

    /** Extracts the first JSON object from model text, tolerating code fences and prose around it. */
    fun parseJsonObject(text: String): JSONObject? {
        val trimmed = text.trim()
        try {
            return JSONObject(trimmed)
        } catch (_: Exception) {
        }
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JSONObject(trimmed.substring(start, end + 1))
        } catch (_: Exception) {
            null
        }
    }
}
