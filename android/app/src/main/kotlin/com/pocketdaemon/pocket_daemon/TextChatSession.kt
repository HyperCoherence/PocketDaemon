package com.pocketdaemon.pocket_daemon

import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.net.Uri
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

/**
 * Text-based chat session using the Gemini REST API (generateContent).
 * Manages multi-turn conversation history, function calling, and logs
 * to the same session_logs directory as voice sessions for transcript continuity.
 */
class TextChatSession(private val context: Context) {

    companion object {
        private const val TAG = "TextChatSession"
        private const val MODEL = "gemini-3.1-pro-preview"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val MAX_TOOL_ROUNDS = 10

        private fun stringParam(name: String, desc: String) = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put(name, JSONObject()
                    .put("type", "string")
                    .put("description", desc)
                )
            )
            .put("required", JSONArray().put(name))
    }

    private val app = PocketDaemonApp.instance!!
    private val noteManager = NoteManager(context)
    private val memoryManager = MemoryManager(context)
    private val toolExecutor = AgentToolExecutor(context, "textchat", "text")

    private val conversationHistory = mutableListOf<JSONObject>()
    private var systemInstruction: String = ""
    private var sessionLog: SessionLogger? = null

    @Volatile var active = false
        private set
    @Volatile var busy = false
        private set
    @Volatile private var endRequested = false

    fun start() {
        if (active) return
        active = true

        sessionLog = SessionLogger.create(context, "textchat")

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
        val parts = JSONArray()
        if (imageBase64 != null) {
            parts.put(JSONObject().put("inlineData", JSONObject()
                .put("mimeType", imageMimeType ?: "image/jpeg")
                .put("data", imageBase64)
            ))
        }
        if (text.isNotBlank()) {
            parts.put(JSONObject().put("text", text))
        }
        conversationHistory.add(JSONObject()
            .put("role", "user")
            .put("parts", parts)
        )

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
                    val newFacts = extractor.extract(transcript, logName, app.apiKey)
                    extractor.refreshIndex()
                    if (newFacts > 0) extractor.compactMemory(app.apiKey)
                } catch (e: Exception) {
                    Log.w(TAG, "Memory extraction failed: ${e.message}")
                }
            }.start()
        }

        Log.i(TAG, "Text chat session ended")
        app.emitEvent("textChatEnded", emptyMap())
    }

    // -----------------------------------------------------------------------
    // generateContent loop with function calling
    // -----------------------------------------------------------------------

    private fun runGenerateLoop(): String? {
        var rounds = 0
        while (rounds < MAX_TOOL_ROUNDS) {
            rounds++
            val responseContent = callGenerateContent() ?: return null

            conversationHistory.add(responseContent)

            val parts = responseContent.optJSONArray("parts") ?: return null
            val functionCalls = mutableListOf<Pair<String, JSONObject>>()
            val textParts = StringBuilder()

            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.has("functionCall")) {
                    val fc = part.getJSONObject("functionCall")
                    functionCalls.add(fc.getString("name") to fc)
                }
                if (part.has("text")) {
                    textParts.append(part.getString("text"))
                }
            }

            if (functionCalls.isEmpty()) {
                return textParts.toString().ifBlank { null }
            }

            val functionResponseParts = JSONArray()
            for ((name, fc) in functionCalls) {
                val args = fc.optJSONObject("args") ?: JSONObject()
                val id = fc.optString("id", "")
                Log.i(TAG, "Tool call: $name args=$args")
                app.emitEvent("textChatThinking", mapOf("tool" to name))
                sessionLog?.logTool(name, args)

                val result = handleToolCall(name, args)

                val responseObj = JSONObject()
                    .put("name", name)
                    .put("response", result)
                if (id.isNotBlank()) responseObj.put("id", id)
                functionResponseParts.put(JSONObject().put("functionResponse", responseObj))
            }

            conversationHistory.add(JSONObject()
                .put("role", "user")
                .put("parts", functionResponseParts)
            )
        }

        Log.w(TAG, "Max tool rounds reached ($MAX_TOOL_ROUNDS)")
        return null
    }

    private fun callGenerateContent(): JSONObject? {
        val url = "$BASE_URL/$MODEL:generateContent?key=${app.apiKey}"

        val body = JSONObject().apply {
            put("systemInstruction", JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
            )
            put("contents", JSONArray().apply { conversationHistory.forEach { put(it) } })
            val toolsArr = buildTools()
            if (toolsArr.length() > 0) {
                put("tools", toolsArr)
            }
        }

        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            app.httpClient.newCall(request).execute().use { response ->
                val raw = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.e(TAG, "generateContent failed: ${response.code} ${raw.take(500)}")
                    return null
                }
                val json = JSONObject(raw)
                json.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
            }
        } catch (e: Exception) {
            Log.e(TAG, "generateContent error: ${e.message}", e)
            null
        }
    }

    private fun buildTools(): JSONArray {
        return AgentToolRegistry.restTools(
            ownerName = app.ownerName,
            agentType = "chat",
            isEnabled = { app.isToolEnabled("chat", it) },
            includeGoogleSearch = true,
        )

        val ownerName = app.ownerName
        val toolsArr = JSONArray()

        val declarations = listOf(
            Triple("leave_message",
                "Leave a message for $ownerName to read. Appears in their notification inbox.",
                stringParam("text", "The message content")),
            Triple("search_memory",
                "Search $ownerName's memory for relevant information. Use keywords or phrases.",
                stringParam("query", "Search keywords or phrase")),
            Triple("get_location",
                "Get $ownerName's current GPS location.",
                null),
            Triple("get_notes",
                "Retrieve all notes and messages saved by the phone agent and chat sessions.",
                null),
            Triple("open_maps",
                "Open Google Maps. Set navigate=true for turn-by-turn directions (replaces current destination if already navigating). Default mode opens a map search.",
                JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("address", JSONObject().put("type", "string").put("description", "The address, place name, or search query"))
                        .put("navigate", JSONObject().put("type", "boolean").put("description", "true for turn-by-turn navigation, false for map search (default false)")))
                    .put("required", JSONArray().put("address"))),
            Triple("play_youtube",
                "Play a YouTube video. Use google_search first to find a specific, age-appropriate video URL on the topic, then call this tool with that URL. YouTube time is limited per day; the system auto-closes YouTube when time runs out. If the limit is reached, tell the user their YouTube time for today is used up and they need approval for more. Only play educational or enriching content.",
                JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("url", JSONObject().put("type", "string").put("description", "Full YouTube video URL"))
                        .put("title", JSONObject().put("type", "string").put("description", "Video title for logging")))
                    .put("required", JSONArray().put("url"))),
            Triple("add_contact",
                "Add a contact to $ownerName's phone. Phone numbers MUST be in full international format.",
                JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("name", JSONObject().put("type", "string").put("description", "Contact display name"))
                        .put("phone", JSONObject().put("type", "string").put("description", "Phone number")))
                    .put("required", JSONArray().put("name").put("phone"))),
            Triple("end_session",
                "End the current text chat session. Use when the conversation is complete or $ownerName says goodbye.",
                null),
            Triple("schedule_task",
                "Schedule a task for the future. The task prompt will be executed automatically at the scheduled time and the result delivered as a notification to $ownerName. Use for reminders, periodic check-ins, delayed actions, or anything $ownerName wants done later.",
                JSONObject()
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
                    .put("required", JSONArray().put("description").put("prompt").put("delayMinutes"))),
            Triple("take_photo",
                "Take a photo using the phone's camera. Saves to storage. Supports an optional delay timer. Tell $ownerName before capturing so they can prepare.",
                JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("camera", JSONObject()
                            .put("type", "string")
                            .put("enum", JSONArray().put("back").put("front"))
                            .put("description", "Which camera to use (default: back)"))
                        .put("delay_seconds", JSONObject()
                            .put("type", "integer")
                            .put("description", "Seconds to wait before taking the photo (0-30, default 0)")))),
            Triple("use_skill",
                "Load a skill's full instructions by name. Call this when one of the available skills is relevant to the current task.",
                stringParam("name", "The skill folder name from the available skills list")),
        )

        val funcDecls = JSONArray()
        for ((name, desc, params) in declarations) {
            if (!app.isToolEnabled("chat", name)) continue
            val decl = JSONObject()
                .put("name", name)
                .put("description", desc)
            if (params != null) decl.put("parameters", params)
            funcDecls.put(decl)
        }

        if (funcDecls.length() > 0) {
            toolsArr.put(JSONObject().put("functionDeclarations", funcDecls))
        }

        if (app.isToolEnabled("chat", "google_search")) {
            toolsArr.put(JSONObject().put("google_search", JSONObject()))
        }

        return toolsArr
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
