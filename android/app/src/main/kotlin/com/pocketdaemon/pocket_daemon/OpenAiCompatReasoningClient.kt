package com.pocketdaemon.pocket_daemon

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** OpenAI-compatible chat completions. Used for xAI (Grok) text models. */
class OpenAiCompatReasoningClient(
    private val apiKey: String,
    override val model: String,
    private val http: OkHttpClient,
    override val provider: String = ProviderIds.XAI,
    private val baseUrl: String = "https://api.x.ai/v1",
) : ReasoningClient {
    companion object {
        private const val TAG = "OpenAiCompatReasoning"
        private val JSON = "application/json".toMediaType()
    }

    override fun generate(request: ReasoningRequest): ReasoningResponse {
        val body = OpenAiReasoningFormat.buildRequest(model, request)
        val httpRequest = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(JSON))
            .build()
        val raw = try {
            http.newCall(httpRequest).execute().use { response ->
                val text = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.e(TAG, "$model returned ${response.code}: ${text.take(300)}")
                    throw ReasoningException("$provider $model returned ${response.code}: ${text.take(200)}", response.code)
                }
                text
            }
        } catch (e: ReasoningException) {
            throw e
        } catch (e: Exception) {
            throw ReasoningException("$provider request failed: ${e.message}", cause = e)
        }
        return OpenAiReasoningFormat.parseResponse(JSONObject(raw), provider)
    }
}

/** Pure translation between the neutral model and OpenAI-style chat completions JSON. */
object OpenAiReasoningFormat {
    fun buildRequest(model: String, request: ReasoningRequest): JSONObject {
        val messages = JSONArray()
        if (request.system.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", request.system))
        }
        for (message in request.messages) {
            for (converted in toMessages(message)) messages.put(converted)
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("max_tokens", request.maxOutputTokens)

        if (request.tools.isNotEmpty()) {
            val tools = JSONArray()
            for (tool in request.tools) {
                tools.put(JSONObject().put("type", "function").put("function", JSONObject()
                    .put("name", tool.name)
                    .put("description", tool.description)
                    .put("parameters", tool.parameters ?: ReasoningSchemas.emptyObjectSchema())))
            }
            body.put("tools", tools)
        }
        if (request.jsonSchema != null) {
            body.put("response_format", JSONObject().put("type", "json_schema").put("json_schema", JSONObject()
                .put("name", "result")
                .put("schema", request.jsonSchema)))
        }
        return body
    }

    /** Tool results become one `tool` message each, so a neutral turn can map to several messages. */
    fun toMessages(message: ReasoningMessage): List<JSONObject> {
        val raw = message.raw
        if (message.rawProvider != null && message.rawProvider != ProviderIds.GEMINI &&
            message.rawProvider != ProviderIds.ANTHROPIC && raw is JSONObject
        ) {
            return listOf(raw)
        }
        val toolResults = message.parts.filterIsInstance<ReasoningPart.ToolResult>()
        if (toolResults.isNotEmpty()) {
            return toolResults.map {
                JSONObject().put("role", "tool").put("tool_call_id", it.id).put("content", it.result.toString())
            }
        }
        if (message.role == ReasoningMessage.ASSISTANT) {
            val out = JSONObject().put("role", "assistant")
            val text = message.text
            out.put("content", if (text.isBlank()) JSONObject.NULL else text)
            val calls = message.parts.filterIsInstance<ReasoningPart.ToolCall>()
            if (calls.isNotEmpty()) {
                val arr = JSONArray()
                for (call in calls) {
                    arr.put(JSONObject().put("id", call.id).put("type", "function").put("function", JSONObject()
                        .put("name", call.name)
                        .put("arguments", call.args.toString())))
                }
                out.put("tool_calls", arr)
            }
            return listOf(out)
        }
        val images = message.parts.filterIsInstance<ReasoningPart.Image>()
        if (images.isEmpty()) {
            return listOf(JSONObject().put("role", "user").put("content", message.text))
        }
        val content = JSONArray()
        for (image in images) {
            content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject()
                .put("url", "data:${image.mimeType};base64,${image.base64}")))
        }
        if (message.text.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", message.text))
        return listOf(JSONObject().put("role", "user").put("content", content))
    }

    fun parseResponse(json: JSONObject, provider: String): ReasoningResponse {
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
            ?: throw ReasoningException("$provider returned no choices: ${json.toString().take(200)}")
        val message = choice.optJSONObject("message") ?: JSONObject().put("role", "assistant")
        val text = message.opt("content")?.let { if (it is String) it else "" } ?: ""

        val calls = mutableListOf<ReasoningPart.ToolCall>()
        message.optJSONArray("tool_calls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val call = arr.optJSONObject(i) ?: continue
                val function = call.optJSONObject("function") ?: continue
                val args = ReasoningSchemas.parseJsonObject(function.optString("arguments", "{}")) ?: JSONObject()
                calls.add(ReasoningPart.ToolCall(call.optString("id", ""), function.optString("name", ""), args))
            }
        }

        val neutral = mutableListOf<ReasoningPart>()
        if (text.isNotBlank()) neutral.add(ReasoningPart.Text(text))
        neutral.addAll(calls)

        val finish = choice.optString("finish_reason", "")
        val stop = when {
            calls.isNotEmpty() -> StopReasons.TOOL_USE
            finish == "length" -> StopReasons.MAX_TOKENS
            finish == "content_filter" -> StopReasons.REFUSAL
            else -> StopReasons.END_TURN
        }
        return ReasoningResponse(
            text = text,
            toolCalls = calls,
            assistantMessage = ReasoningMessage(
                role = ReasoningMessage.ASSISTANT,
                parts = neutral,
                raw = message,
                rawProvider = provider,
            ),
            stopReason = stop,
            refusal = if (stop == StopReasons.REFUSAL) "$provider filtered the response" else null,
        )
    }
}
