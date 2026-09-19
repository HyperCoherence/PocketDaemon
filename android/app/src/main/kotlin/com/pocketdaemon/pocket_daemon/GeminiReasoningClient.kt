package com.pocketdaemon.pocket_daemon

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Gemini generateContent over REST. */
class GeminiReasoningClient(
    private val apiKey: String,
    override val model: String,
    private val http: OkHttpClient,
) : ReasoningClient {
    companion object {
        private const val TAG = "GeminiReasoning"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private val JSON = "application/json".toMediaType()
    }

    override val provider: String = ProviderIds.GEMINI

    override fun generate(request: ReasoningRequest): ReasoningResponse {
        val body = GeminiReasoningFormat.buildRequest(request)
        val httpRequest = Request.Builder()
            .url("$BASE_URL/$model:generateContent?key=$apiKey")
            .post(body.toString().toRequestBody(JSON))
            .build()
        val raw = try {
            http.newCall(httpRequest).execute().use { response ->
                val text = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.e(TAG, "$model returned ${response.code}: ${text.take(300)}")
                    throw ReasoningException("Gemini $model returned ${response.code}: ${text.take(200)}", response.code)
                }
                text
            }
        } catch (e: ReasoningException) {
            throw e
        } catch (e: Exception) {
            throw ReasoningException("Gemini request failed: ${e.message}", cause = e)
        }
        return GeminiReasoningFormat.parseResponse(JSONObject(raw))
    }
}

/** Pure translation between the neutral model and Gemini's generateContent JSON. */
object GeminiReasoningFormat {
    fun buildRequest(request: ReasoningRequest): JSONObject {
        val body = JSONObject()
        if (request.system.isNotBlank()) {
            body.put("systemInstruction", JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text", request.system))))
        }
        val contents = JSONArray()
        for (message in request.messages) contents.put(toContent(message))
        body.put("contents", contents)

        val tools = JSONArray()
        val declarations = JSONArray()
        for (tool in request.tools) {
            val decl = JSONObject().put("name", tool.name).put("description", tool.description)
            if (tool.parameters != null) decl.put("parameters", tool.parameters)
            declarations.put(decl)
        }
        if (declarations.length() > 0) tools.put(JSONObject().put("functionDeclarations", declarations))
        if (request.webSearch) tools.put(JSONObject().put("google_search", JSONObject()))
        if (tools.length() > 0) body.put("tools", tools)

        val generation = JSONObject().put("maxOutputTokens", request.maxOutputTokens)
        if (request.jsonSchema != null) {
            generation.put("responseMimeType", "application/json")
            generation.put("responseSchema", ReasoningSchemas.toGeminiSchema(request.jsonSchema))
        }
        body.put("generationConfig", generation)
        return body
    }

    fun toContent(message: ReasoningMessage): JSONObject {
        val raw = message.raw
        if (message.rawProvider == ProviderIds.GEMINI && raw is JSONObject) return raw
        val parts = JSONArray()
        for (part in message.parts) {
            when (part) {
                is ReasoningPart.Text -> parts.put(JSONObject().put("text", part.text))
                is ReasoningPart.Image -> parts.put(JSONObject().put("inlineData", JSONObject()
                    .put("mimeType", part.mimeType)
                    .put("data", part.base64)))
                is ReasoningPart.ToolCall -> {
                    val call = JSONObject().put("name", part.name).put("args", part.args)
                    if (part.id.isNotBlank()) call.put("id", part.id)
                    parts.put(JSONObject().put("functionCall", call))
                }
                is ReasoningPart.ToolResult -> {
                    val result = JSONObject().put("name", part.name).put("response", part.result)
                    if (part.id.isNotBlank()) result.put("id", part.id)
                    parts.put(JSONObject().put("functionResponse", result))
                }
            }
        }
        val role = if (message.role == ReasoningMessage.ASSISTANT) "model" else "user"
        return JSONObject().put("role", role).put("parts", parts)
    }

    fun parseResponse(json: JSONObject): ReasoningResponse {
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw ReasoningException(
                "Gemini returned no candidates: ${json.optJSONObject("promptFeedback")?.toString()?.take(200) ?: ""}".trim(),
            )
        val content = candidate.optJSONObject("content")
            ?: JSONObject().put("role", "model").put("parts", JSONArray())
        val parts = content.optJSONArray("parts") ?: JSONArray()

        val text = StringBuilder()
        val calls = mutableListOf<ReasoningPart.ToolCall>()
        val neutral = mutableListOf<ReasoningPart>()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.optBoolean("thought", false)) continue
            part.optJSONObject("functionCall")?.let { fc ->
                val call = ReasoningPart.ToolCall(
                    id = fc.optString("id", ""),
                    name = fc.optString("name", ""),
                    args = fc.optJSONObject("args") ?: JSONObject(),
                )
                calls.add(call)
                neutral.add(call)
            }
            if (part.has("text")) {
                val t = part.optString("text", "")
                text.append(t)
                neutral.add(ReasoningPart.Text(t))
            }
        }

        val sources = mutableListOf<String>()
        candidate.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")?.let { chunks ->
            for (i in 0 until chunks.length()) {
                val uri = chunks.optJSONObject(i)?.optJSONObject("web")?.optString("uri", "") ?: ""
                if (uri.isNotBlank()) sources.add(uri)
            }
        }

        val finish = candidate.optString("finishReason", "")
        val stop = when {
            calls.isNotEmpty() -> StopReasons.TOOL_USE
            finish == "MAX_TOKENS" -> StopReasons.MAX_TOKENS
            finish == "SAFETY" || finish == "PROHIBITED_CONTENT" || finish == "BLOCKLIST" -> StopReasons.REFUSAL
            else -> StopReasons.END_TURN
        }
        return ReasoningResponse(
            text = text.toString(),
            toolCalls = calls,
            assistantMessage = ReasoningMessage(
                role = ReasoningMessage.ASSISTANT,
                parts = neutral,
                raw = content,
                rawProvider = ProviderIds.GEMINI,
            ),
            stopReason = stop,
            sources = sources.distinct(),
            refusal = if (stop == StopReasons.REFUSAL) "Gemini blocked the response ($finish)" else null,
        )
    }
}
