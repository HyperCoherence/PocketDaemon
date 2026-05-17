package com.pocketdaemon.pocket_daemon

import android.util.Base64
import android.util.Log
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiVoiceSessionClient(
    private val apiKey: String,
    private val model: String,
    private val systemPrompt: String,
    private val voice: String? = null,
    private val tools: List<ToolSpec> = emptyList(),
    private val googleSearch: Boolean = false,
    private val resumeHandle: String? = null,
    @Volatile override var onAgentAudio: (ByteArray) -> Unit,
    private val onTranscript: (speaker: String, text: String) -> Unit,
    private val onToolCall: ((name: String, id: String, args: JSONObject) -> JSONObject)? = null,
    private val onTurnComplete: (() -> Unit)? = null,
    private val onInterrupted: (() -> Unit)? = null,
    private val onReady: (() -> Unit)? = null,
    private val onSessionEnded: (reason: String?) -> Unit,
) : VoiceSessionClient {
    companion object {
        private const val TAG = "GeminiVoiceSessionClient"
        private const val BASE_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile override var connected = false
        private set
    @Volatile override var ready = false
        private set
    @Volatile override var sessionHandle: String? = null
        private set
    @Volatile private var closeReason: String? = null

    override fun connect() {
        val url = "$BASE_URL?key=$apiKey"
        Log.i(TAG, "Connecting to Gemini Live ($model)" +
                if (resumeHandle != null) " [resuming]" else " [new session]")

        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                Log.i(TAG, "WebSocket connected, sending setup")
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
        val msg = JSONObject().put("realtimeInput", JSONObject()
            .put("audio", JSONObject()
                .put("data", b64)
                .put("mimeType", "audio/pcm;rate=16000")
            )
        )
        ws?.send(msg.toString())
    }

    override fun sendText(text: String) {
        if (!ready) {
            Log.w(TAG, "sendText called before ready, queuing ignored")
            return
        }
        val msg = JSONObject().put("realtimeInput", JSONObject()
            .put("text", text)
        )
        ws?.send(msg.toString())
        Log.i(TAG, "Sent text: ${text.take(80)}")
    }

    override fun sendImage(imageBase64: String, mimeType: String, caption: String?) {
        if (!ready) {
            Log.w(TAG, "sendImage called before ready")
            return
        }
        val parts = JSONArray()
        parts.put(JSONObject().put("inlineData", JSONObject()
            .put("mimeType", mimeType)
            .put("data", imageBase64)
        ))
        if (!caption.isNullOrBlank()) {
            parts.put(JSONObject().put("text", caption))
        }
        val msg = JSONObject().put("clientContent", JSONObject()
            .put("turns", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", parts)
            ))
            .put("turnComplete", true)
        )
        ws?.send(msg.toString())
        Log.i(TAG, "Sent image (${mimeType}, caption=${caption?.take(40) ?: "none"})")
    }

    private fun sendSetup(webSocket: WebSocket) {
        val setupInner = JSONObject().apply {
            put("model", "models/$model")
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO"))
                if (!voice.isNullOrBlank()) {
                    put("speechConfig", JSONObject()
                        .put("voiceConfig", JSONObject()
                            .put("prebuiltVoiceConfig", JSONObject()
                                .put("voiceName", voice)
                            )
                        )
                    )
                }
            })
            if (systemPrompt.isNotBlank()) {
                put("systemInstruction", JSONObject()
                    .put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                )
            }
            val toolsArr = JSONArray()
            if (googleSearch) {
                toolsArr.put(JSONObject().put("google_search", JSONObject()))
            }
            if (tools.isNotEmpty()) {
                val funcDecls = JSONArray()
                for (tool in tools) {
                    val decl = JSONObject()
                        .put("name", tool.name)
                        .put("description", tool.description)
                    if (tool.parameters != null) {
                        decl.put("parameters", tool.parameters)
                    }
                    funcDecls.put(decl)
                }
                toolsArr.put(JSONObject().put("functionDeclarations", funcDecls))
            }
            if (toolsArr.length() > 0) {
                put("tools", toolsArr)
            }
            put("inputAudioTranscription", JSONObject())
            put("outputAudioTranscription", JSONObject())
            put("contextWindowCompression", JSONObject()
                .put("slidingWindow", JSONObject())
            )
            put("sessionResumption", JSONObject().apply {
                if (resumeHandle != null) put("handle", resumeHandle)
            })
        }
        val setup = JSONObject().put("setup", setupInner)
        Log.i(TAG, "Setup sent: models/$model, voice=$voice, tools=${tools.map { it.name }}, googleSearch=$googleSearch")
        webSocket.send(setup.toString())
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)

            if (json.has("setupComplete")) {
                ready = true
                Log.i(TAG, "Setup complete — ready")
                onReady?.invoke()
            }

            if (json.has("sessionResumptionUpdate")) {
                val update = json.getJSONObject("sessionResumptionUpdate")
                val resumable = update.optBoolean("resumable", false)
                val newHandle = update.optString("newHandle", "")
                if (resumable && newHandle.isNotEmpty()) {
                    sessionHandle = newHandle
                }
            }

            if (json.has("serverContent")) {
                val sc = json.getJSONObject("serverContent")

                if (sc.has("modelTurn")) {
                    val parts = sc.getJSONObject("modelTurn").optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            val inlineData = part.optJSONObject("inlineData")
                            if (inlineData != null) {
                                val b64 = inlineData.optString("data", "")
                                if (b64.isNotEmpty()) {
                                    val pcm = Base64.decode(b64, Base64.DEFAULT)
                                    onAgentAudio(pcm)
                                }
                            }
                        }
                    }
                }

                val inputT = sc.optJSONObject("inputTranscription")
                if (inputT != null) {
                    val t = inputT.optString("text", "")
                    if (t.isNotBlank()) {
                        Log.i(TAG, "Caller: $t")
                        onTranscript("user", t)
                    }
                }

                val outputT = sc.optJSONObject("outputTranscription")
                if (outputT != null) {
                    val t = outputT.optString("text", "")
                    if (t.isNotBlank()) {
                        Log.i(TAG, "Agent: $t")
                        onTranscript("agent", t)
                    }
                }

                val grounding = sc.optJSONObject("groundingMetadata")
                if (grounding != null) {
                    val queries = grounding.optJSONArray("webSearchQueries")
                    if (queries != null && queries.length() > 0) {
                        val qs = (0 until queries.length()).map { queries.getString(it) }
                        Log.i(TAG, "Grounding searches: $qs")
                    }
                }

                if (sc.optBoolean("interrupted", false)) {
                    Log.i(TAG, "Interrupted by user (barge-in)")
                    onInterrupted?.invoke()
                }

                if (sc.optBoolean("turnComplete", false)) {
                    Log.i(TAG, "Turn complete")
                    onTurnComplete?.invoke()
                }
            }

            if (json.has("toolCall")) {
                val toolCallObj = json.getJSONObject("toolCall")
                Thread({ handleToolCall(toolCallObj) }, "tool-call").start()
            }

            if (json.has("goAway")) {
                val timeLeft = json.getJSONObject("goAway").optString("timeLeft", "unknown")
                Log.w(TAG, "GoAway received, time left: $timeLeft")
            }

        } catch (e: Exception) {
            Log.w(TAG, "Parse error: ${e.message}")
        }
    }

    private fun handleToolCall(toolCallObj: JSONObject) {
        val calls = toolCallObj.optJSONArray("functionCalls") ?: return
        val responses = JSONArray()

        for (i in 0 until calls.length()) {
            val fc = calls.getJSONObject(i)
            val name = fc.optString("name", "")
            val id = fc.optString("id", "")
            val args = fc.optJSONObject("args") ?: JSONObject()

            Log.i(TAG, "Tool call: $name (id=$id)")

            val result = try {
                onToolCall?.invoke(name, id, args) ?: JSONObject().put("error", "no handler")
            } catch (e: Exception) {
                Log.e(TAG, "Tool call error: ${e.message}")
                JSONObject().put("error", e.message)
            }

            responses.put(JSONObject()
                .put("name", name)
                .put("id", id)
                .put("response", result)
            )
        }

        val resp = JSONObject().put("toolResponse", JSONObject()
            .put("functionResponses", responses)
        )
        ws?.send(resp.toString())
        Log.i(TAG, "Tool responses sent")
    }
}

typealias GeminiLiveClient = GeminiVoiceSessionClient
