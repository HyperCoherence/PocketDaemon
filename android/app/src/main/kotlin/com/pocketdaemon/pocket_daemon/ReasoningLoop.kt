package com.pocketdaemon.pocket_daemon

import android.util.Log
import org.json.JSONObject

/** Runs the generate, execute tools, generate again cycle shared by text chat and scheduled tasks. */
object ReasoningLoop {
    private const val TAG = "ReasoningLoop"

    /**
     * Appends every assistant turn and tool-result turn to [history] and returns the model's final
     * text, or null when it produced nothing or [maxRounds] was exhausted.
     */
    fun run(
        client: ReasoningClient,
        system: String,
        history: MutableList<ReasoningMessage>,
        tools: List<ToolSpec>,
        webSearch: Boolean,
        maxRounds: Int,
        maxOutputTokens: Int = 4096,
        onToolStart: ((name: String) -> Unit)? = null,
        onToolCall: (name: String, args: JSONObject) -> JSONObject,
    ): String? {
        repeat(maxRounds) {
            val response = client.generate(
                ReasoningRequest(
                    system = system,
                    messages = history.toList(),
                    tools = tools,
                    webSearch = webSearch,
                    maxOutputTokens = maxOutputTokens,
                ),
            )
            history.add(response.assistantMessage)

            if (response.refusal != null) {
                Log.w(TAG, "${client.provider}/${client.model} declined: ${response.refusal}")
            }
            if (response.toolCalls.isEmpty()) {
                return response.text.ifBlank { null }
            }

            val results = response.toolCalls.map { call ->
                onToolStart?.invoke(call.name)
                Log.i(TAG, "Tool call: ${call.name} args=${call.args}")
                val result = try {
                    onToolCall(call.name, call.args)
                } catch (e: Exception) {
                    Log.e(TAG, "Tool ${call.name} failed: ${e.message}")
                    JSONObject().put("status", "error").put("error", e.message ?: "tool failed")
                }
                ReasoningPart.ToolResult(call.id, call.name, result)
            }
            history.add(ReasoningMessage.toolResults(results))
        }
        Log.w(TAG, "Max tool rounds reached ($maxRounds)")
        return null
    }
}
