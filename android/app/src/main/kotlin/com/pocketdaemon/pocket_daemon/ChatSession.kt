package com.pocketdaemon.pocket_daemon

import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Chat session supporting PTT and continuous conversation modes.
 * Both keep the WebSocket alive between turns. PTT has a 60s idle timeout
 * after each AI turn; conversation stays open until explicitly ended.
 */
class ChatSession(
    private val context: Context,
    val conversationMode: Boolean = false,
) {

    companion object {
        private const val TAG = "ChatSession"
        private const val PREFS_KEY_HANDLE = "chat_session_handle"
        private const val PREFS_KEY_SESSION_LOG = "chat_session_log"
        private const val IDLE_TIMEOUT_MS = 60_000L
        private const val CONVERSATION_IDLE_TIMEOUT_MS = 180_000L
        private const val APP_SWITCH_IDLE_TIMEOUT_MS = 45_000L
        private const val EXPERT_MODEL = "gemini-3.1-pro-preview"

        private fun stringParam(name: String, desc: String) = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put(name, JSONObject()
                    .put("type", "string")
                    .put("description", desc)
                )
            )
            .put("required", JSONArray().put(name))

        @Suppress("unused")
        fun oldTools(ownerName: String) = listOf(
            GeminiLiveClient.ToolDeclaration(
                name = "leave_message",
                description = "Leave a message for $ownerName to read. Appears in their notification inbox. Use for caller messages, call summaries, or anything $ownerName needs to see and act on.",
                parameters = stringParam("text", "The message content"),
            ),
            GeminiLiveClient.ToolDeclaration(
                name = "search_memory",
                description = "Search $ownerName's memory for relevant information. Use keywords or phrases.",
                parameters = stringParam("query", "Search keywords or phrase"),
            ),
            GeminiLiveClient.ToolDeclaration(
                name = "get_location",
                description = "Get $ownerName's current GPS location.",
            ),
            GeminiLiveClient.ToolDeclaration(
                name = "get_notes",
                description = "Retrieve all notes and messages saved by the phone agent and chat sessions.",
            ),
            GeminiLiveClient.ToolDeclaration(
                name = "open_maps",
                description = "Open Google Maps with a specific address or place. Set navigate=true when $ownerName wants turn-by-turn directions (example: 'take me to', 'navigate to', 'drive to'). Navigation mode replaces the current destination if already navigating — use it to correct a wrong address. Default mode (navigate=false) opens a map search.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("address", JSONObject().put("type", "string").put("description", "The address, place name, or search query"))
                        .put("navigate", JSONObject().put("type", "boolean").put("description", "true for turn-by-turn navigation, false for map search (default false)")))
                    .put("required", JSONArray().put("address")),
            ),
            GeminiLiveClient.ToolDeclaration(
                name = "play_youtube",
                description = "Play a YouTube video. Use google_search first to find a specific, age-appropriate video URL on the topic, then call this tool with that URL. YouTube time is limited per day; the system auto-closes YouTube when time runs out. If the limit is reached, tell the user their YouTube time for today is used up and they need approval for more. Only play educational or enriching content.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("url", JSONObject().put("type", "string").put("description", "Full YouTube video URL"))
                        .put("title", JSONObject().put("type", "string").put("description", "Video title for logging")))
                    .put("required", JSONArray().put("url")),
            ),
            GeminiLiveClient.ToolDeclaration(
                name = "ask_expert",
                description = "Consult a more powerful AI model for complex questions requiring deep reasoning, analysis, math, coding, or detailed knowledge. Use when the question is hard enough that you want a second, more thorough opinion before answering $ownerName.",
                parameters = stringParam("question", "The question or problem to send to the expert model"),
            ),
            GeminiLiveClient.ToolDeclaration(
                name = "add_contact",
                description = "Add a contact to $ownerName's phone. Use when $ownerName asks you to save someone's number. Phone numbers MUST be in full international format.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("name", JSONObject().put("type", "string").put("description", "Contact display name"))
                        .put("phone", JSONObject().put("type", "string").put("description", "Phone number")))
                    .put("required", JSONArray().put("name").put("phone")),
            ),
            GeminiLiveClient.ToolDeclaration(
                name = "dial_contact",
                description = "Search $ownerName's phonebook by name and call the matching contact. You will be connected to the call and can speak with them on $ownerName's behalf. Do NOT guess — if zero or multiple contacts match, report back to $ownerName instead of dialing. When the call ends you will automatically return to talking with $ownerName.",
                parameters = stringParam("name", "Contact name to search for"),
            ),
            GeminiLiveClient.ToolDeclaration(
                name = "dial_number",
                description = "Call the given phone number directly. You will be connected to the call and can speak with the person on $ownerName's behalf. Only use when $ownerName provides an explicit number or you retrieved it from a previous lookup. When the call ends you will automatically return to talking with $ownerName.",
                parameters = stringParam("number", "Phone number to dial"),
            ),
            GeminiLiveClient.ToolDeclaration(
                name = "hangUp",
                description = "End the current outbound phone call and return to talking with $ownerName. Only works during an active bridged call.",
            ),
            GeminiLiveClient.ToolDeclaration(
                name = "end_session",
                description = "End the current chat session and disconnect. Use when the conversation is complete, $ownerName says goodbye, or there is nothing more to discuss.",
            ),
            GeminiLiveClient.ToolDeclaration(
                name = "schedule_task",
                description = "Schedule a task for the future. The task prompt will be executed automatically at the scheduled time and the result delivered as a notification to $ownerName. Use for reminders, periodic check-ins, delayed actions, or anything $ownerName wants done later.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("description", JSONObject()
                            .put("type", "string")
                            .put("description", "Short human-readable task name shown in the task list"))
                        .put("prompt", JSONObject()
                            .put("type", "string")
                            .put("description", "The full instruction to execute when the task fires"))
                        .put("delayMinutes", JSONObject()
                            .put("type", "integer")
                            .put("description", "Minutes from now until first execution"))
                        .put("recurring", JSONObject()
                            .put("type", "boolean")
                            .put("description", "Whether this task should repeat on an interval"))
                        .put("intervalMinutes", JSONObject()
                            .put("type", "integer")
                            .put("description", "Minutes between recurrences (required if recurring is true)")))
                    .put("required", JSONArray().put("description").put("prompt").put("delayMinutes")),
            ),
            GeminiLiveClient.ToolDeclaration(
                name = "take_photo",
                description = "Take a photo using the phone's camera. Saves to storage. Supports an optional delay timer (e.g. 'take a photo in 5 seconds'). Tell $ownerName before capturing so they can prepare.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("camera", JSONObject()
                            .put("type", "string")
                            .put("enum", JSONArray().put("back").put("front"))
                            .put("description", "Which camera to use (default: back)"))
                        .put("delay_seconds", JSONObject()
                            .put("type", "integer")
                            .put("description", "Seconds to wait before taking the photo (0-30, default 0)"))),
            ),
            GeminiLiveClient.ToolDeclaration(
                name = "use_skill",
                description = "Load a skill's full instructions by name. Call this when one of the available skills is relevant to the current task.",
                parameters = stringParam("name", "The skill folder name from the available skills list"),
            ),
        )

        fun tools(ownerName: String) = AgentToolRegistry.liveDeclarations(ownerName, "chat")
    }

    private val app = PocketDaemonApp.instance!!
    private val noteManager = NoteManager(context)
    private val memoryManager = MemoryManager(context)
    private val handler = Handler(Looper.getMainLooper())
    private val toolExecutor by lazy {
        AgentToolExecutor(context, "chat", "voice") { appSwitchedShortTimeout = true }
    }

    private var gemini: GeminiLiveClient? = null
    private var audioHandler: ChatAudioHandler? = null
    private var callAudioHandler: CallAudioHandler? = null
    private var recorder: AudioRecorder? = null
    private var outboundHangUp: (() -> Unit)? = null
    private var sessionLog: SessionLogger? = null
    @Volatile var active = false
        private set
    @Volatile var waitingForResponse = false
        private set
    private var searchQuotaExhausted = false
    @Volatile private var appSwitchedShortTimeout = false
    @Volatile private var expertTurnLatch: CountDownLatch? = null

    private val idleRunnable = Runnable {
        Log.i(TAG, "Idle timeout — disconnecting")
        teardown()
        app.emitEvent("chatEnded", emptyMap<String, Any>())
    }

    fun startTalk() {
        handler.removeCallbacks(idleRunnable)
        appSwitchedShortTimeout = false
        startForegroundService()

        if (gemini?.ready == true) {
            Log.i(TAG, "Reusing live connection")
            active = true
            waitingForResponse = false
            val now = SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a z", Locale.ENGLISH).format(Date())
            gemini?.sendText("Current date and time: $now. ${app.ownerName} is back — greet them.")
            audioHandler?.startCapture()
            app.emitEvent("chatReady", emptyMap<String, Any>())
            return
        }

        if (active) {
            Log.w(TAG, "Already active (connecting)")
            return
        }
        active = true
        waitingForResponse = false

        val savedHandle = app.prefs.getString(PREFS_KEY_HANDLE, null)
        val savedLogFile = app.prefs.getString(PREFS_KEY_SESSION_LOG, null)
        Log.i(TAG, "startTalk, resumeHandle=${savedHandle?.take(12) ?: "null"}, logFile=$savedLogFile")

        if (savedHandle != null && savedLogFile != null) {
            sessionLog = SessionLogger.reopen(context, savedLogFile)
        }
        if (sessionLog == null) {
            val mode = if (conversationMode) "conversation" else "ptt"
            sessionLog = SessionLogger.create(context, "chat", mapOf("mode" to mode))
            app.prefs.edit().putString(PREFS_KEY_SESSION_LOG, sessionLog!!.filename).apply()
        }

        val memoryCtx = memoryManager.getSessionContext(excludeFile = sessionLog?.filename)
        val locationCtx = app.locationProvider.getLastLocationSummary()
        val now = SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a z", Locale.ENGLISH).format(Date())
        val skillsBlock = SkillManager(context).getSkillsPromptBlock()
        val fullPrompt = buildString {
            if (memoryCtx.isNotBlank()) {
                append(memoryCtx)
                append("\n---\n")
            }
            if (locationCtx != null) {
                append(locationCtx)
                append("\n---\n")
            }
            append("Current date and time: $now")
            append("\n---\n")
            if (skillsBlock.isNotBlank()) {
                append(skillsBlock)
                append("\n---\n")
            }
            append("This is a direct conversation, not a phone call.")
        }

        connectGemini(fullPrompt, savedHandle)
    }

    private fun connectGemini(prompt: String, resumeHandle: String?) {
        val enabledTools = tools(app.ownerName).filter { app.isToolEnabled("chat", it.name) }
        val wantSearch = app.isToolEnabled("chat", "google_search") && !searchQuotaExhausted
        Log.i(TAG, "Connecting: googleSearch=$wantSearch, tools=${enabledTools.map { it.name }}")

        if (recorder == null && app.recordAgentConversationsEnabled) {
            recorder = AudioRecorder("chat").also { it.start() }
        }

        gemini = GeminiLiveClient(
            apiKey = app.apiKey,
            model = app.model,
            systemPrompt = prompt,
            voice = app.voice,
            tools = enabledTools,
            googleSearch = wantSearch,
            resumeHandle = resumeHandle,
            onAgentAudio = { pcm ->
                audioHandler?.enqueuePlayback(pcm)
                recorder?.writePlaybackAudio(pcm, ChatAudioHandler.PLAYBACK_RATE)
            },
            onTranscript = { speaker, text ->
                sessionLog?.log(speaker, text)
                app.emitEvent("chatTranscript", mapOf("speaker" to speaker, "text" to text))
            },
            onToolCall = { name, _, args -> handleToolCall(name, args) },
            onReady = {
                Log.i(TAG, "Gemini ready for chat (googleSearch=$wantSearch)")
                if (resumeHandle != null) {
                    val now = SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a z", Locale.ENGLISH).format(Date())
                    gemini?.sendText("Current date and time: $now. ${app.ownerName} is back — greet them.")
                }
                app.emitEvent("chatReady", emptyMap<String, Any>())
            },
            onInterrupted = {
                if (app.bargeInEnabled) {
                    audioHandler?.flushPlayback()
                    callAudioHandler?.flushPlayback()
                }
                recorder?.finishPlaybackTurn()
            },
            onTurnComplete = {
                Log.i(TAG, "Chat turn complete (conversation=$conversationMode)")
                recorder?.finishPlaybackTurn()
                persistHandle()
                expertTurnLatch?.countDown()
                if (!conversationMode) {
                    Thread({
                        audioHandler?.awaitPlaybackDrain()
                        handler.post {
                            waitingForResponse = false
                            active = false
                            audioHandler?.stopCapture()
                            app.emitEvent("chatTurnComplete", mapOf("conversationMode" to conversationMode))
                            handler.postDelayed(idleRunnable, IDLE_TIMEOUT_MS)
                            Log.i(TAG, "Idle timer started (${IDLE_TIMEOUT_MS / 1000}s)")
                        }
                    }, "ptt-drain").start()
                } else {
                    val timeout = if (appSwitchedShortTimeout) APP_SWITCH_IDLE_TIMEOUT_MS else CONVERSATION_IDLE_TIMEOUT_MS
                    appSwitchedShortTimeout = false
                    handler.removeCallbacks(idleRunnable)
                    handler.postDelayed(idleRunnable, timeout)
                    Log.i(TAG, "Conversation idle timer started (${timeout / 1000}s)")
                    app.emitEvent("chatTurnComplete", mapOf("conversationMode" to conversationMode))
                }
            },
            onSessionEnded = { reason ->
                Log.i(TAG, "Chat session ended by server: ${reason ?: "clean"}")

                if (reason != null && reason.contains("quota", ignoreCase = true) && wantSearch) {
                    Log.w(TAG, "Google Search quota hit — retrying without search")
                    searchQuotaExhausted = true
                    gemini = null
                    handler.post { connectGemini(prompt, null) }
                    return@GeminiLiveClient
                }

                if (reason != null && reason.contains("expired", ignoreCase = true) && resumeHandle != null) {
                    Log.w(TAG, "Session expired — clearing handle and retrying fresh")
                    app.prefs.edit().remove(PREFS_KEY_HANDLE).remove(PREFS_KEY_SESSION_LOG).apply()
                    gemini = null
                    handler.post { connectGemini(prompt, null) }
                    return@GeminiLiveClient
                }

                handler.removeCallbacks(idleRunnable)
                active = false
                audioHandler?.release()
                audioHandler = null
                gemini = null
                val data = mutableMapOf<String, Any?>()
                if (reason != null) data["error"] = reason
                app.emitEvent("chatEnded", data)
            }
        )

        if (audioHandler == null) {
            audioHandler = ChatAudioHandler(context) { capturedPcm ->
                gemini?.sendAudio(capturedPcm)
                recorder?.writeCaptureAudio(capturedPcm)
            }
        }

        gemini?.connect()
        audioHandler?.startCapture()
    }

    fun stopTalk() {
        if (conversationMode) return
        waitingForResponse = true
        audioHandler?.stopCapture()
        Log.i(TAG, "Mic stopped, waiting for Gemini response")
        app.emitEvent("chatWaiting", emptyMap<String, Any>())
    }

    fun endConversation() {
        Log.i(TAG, "Ending conversation")
        handler.removeCallbacks(idleRunnable)
        persistHandle()
        teardown()
        app.emitEvent("chatEnded", emptyMap<String, Any>())
    }

    fun cancel() {
        handler.removeCallbacks(idleRunnable)
        teardown()
    }

    fun sendImage(imageBase64: String, mimeType: String, caption: String?) {
        gemini?.sendImage(imageBase64, mimeType, caption)
    }

    private fun teardown() {
        active = false
        waitingForResponse = false
        stopForegroundService()
        sessionLog?.close()
        val transcript = sessionLog?.getTranscript() ?: ""
        val logName = sessionLog?.filename ?: ""
        sessionLog = null
        audioHandler?.release()
        audioHandler = null
        callAudioHandler?.stop()
        callAudioHandler?.awaitTermination()
        callAudioHandler = null
        recorder?.stop()
        recorder = null
        outboundHangUp = null
        app.outboundBridge = null
        gemini?.disconnect()
        gemini = null
        app.prefs.edit().remove(PREFS_KEY_HANDLE).remove(PREFS_KEY_SESSION_LOG).apply()

        if (transcript.isNotBlank() && logName.isNotBlank()
            && app.memoryExtractionEnabled) {
            Thread {
                try {
                    val extractor = MemoryExtractor(context)
                    val newFacts = extractor.extract(transcript, logName, app.apiKey)
                    extractor.refreshIndex()
                    if (newFacts > 0) extractor.compactMemory(app.apiKey)
                } catch (e: Exception) {
                    Log.w(TAG, "Memory extraction failed: ${e.message}")
                }
            }.start()
        }
    }

    private fun startForegroundService() {
        try {
            context.startForegroundService(Intent(context, ChatForegroundService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "Could not start chat foreground service: ${e.message}")
        }
    }

    private fun stopForegroundService() {
        try {
            context.stopService(Intent(context, ChatForegroundService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop chat foreground service: ${e.message}")
        }
    }

    private fun initiateOutboundCall(number: String) {
        audioHandler?.release()
        audioHandler = null
        app.outboundBridge = this
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Log.i(TAG, "Outbound call initiated: $number")
        app.emitEvent("outboundCallDialing", mapOf("number" to number))
    }

    fun onOutboundCallActive(hangUp: () -> Unit) {
        outboundHangUp = hangUp
        callAudioHandler = CallAudioHandler(context, speakerMonitor = app.speakerMonitorEnabled) { capturedPcm ->
            gemini?.sendAudio(capturedPcm)
            recorder?.writeCaptureAudio(capturedPcm)
        }
        gemini?.onAgentAudio = { pcm ->
            callAudioHandler?.enqueuePlayback(pcm)
            recorder?.writePlaybackAudio(pcm, CallAudioHandler.PLAYBACK_RATE)
        }
        callAudioHandler?.start()
        Log.i(TAG, "Outbound call active — audio bridged to Gemini")
        app.emitEvent("outboundCallActive", emptyMap<String, Any>())
    }

    fun onOutboundCallEnded() {
        Log.i(TAG, "Outbound call ended — restoring chat audio")
        callAudioHandler?.stop()
        callAudioHandler?.awaitTermination()
        callAudioHandler = null
        outboundHangUp = null
        app.outboundBridge = null

        audioHandler = ChatAudioHandler(context) { capturedPcm ->
            gemini?.sendAudio(capturedPcm)
            recorder?.writeCaptureAudio(capturedPcm)
        }
        gemini?.onAgentAudio = { pcm ->
            audioHandler?.enqueuePlayback(pcm)
            recorder?.writePlaybackAudio(pcm, ChatAudioHandler.PLAYBACK_RATE)
        }
        audioHandler?.startCapture()

        gemini?.sendText("The outbound call has ended. You are now talking to ${app.ownerName} again. Tell them what happened on the call.")
        app.emitEvent("outboundCallEnded", emptyMap<String, Any>())
    }

    private fun persistHandle() {
        val handle = gemini?.sessionHandle
        if (handle != null) {
            app.prefs.edit().putString(PREFS_KEY_HANDLE, handle).apply()
            Log.i(TAG, "Session handle persisted: ${handle.take(12)}...")
        }
    }

    private fun handleToolCall(name: String, args: JSONObject): JSONObject {
        sessionLog?.logTool(name, args)
        if (name !in setOf("dial_contact", "dial_number", "ask_expert", "hangUp", "end_session")) {
            toolExecutor.handle(name, args)?.let { return it }
        }
        return when (name) {
            "leave_message" -> {
                val text = args.optString("text", "")
                if (text.isNotBlank()) {
                    noteManager.save("chat", text)
                    JSONObject().put("status", "saved")
                } else {
                    JSONObject().put("error", "empty text")
                }
            }
            "search_memory" -> {
                val query = args.optString("query", "")
                Log.i(TAG, "Tool: search_memory '$query'")
                JSONObject().put("results", memoryManager.searchMemory(query))
            }
            "get_location" -> {
                Log.i(TAG, "Tool: get_location")
                app.locationProvider.getLocation()
            }
            "get_notes" -> {
                Log.i(TAG, "Tool: get_notes")
                val notes = noteManager.getAll()
                val arr = JSONArray()
                for (n in notes) arr.put(JSONObject(n))
                JSONObject().put("notes", arr)
            }
            "open_maps" -> {
                val address = args.optString("address", "")
                val navigate = args.optBoolean("navigate", false)
                Log.i(TAG, "Tool: open_maps '$address' navigate=$navigate")
                if (address.isBlank()) {
                    JSONObject().put("error", "address is required")
                } else {
                    val uri = if (navigate) {
                        Uri.parse("google.navigation:q=${Uri.encode(address)}")
                    } else {
                        Uri.parse("geo:0,0?q=${Uri.encode(address)}")
                    }
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        setPackage("com.google.android.apps.maps")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(intent)
                        appSwitchedShortTimeout = true
                        JSONObject().put("status", if (navigate) "navigating" else "opened").put("address", address)
                    } catch (e: Exception) {
                        Log.w(TAG, "Google Maps not available, falling back to browser")
                        val webUri = Uri.parse("https://www.google.com/maps/search/${Uri.encode(address)}")
                        val fallback = Intent(Intent.ACTION_VIEW, webUri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            context.startActivity(fallback)
                            appSwitchedShortTimeout = true
                            JSONObject().put("status", "opened_browser").put("address", address)
                        } catch (e2: Exception) {
                            JSONObject().put("error", "could not open maps: ${e2.message}")
                        }
                    }
                }
            }
            "play_youtube" -> {
                val url = args.optString("url", "")
                val title = args.optString("title", "")
                Log.i(TAG, "Tool: play_youtube '$title' '$url'")
                if (url.isBlank()) {
                    JSONObject().put("error", "url is required")
                } else {
                    val budget = app.configGet("youtubeMinutes", 15L).toInt()
                    val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                    val (fileDate, fileRemaining) = app.readYoutubeRemaining()
                    val remaining = when {
                        budget <= 0 -> -1
                        fileDate != today -> { app.writeYoutubeRemaining(today, budget); budget }
                        else -> fileRemaining
                    }

                    if (budget > 0 && remaining <= 0) {
                        JSONObject().put("error", "YouTube time for today is used up. Ask for more time.")
                    } else {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            setPackage("com.google.android.youtube")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            context.startActivity(intent)
                            if (budget > 0) app.launchYoutubeTimer()
                            appSwitchedShortTimeout = true
                            val timeInfo = if (remaining > 0) "$remaining minutes remaining" else "unlimited"
                            JSONObject().put("status", "playing").put("title", title).put("timeLimit", timeInfo)
                        } catch (e: Exception) {
                            Log.w(TAG, "YouTube not available: ${e.message}")
                            JSONObject().put("error", "YouTube not available: ${e.message}")
                        }
                    }
                }
            }
            "add_contact" -> {
                val cName = args.optString("name", "")
                val cPhone = args.optString("phone", "")
                Log.i(TAG, "Tool: add_contact '$cName' '$cPhone'")
                if (cName.isBlank() || cPhone.isBlank()) {
                    JSONObject().put("error", "name and phone required")
                } else {
                    insertContact(cName, cPhone)
                }
            }
            "dial_contact" -> {
                val contactName = args.optString("name", "")
                Log.i(TAG, "Tool: dial_contact '$contactName'")
                if (contactName.isBlank()) {
                    JSONObject().put("error", "name is required")
                } else {
                    val matches = searchContacts(contactName)
                    when {
                        matches.isEmpty() -> JSONObject().put("error", "no contact found for '$contactName'")
                        matches.size > 1 -> {
                            val arr = JSONArray()
                            for ((n, p) in matches) arr.put(JSONObject().put("name", n).put("phone", p))
                            JSONObject().put("error", "multiple matches").put("matches", arr)
                        }
                        else -> {
                            val (n, p) = matches[0]
                            initiateOutboundCall(p)
                            JSONObject().put("status", "dialing").put("name", n).put("number", p)
                        }
                    }
                }
            }
            "dial_number" -> {
                val number = args.optString("number", "")
                Log.i(TAG, "Tool: dial_number '$number'")
                if (number.isBlank()) {
                    JSONObject().put("error", "number is required")
                } else {
                    initiateOutboundCall(number)
                    JSONObject().put("status", "dialing").put("number", number)
                }
            }
            "ask_expert" -> {
                val question = args.optString("question", "")
                Log.i(TAG, "Tool: ask_expert '${question.take(80)}'")
                if (question.isBlank()) {
                    JSONObject().put("error", "question is required")
                } else {
                    val latch = CountDownLatch(1)
                    expertTurnLatch = latch
                    Thread({
                        val result = queryExpert(question)
                        val answer = result.optString("answer", result.optString("error", "no response"))
                        latch.await(45, TimeUnit.SECONDS)
                        audioHandler?.awaitPlaybackDrain()
                        expertTurnLatch = null
                        if (gemini?.connected == true) {
                            gemini?.sendText("Expert advisor response:\n$answer")
                            Log.i(TAG, "Expert answer delivered (${answer.length} chars)")
                        } else {
                            Log.w(TAG, "Gemini disconnected before expert answer could be delivered")
                        }
                    }, "expert-query").start()
                    JSONObject().put("status", "consulting_expert")
                }
            }
            "schedule_task" -> {
                val desc = args.optString("description", "")
                val prompt = args.optString("prompt", "")
                val delayMin = args.optLong("delayMinutes", 0)
                Log.i(TAG, "Tool: schedule_task '$desc' delay=${delayMin}m")
                if (desc.isBlank() || prompt.isBlank() || delayMin <= 0) {
                    JSONObject().put("error", "description, prompt, and delayMinutes (>0) required")
                } else {
                    val mgr = ScheduledTaskManager(context)
                    val task = ScheduledTask(
                        description = desc,
                        prompt = prompt,
                        recurring = args.optBoolean("recurring", false),
                        intervalMinutes = if (args.has("intervalMinutes")) args.optInt("intervalMinutes") else null,
                        nextFireMs = System.currentTimeMillis() + delayMin * 60_000,
                        createdBy = "voice",
                    )
                    mgr.schedule(task)
                    JSONObject().put("status", "scheduled").put("id", task.id)
                        .put("nextFireMs", task.nextFireMs)
                }
            }
            "hangUp" -> {
                val hangUpFn = outboundHangUp
                if (hangUpFn != null) {
                    Log.i(TAG, "Tool: hangUp (outbound call)")
                    hangUpFn()
                    JSONObject().put("status", "hanging up outbound call")
                } else {
                    JSONObject().put("status", "no-op (chat mode)")
                }
            }
            "take_photo" -> {
                val camera = args.optString("camera", "back")
                val delay = args.optInt("delay_seconds", 0)
                Log.i(TAG, "Tool: take_photo camera=$camera delay=${delay}s")
                CameraCapture(context).takePhoto(
                    useBackCamera = camera != "front",
                    delaySeconds = delay,
                )
            }
            "end_session" -> {
                Log.i(TAG, "Tool: end_session")
                handler.postDelayed({ endConversation() }, 500)
                JSONObject().put("status", "ending")
            }
            "use_skill" -> {
                val skillName = args.optString("name", "")
                Log.i(TAG, "Tool: use_skill '$skillName'")
                val content = SkillManager(context).getSkillContent(skillName)
                if (content != null) {
                    JSONObject().put("content", content)
                } else {
                    JSONObject().put("error", "skill not found: $skillName")
                }
            }
            else -> JSONObject().put("error", "unknown tool: $name")
        }
    }

    private fun searchContacts(query: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selArgs = arrayOf("%$query%")
        try {
            context.contentResolver.query(uri, projection, selection, selArgs, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext() && results.size < 10) {
                    val n = cursor.getString(nameIdx) ?: continue
                    val p = cursor.getString(numIdx)?.replace("\\s".toRegex(), "") ?: continue
                    val key = n.lowercase()
                    if (seen.add(key)) {
                        results.add(n to p)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Contact search failed: ${e.message}")
        }
        Log.i(TAG, "searchContacts('$query') → ${results.size} results")
        return results
    }

    private fun insertContact(name: String, phone: String): JSONObject {
        return try {
            val ops = ArrayList<ContentProviderOperation>()
            ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build())
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build())
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build())
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            Log.i(TAG, "Contact added: $name ($phone)")
            JSONObject().put("status", "saved").put("name", name).put("phone", phone)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add contact: ${e.message}")
            JSONObject().put("error", "failed to add contact: ${e.message}")
        }
    }

    private fun queryExpert(question: String): JSONObject {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$EXPERT_MODEL:generateContent?key=${app.apiKey}"
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text", question)))
            ))
            .put("systemInstruction", JSONObject()
                .put("parts", JSONArray().put(JSONObject()
                    .put("text", "You are an expert advisor. Give a direct, thorough answer. No preamble.")
                ))
            )
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            app.httpClient.newCall(request).execute().use { response ->
                val raw = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.e(TAG, "Expert query failed: ${response.code} $raw")
                    return JSONObject().put("error", "expert returned ${response.code}")
                }
                val json = JSONObject(raw)
                val text = json.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text", "") ?: ""
                Log.i(TAG, "Expert answered (${text.length} chars)")
                JSONObject().put("answer", text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Expert query error: ${e.message}")
            JSONObject().put("error", "expert query failed: ${e.message}")
        }
    }
}
