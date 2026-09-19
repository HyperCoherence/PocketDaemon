package com.pocketdaemon.pocket_daemon

import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Text-based chat session over the chat role's reasoning model (Gemini, xAI, or Claude).
 * Manages multi-turn conversation history, function calling, and logs
 * to the same session_logs directory as voice sessions for transcript continuity.
 */
class TextChatSession(private val context: Context) {

    companion object {
        private const val TAG = "TextChatSession"
        private const val MAX_TOOL_ROUNDS = 10
    }

    private val app = PocketDaemonApp.instance!!
    private val noteManager = NoteManager(context)
    private val memoryManager = MemoryManager(context)
    private val toolExecutor = AgentToolExecutor(context, "textchat", "text", recentTranscript = { sessionLog?.getTranscript() })

    private val conversationHistory = mutableListOf<ReasoningMessage>()
    private var systemInstruction: String = ""
    private var sessionLog: SessionLogger? = null
    private var reasoning: ReasoningClient? = null
    private var reasoningError: String? = null

    @Volatile var active = false
        private set
    @Volatile var busy = false
        private set
    @Volatile private var endRequested = false

    fun start() {
        if (active) return
        active = true

        sessionLog = SessionLogger.create(context, "textchat")
        reasoning = try {
            ReasoningClients.forRole(app, AgentRoles.CHAT).also { reasoningError = null }
        } catch (e: ReasoningException) {
            Log.w(TAG, "No reasoning provider for text chat: ${e.message}")
            reasoningError = e.message
            null
        }

        val memoryCtx = memoryManager.getSessionContext(excludeFile = sessionLog?.filename)
        val locationCtx = app.locationProvider.getLastLocationSummary()
        val now = SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a z", Locale.ENGLISH).format(Date())
        val skillsBlock = SkillManager(context).getSkillsPromptBlock()

        systemInstruction = buildString {
            if (memoryCtx.isNotBlank()) {
                append(memoryCtx)
                append("\n---\n")
            }
            append(app.systemPrompt)
            append("\n---\n")
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
            append("This is a text chat conversation with ${app.ownerName}. Respond in text — be concise but thorough. You may use markdown formatting.")
        }

        Log.i(TAG, "Text chat session started (log=${sessionLog?.filename})")
        app.emitEvent("textChatReady", emptyMap())
    }

    fun sendMessage(text: String, imageBase64: String? = null, imageMimeType: String? = null) {
        if (!active || busy) return
        busy = true

        sessionLog?.log("user", if (imageBase64 != null) "[image] $text" else text)
        val parts = mutableListOf<ReasoningPart>()
        if (imageBase64 != null) {
            parts.add(ReasoningPart.Image(imageBase64, imageMimeType ?: "image/jpeg"))
        }
        if (text.isNotBlank()) {
            parts.add(ReasoningPart.Text(text))
        }
        conversationHistory.add(ReasoningMessage(ReasoningMessage.USER, parts))

        Thread({
            try {
                val response = runGenerateLoop()
                if (response != null) {
                    sessionLog?.log("agent", response)
                    app.emitEvent("textChatResponse", mapOf("text" to response))
                } else {
                    app.emitEvent("textChatResponse", mapOf("text" to "Sorry, I couldn't generate a response.", "error" to true))
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendMessage error: ${e.message}", e)
                app.emitEvent("textChatResponse", mapOf("text" to "Error: ${e.message}", "error" to true))
            } finally {
                busy = false
                if (endRequested) {
                    endRequested = false
                    end()
                }
            }
        }, "textchat-send").start()
    }

    fun end() {
        if (!active) return
        active = false
        busy = false

        sessionLog?.close()
        val transcript = sessionLog?.getTranscript() ?: ""
        val logName = sessionLog?.filename ?: ""
        sessionLog = null
        conversationHistory.clear()

        if (transcript.isNotBlank() && logName.isNotBlank() && app.memoryExtractionEnabled) {
            Thread {
                try {
                    val extractor = MemoryExtractor(context)
                    val newFacts = extractor.extract(transcript, logName)
                    extractor.refreshIndex()
                    if (newFacts > 0) extractor.compactMemory()
                } catch (e: Exception) {
                    Log.w(TAG, "Memory extraction failed: ${e.message}")
                }
            }.start()
        }

        Log.i(TAG, "Text chat session ended")
        app.emitEvent("textChatEnded", emptyMap())
    }

    // -----------------------------------------------------------------------
    // Model loop with function calling (provider chosen by the chat role config)
    // -----------------------------------------------------------------------

    private fun runGenerateLoop(): String? {
        val client = reasoning ?: throw ReasoningException(reasoningError ?: "No reasoning provider configured")
        val isEnabled = { name: String -> app.isToolEnabled("chat", name) }
        return ReasoningLoop.run(
            client = client,
            system = systemInstruction,
            history = conversationHistory,
            tools = AgentToolRegistry.restToolSpecs(app.ownerName, "chat", isEnabled),
            webSearch = AgentToolRegistry.webSearchEnabled("chat", isEnabled),
            maxRounds = MAX_TOOL_ROUNDS,
            onToolStart = { name -> app.emitEvent("textChatThinking", mapOf("tool" to name)) },
        ) { name, args ->
            sessionLog?.logTool(name, args)
            handleToolCall(name, args)
        }
    }

    // -----------------------------------------------------------------------
    // Tool implementations
    // -----------------------------------------------------------------------

    private fun handleToolCall(name: String, args: JSONObject): JSONObject {
        if (name != "end_session") {
            toolExecutor.handle(name, args)?.let { return it }
        }
        return when (name) {
            "leave_message" -> {
                val text = args.optString("text", "")
                if (text.isNotBlank()) {
                    noteManager.save("textchat", text)
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
                        JSONObject().put("status", if (navigate) "navigating" else "opened").put("address", address)
                    } catch (_: Exception) {
                        val webUri = Uri.parse("https://www.google.com/maps/search/${Uri.encode(address)}")
                        val fallback = Intent(Intent.ACTION_VIEW, webUri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            context.startActivity(fallback)
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
            "end_session" -> {
                Log.i(TAG, "Tool: end_session")
                endRequested = true
                JSONObject().put("status", "ending")
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
                        createdBy = "text",
                    )
                    mgr.schedule(task)
                    JSONObject().put("status", "scheduled").put("id", task.id)
                        .put("nextFireMs", task.nextFireMs)
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
}
