package com.pocketdaemon.pocket_daemon

import android.util.Base64
import android.util.Log
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class GeminiVoiceSessionClient(
    private val apiKey: String,
    private val model: String,
    private val systemPrompt: String,
    private val voice: String? = null,
    private val tools: List<ToolSpec> = emptyList(),
    private val googleSearch: Boolean = false,
    private val resumeHandle: String? = null,
    private val thinkingLevel: String? = null,
    @Volatile override var onAgentAudio: (ByteArray) -> Unit,
    private val onTranscript: (speaker: String, text: String) -> Unit,
    private val onToolCall: ((name: String, id: String, args: JSONObject) -> JSONObject)? = null,
    private val onTurnComplete: (() -> Unit)? = null,
    private val onInterrupted: (() -> Unit)? = null,
    private val onInteractionStatus: ((status: String) -> Unit)? = null,
    private val onReady: (() -> Unit)? = null,
    private val onSessionEnded: (reason: String?) -> Unit,
) : VoiceSessionClient {
    companion object {
        private const val TAG = "GeminiVoiceSessionClient"
        private const val BASE_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        /** How long to wait after turnComplete for an interaction status before treating the turn as done. */
        private const val TURN_COMPLETE_FALLBACK_MS = 2_500L
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

    private val toolsByName = tools.associateBy { it.name }
    private val cancelledToolCalls: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    // Async-reasoning models (gemini-3.8-live) report interactionStatus. Once seen, REQUIRES_ACTION marks the
    // end of the agent's turn instead of turnComplete, which can precede more audio or tool follow-ups.
    @Volatile private var statusDriven = false
    @Volatile private var turnCompleteFired = false
    /** Grounding metadata repeats across a turn's messages; only a new search is reported to the UI. */
    private var lastGroundingQueries: List<String> = emptyList()
    @Volatile private var turnFallback: ScheduledFuture<*>? = null
    private val turnScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "gemini-live-turn").apply { isDaemon = true }
    }

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
                stopTurnScheduler()
                onSessionEnded(t.message)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code")
                connected = false
                ready = false
                stopTurnScheduler()
                onSessionEnded(closeReason)
            }
        })
    }

    override fun disconnect() {
        connected = false
        ready = false
        stopTurnScheduler()
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
        // A photo rides the realtime stream as a single video frame, the shape the Live API camera-sharing
        // examples use, so it lands in order with the microphone audio instead of opening a separate turn.
        val frame = JSONObject().put("realtimeInput", JSONObject()
            .put("video", JSONObject()
                .put("mimeType", mimeType)
                .put("data", imageBase64)
            )
        )
        ws?.send(frame.toString())
        Log.i(TAG, "Sent image frame ($mimeType, ${imageBase64.length / 1024} KB base64)")
        // The follow-up realtime text is what makes the model react to the frame right away.
        if (!caption.isNullOrBlank()) sendText(caption)
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
                // thinkingConfig is omitted unless a level was explicitly enabled in settings.
                // gemini-3.8-live reasons on its own and rejects thinkingLevel, so it is skipped there.
                val level = thinkingLevel?.takeIf { it.isNotBlank() }
                if (level != null) {
                    if (GeminiModels.supportsThinkingLevel(model)) {
                        put("thinkingConfig", JSONObject().put("thinkingLevel", level))
                    } else {
                        Log.w(TAG, "thinkingLevel=$level ignored: not supported on $model")
                    }
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
                    // NON_BLOCKING lets the model keep talking while the tool runs.
                    if (tool.behavior != null) {
                        decl.put("behavior", tool.behavior)
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
        val asyncTools = tools.filter { it.nonBlocking }.map { it.name }
        Log.i(TAG, "Setup sent: models/$model, voice=$voice, thinkingLevel=${thinkingLevel ?: "off"}, " +
                "tools=${tools.map { it.name }}, nonBlocking=$asyncTools, googleSearch=$googleSearch")
        webSocket.send(setup.toString())
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)

            // Parse the status before turnComplete so a message carrying both is handled as one unit.
            val status = parseInteractionStatus(
                json.optJSONObject("serverContent")?.opt("interactionStatus") ?: json.opt("interactionStatus")
            )
            if (status != null) {
                statusDriven = true
                cancelTurnCompleteFallback()
            }

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
                    noteModelOutput()
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
                        if (qs != lastGroundingQueries) {
                            lastGroundingQueries = qs
                            PocketDaemonApp.instance?.emitEvent("agentTool", mapOf("name" to AgentToolRegistry.GOOGLE_SEARCH))
                        }
                    }
                }

                if (sc.optBoolean("interrupted", false)) {
                    Log.i(TAG, "Interrupted by user (barge-in)")
                    onInterrupted?.invoke()
                }

                if (sc.optBoolean("turnComplete", false)) {
                    if (statusDriven) {
                        Log.i(TAG, "Turn complete signalled; waiting for REQUIRES_ACTION")
                        armTurnCompleteFallback()
                    } else {
                        Log.i(TAG, "Turn complete")
                        fireTurnComplete(force = true)
                    }
                }
            }

            if (status != null) handleInteractionStatus(status)

            if (json.has("toolCall")) {
                noteModelOutput()
                val toolCallObj = json.getJSONObject("toolCall")
                Thread({ handleToolCall(toolCallObj) }, "tool-call").start()
            }

            if (json.has("toolCallCancellation")) {
                val ids = json.getJSONObject("toolCallCancellation").optJSONArray("ids")
                if (ids != null && ids.length() > 0) {
                    val cancelled = (0 until ids.length()).map { ids.getString(it) }
                    cancelledToolCalls.addAll(cancelled)
                    Log.i(TAG, "Tool calls cancelled by server: $cancelled")
                }
            }

            if (json.has("goAway")) {
                val timeLeft = json.getJSONObject("goAway").optString("timeLeft", "unknown")
                Log.w(TAG, "GoAway received, time left: $timeLeft")
            }

        } catch (e: Exception) {
            Log.w(TAG, "Parse error: ${e.message}")
        }
    }

    private fun parseInteractionStatus(raw: Any?): String? {
        val value = when (raw) {
            null, JSONObject.NULL -> return null
            is String -> raw
            is JSONObject -> raw.optString("status", raw.optString("state", ""))
            else -> raw.toString()
        }
        return value.trim().uppercase().ifBlank { null }
    }

    private fun handleInteractionStatus(status: String) {
        Log.i(TAG, "Interaction status: $status")
        // IN_PROGRESS means the server started (or is still) working on input: a new turn is under way.
        if (status == InteractionStatus.IN_PROGRESS) turnCompleteFired = false
        onInteractionStatus?.invoke(status)
        if (status == InteractionStatus.REQUIRES_ACTION) fireTurnComplete()
    }

    /** New model output (audio, tool calls) after a completion means the agent's turn is not over. */
    private fun noteModelOutput() {
        turnCompleteFired = false
        cancelTurnCompleteFallback()
    }

    private fun fireTurnComplete(force: Boolean = false) {
        if (turnCompleteFired && !force) {
            Log.i(TAG, "Turn already marked complete")
            return
        }
        turnCompleteFired = true
        onTurnComplete?.invoke()
    }

    private fun armTurnCompleteFallback() {
        cancelTurnCompleteFallback()
        turnFallback = try {
            turnScheduler.schedule({
                Log.w(TAG, "No interaction status after turnComplete; treating turn as complete")
                fireTurnComplete()
            }, TURN_COMPLETE_FALLBACK_MS, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            null
        }
    }

    private fun cancelTurnCompleteFallback() {
        turnFallback?.cancel(false)
        turnFallback = null
    }

    private fun stopTurnScheduler() {
        cancelTurnCompleteFallback()
        turnScheduler.shutdownNow()
    }

    private fun handleToolCall(toolCallObj: JSONObject) {
        val calls = toolCallObj.optJSONArray("functionCalls") ?: return
        val blockingResponses = JSONArray()

        for (i in 0 until calls.length()) {
            val fc = calls.getJSONObject(i)
            val name = fc.optString("name", "")
            val id = fc.optString("id", "")
            val args = fc.optJSONObject("args") ?: JSONObject()
            val spec = toolsByName[name]

            if (spec?.nonBlocking == true) {
                // NON_BLOCKING: the model keeps talking while this runs. The result goes back on its
                // own as soon as it lands, tagged with a scheduling hint (INTERRUPT, WHEN_IDLE, SILENT).
                val scheduling = spec.scheduling ?: ToolScheduling.INTERRUPT
                Log.i(TAG, "Tool call: $name (id=$id, non-blocking, scheduling=$scheduling)")
                Thread({
                    val result = runTool(name, id, args)
                    if (id.isNotEmpty() && cancelledToolCalls.remove(id)) {
                        Log.i(TAG, "Dropping result of cancelled tool call $name (id=$id)")
                    } else {
                        result.put("scheduling", scheduling)
                        sendToolResponses(JSONArray().put(functionResponse(name, id, result)))
                    }
                }, "tool-call-async-$name").start()
            } else {
                Log.i(TAG, "Tool call: $name (id=$id)")
                blockingResponses.put(functionResponse(name, id, runTool(name, id, args)))
            }
        }

        if (blockingResponses.length() > 0) sendToolResponses(blockingResponses)
    }

    private fun runTool(name: String, id: String, args: JSONObject): JSONObject {
        return try {
            onToolCall?.invoke(name, id, args) ?: JSONObject().put("error", "no handler")
        } catch (e: Exception) {
            Log.e(TAG, "Tool call error: ${e.message}")
            JSONObject().put("error", e.message)
        }
    }

    private fun functionResponse(name: String, id: String, result: JSONObject): JSONObject =
        JSONObject()
            .put("name", name)
            .put("id", id)
            .put("response", result)

    private fun sendToolResponses(responses: JSONArray) {
        val resp = JSONObject().put("toolResponse", JSONObject()
            .put("functionResponses", responses)
        )
        val sent = ws?.send(resp.toString()) ?: false
        if (sent) {
            Log.i(TAG, "Tool responses sent (${responses.length()})")
        } else {
            Log.w(TAG, "Tool responses dropped, socket closed (${responses.length()})")
        }
    }
}

typealias GeminiLiveClient = GeminiVoiceSessionClient
