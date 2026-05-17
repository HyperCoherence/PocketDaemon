package com.pocketdaemon.pocket_daemon

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class XaiVoiceSessionClient(
    private val apiKey: String,
    private val model: String,
    private val systemPrompt: String,
    private val voice: String? = null,
    private val tools: List<ToolSpec> = emptyList(),
    private val searchTools: Boolean = false,
    @Volatile override var onAgentAudio: (ByteArray) -> Unit,
    private val onTranscript: (speaker: String, text: String) -> Unit,
    private val onToolCall: ((name: String, id: String, args: JSONObject) -> JSONObject)? = null,
    private val onTurnComplete: (() -> Unit)? = null,
    private val onInterrupted: (() -> Unit)? = null,
    private val onReady: (() -> Unit)? = null,
    private val onSessionEnded: (reason: String?) -> Unit,
) : VoiceSessionClient {

    companion object {
        private const val TAG = "XaiVoiceSessionClient"
        private const val BASE_URL = "wss://api.x.ai/v1/realtime"
    }

    private data class PendingFunctionCall(
        var name: String = "",
        var callId: String = "",
        val arguments: StringBuilder = StringBuilder(),
    )

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile override var connected = false
        private set
    @Volatile override var ready = false
        private set
    override val sessionHandle: String?
        get() = null

    @Volatile private var closeReason: String? = null
    @Volatile private var responseInProgress = false
    private val pendingCalls = mutableMapOf<String, PendingFunctionCall>()
    private val handledCallIds = mutableSetOf<String>()

    override fun connect() {
        val selectedModel = model.ifBlank { PocketDaemonApp.DEFAULT_XAI_MODEL }
        val encodedModel = URLEncoder.encode(selectedModel, "UTF-8")
        val request = Request.Builder()
            .url("$BASE_URL?model=$encodedModel")
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        Log.i(TAG, "Connecting to xAI Voice Agent ($selectedModel)")
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                Log.i(TAG, "WebSocket connected, sending session.update")
                sendSetup(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleMessage(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code $reason")
                closeReason = if (code != 1000) "[$code] $reason" else null
                connected = false
                ready = false
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                connected = false
                ready = false
                onSessionEnded(t.message)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code")
                connected = false
                ready = false
                onSessionEnded(closeReason)
            }
        })
    }

    override fun disconnect() {
        connected = false
        ready = false
        ws?.close(1000, "session ended")
        ws = null
    }

    override fun sendAudio(pcm16k: ByteArray) {
        if (!ready) return
        val b64 = Base64.encodeToString(pcm16k, Base64.NO_WRAP)
        ws?.send(JSONObject()
            .put("type", "input_audio_buffer.append")
            .put("audio", b64)
            .toString())
    }

    override fun commitAudio() {
        if (!ready) return
        // With server VAD this is normally automatic, but this nudges PTT turns
        // that have already stopped capturing into response generation.
        ws?.send(JSONObject().put("type", "response.create").toString())
    }

    override fun sendText(text: String) {
        if (!ready) {
            Log.w(TAG, "sendText called before ready, ignoring")
            return
        }
        ws?.send(JSONObject()
            .put("type", "conversation.item.create")
            .put("item", JSONObject()
                .put("type", "message")
                .put("role", "user")
                .put("content", JSONArray().put(JSONObject()
                    .put("type", "input_text")
                    .put("text", text))))
            .toString())
        ws?.send(JSONObject().put("type", "response.create").toString())
        Log.i(TAG, "Sent text: ${text.take(80)}")
    }

    override fun sendImage(imageBase64: String, mimeType: String, caption: String?) {
        val fallback = caption?.takeIf { it.isNotBlank() }
            ?: "An image was selected, but the configured xAI realtime voice session does not accept image input yet."
        sendText(fallback)
    }

    private fun sendSetup(webSocket: WebSocket) {
        val toolArray = JSONArray()
        if (searchTools) {
            toolArray.put(JSONObject().put("type", "web_search"))
            toolArray.put(JSONObject().put("type", "x_search"))
        }
        for (tool in tools) {
            toolArray.put(JSONObject()
                .put("type", "function")
                .put("name", tool.name)
                .put("description", tool.description)
                .put("parameters", tool.parameters ?: JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject())))
        }

        val session = JSONObject()
            .put("voice", voice?.lowercase()?.ifBlank { "eve" } ?: "eve")
            .put("instructions", systemPrompt)
            .put("turn_detection", JSONObject()
                .put("type", "server_vad")
                .put("silence_duration_ms", 700))
            .put("audio", JSONObject()
                .put("input", JSONObject()
                    .put("format", JSONObject()
                        .put("type", "audio/pcm")
                        .put("rate", 16000)))
                .put("output", JSONObject()
                    .put("format", JSONObject()
                        .put("type", "audio/pcm")
                        .put("rate", 24000))))

        if (toolArray.length() > 0) session.put("tools", toolArray)

        val setup = JSONObject()
            .put("type", "session.update")
            .put("session", session)

        Log.i(TAG, "Setup sent: model=${model.ifBlank { PocketDaemonApp.DEFAULT_XAI_MODEL }}, voice=$voice, searchTools=$searchTools, tools=${tools.map { it.name }}")
        webSocket.send(setup.toString())
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (val type = json.optString("type", "")) {
                "session.updated" -> {
                    if (!ready) {
                        ready = true
                        Log.i(TAG, "Session updated - ready")
                        onReady?.invoke()
                    }
                }
                "response.created" -> {
                    responseInProgress = true
                }
                "response.output_audio.delta", "response.audio.delta" -> {
                    val b64 = json.optString("delta", json.optString("audio", ""))
                    if (b64.isNotBlank()) onAgentAudio(Base64.decode(b64, Base64.DEFAULT))
                }
                "conversation.item.input_audio_transcription.completed" -> {
                    val transcript = json.optString("transcript", json.optString("text", ""))
                    if (transcript.isNotBlank()) {
                        Log.i(TAG, "User: $transcript")
                        onTranscript("user", transcript)
                    }
                }
                "response.output_audio_transcript.delta",
                "response.audio_transcript.delta",
                "response.text.delta" -> {
                    val delta = json.optString("delta", "")
                    if (delta.isNotBlank()) onTranscript("agent", delta)
                }
                "response.output_audio_transcript.done",
                "response.audio_transcript.done",
                "response.text.done" -> {
                    val transcript = json.optString("transcript", json.optString("text", ""))
                    if (transcript.isNotBlank()) onTranscript("agent", transcript)
                }
                "input_audio_buffer.speech_started" -> {
                    Log.i(TAG, "Speech started")
                    if (responseInProgress) {
                        ws?.send(JSONObject().put("type", "response.cancel").toString())
                        responseInProgress = false
                    }
                    onInterrupted?.invoke()
                }
                "response.output_item.added" -> {
                    json.optJSONObject("item")?.let { rememberFunctionCall(it, json) }
                }
                "response.function_call_arguments.delta" -> {
                    appendFunctionArguments(json)
                }
                "response.function_call_arguments.done" -> {
                    finishFunctionArguments(json)
                }
                "response.output_item.done" -> {
                    json.optJSONObject("item")?.let { item ->
                        rememberFunctionCall(item, json)
                        maybeHandleFunctionItem(item, json)
                    }
                }
                "response.done" -> {
                    responseInProgress = false
                    parseFunctionCallsFromResponse(json)
                    Log.i(TAG, "Turn complete")
                    onTurnComplete?.invoke()
                }
                "error" -> {
                    val err = json.optJSONObject("error")
                    val message = err?.optString("message") ?: json.optString("message", "xAI realtime error")
                    Log.e(TAG, message)
                    onSessionEnded(message)
                }
                else -> {
                    if (type.isNotBlank() && type.contains("failed", ignoreCase = true)) {
                        Log.w(TAG, "Event failed: $text")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Parse error: ${e.message}")
        }
    }

    private fun rememberFunctionCall(item: JSONObject, envelope: JSONObject) {
        if (item.optString("type") != "function_call") return
        val key = functionKey(item, envelope)
        synchronized(pendingCalls) {
            val call = pendingCalls.getOrPut(key) { PendingFunctionCall() }
            call.name = item.optString("name", call.name)
            call.callId = item.optString("call_id", item.optString("id", call.callId))
            val args = item.optString("arguments", "")
            if (args.isNotBlank() && call.arguments.isEmpty()) call.arguments.append(args)
        }
    }

    private fun appendFunctionArguments(json: JSONObject) {
        val key = functionKey(json, json)
        synchronized(pendingCalls) {
            val call = pendingCalls.getOrPut(key) { PendingFunctionCall() }
            call.name = json.optString("name", call.name)
            call.callId = json.optString("call_id", call.callId)
            call.arguments.append(json.optString("delta", ""))
        }
    }

    private fun finishFunctionArguments(json: JSONObject) {
        val key = functionKey(json, json)
        val call = synchronized(pendingCalls) {
            val pending = pendingCalls.remove(key) ?: PendingFunctionCall()
            pending.name = json.optString("name", pending.name)
            pending.callId = json.optString("call_id", pending.callId)
            val args = json.optString("arguments", "")
            if (args.isNotBlank()) {
                pending.arguments.clear()
                pending.arguments.append(args)
            }
            pending
        }
        if (call.name.isNotBlank()) {
            Thread({ handleFunctionCall(call.name, call.callId, call.arguments.toString()) }, "xai-tool-call").start()
        }
    }

    private fun maybeHandleFunctionItem(item: JSONObject, envelope: JSONObject) {
        if (item.optString("type") != "function_call") return
        val key = functionKey(item, envelope)
        val call = synchronized(pendingCalls) { pendingCalls.remove(key) }
        val name = item.optString("name", call?.name ?: "")
        val callId = item.optString("call_id", item.optString("id", call?.callId ?: ""))
        val args = item.optString("arguments", call?.arguments?.toString() ?: "")
        if (name.isNotBlank()) {
            Thread({ handleFunctionCall(name, callId, args) }, "xai-tool-call").start()
        }
    }

    private fun parseFunctionCallsFromResponse(json: JSONObject) {
        val response = json.optJSONObject("response") ?: return
        val output = response.optJSONArray("output") ?: return
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            maybeHandleFunctionItem(item, json)
        }
    }

    private fun functionKey(item: JSONObject, envelope: JSONObject): String {
        return item.optString("item_id").ifBlank {
            item.optString("output_item_id").ifBlank {
                item.optString("call_id").ifBlank {
                    item.optString("id").ifBlank {
                        envelope.optString("item_id").ifBlank {
                            envelope.optString("output_item_id").ifBlank {
                                envelope.optString("call_id").ifBlank { "default" }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleFunctionCall(name: String, id: String, argsJson: String) {
        val callId = id.ifBlank { name }
        synchronized(pendingCalls) {
            if (!handledCallIds.add(callId)) return
        }
        val args = try {
            if (argsJson.isBlank()) JSONObject() else JSONObject(argsJson)
        } catch (_: Exception) {
            JSONObject().put("_raw", argsJson)
        }
        Log.i(TAG, "Tool call: $name (id=$id)")

        val result = try {
            onToolCall?.invoke(name, id, args) ?: JSONObject().put("error", "no handler")
        } catch (e: Exception) {
            Log.e(TAG, "Tool call error: ${e.message}")
            JSONObject().put("error", e.message)
        }

        ws?.send(JSONObject()
            .put("type", "conversation.item.create")
            .put("item", JSONObject()
                .put("type", "function_call_output")
                .put("call_id", callId)
                .put("output", result.toString()))
            .toString())
        ws?.send(JSONObject().put("type", "response.create").toString())
        Log.i(TAG, "Tool response sent: $name")
    }
}
