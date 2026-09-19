package com.pocketdaemon.pocket_daemon

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrustedCallSession(
    private val context: Context,
    val callerNumber: String,
    private val callerConfig: TrustedContactConfig,
    private val onHangUp: () -> Unit,
) : ActiveCallSession {

    companion object {
        private const val TAG = "TrustedCallSession"
        private const val INITIAL_INTERRUPT_GRACE_MS = 8_000L

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
            ToolSpec(
                name = "hangUp",
                description = "End the current phone call. Use when the conversation is finished or the caller wants to hang up.",
            ),
            ToolSpec(
                name = "leave_message",
                description = "Leave a message for $ownerName to read. Appears in their notification inbox. Use for caller messages, call summaries, or anything $ownerName needs to see and act on.",
                parameters = stringParam("text", "The message content"),
            ),
            ToolSpec(
                name = "search_memory",
                description = "Search $ownerName's memory for relevant information. Use keywords or phrases.",
                parameters = stringParam("query", "Search keywords or phrase"),
            ),
            ToolSpec(
                name = "get_location",
                description = "Get $ownerName's current GPS location.",
            ),
            ToolSpec(
                name = "get_notes",
                description = "Retrieve all notes and messages saved by the phone agent and chat sessions.",
            ),
            ToolSpec(
                name = "use_skill",
                description = "Load a skill's full instructions by name. Call this when one of the available skills is relevant to the current task.",
                parameters = stringParam("name", "The skill folder name from the available skills list"),
            ),
        )

        fun tools(ownerName: String) = AgentToolRegistry.liveDeclarations(ownerName, "trusted")
    }

    private val app = PocketDaemonApp.instance!!
    private val noteManager = NoteManager(context)
    private val memoryManager = MemoryManager(context)
    private val toolExecutor = AgentToolExecutor(context, callerConfig.name.ifBlank { callerNumber }, "trusted")
    private val sessionLog = SessionLogger.create(context, "trusted-call", mapOf(
        "caller" to callerNumber,
        "name" to callerConfig.name,
    ))

    private var audioHandler: CallAudioHandler? = null
    private var gemini: VoiceSessionClient? = null
    private var recorder: AudioRecorder? = null
    private var initialInterruptIgnoreUntilMs = 0L
    private var initialInterruptionIgnored = false
    @Volatile private var active = false

    override fun start() {
        active = true
        Log.i(TAG, "Trusted session starting for ${callerConfig.name} ($callerNumber)")

        val voiceConfig = app.agentConfig(AgentRoles.VOICE)
        val apiKey = voiceConfig.apiKey

        if (apiKey.isBlank()) {
            Log.w(TAG, "No API key — audio only")
            audioHandler = CallAudioHandler(context, speakerMonitor = app.speakerMonitorEnabled) { }
            audioHandler?.start()
            return
        }

        val memoryCtx = memoryManager.getSessionContext(excludeFile = sessionLog.filename)
        val callerCtx = buildString {
            append("The caller is ${callerConfig.name}")
            if (callerConfig.relation.isNotBlank()) append(", ${callerConfig.relation}")
            append(". This is a trusted caller with full access.")
            if (callerConfig.prompt.isNotBlank()) append("\n${callerConfig.prompt}")
        }
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
            append(callerCtx)
            append("\n---\n")
            append(app.systemPrompt)
        }

        val enabledTools = tools(app.ownerName).filter { app.isToolEnabled("trusted", it.name) }
        val useSearch = app.isToolEnabled("trusted", "google_search")

        if (app.recordAgentCallsEnabled) {
            recorder = AudioRecorder("trusted-call").also { it.start() }
        }

        gemini = VoiceSessionFactory.create(
            config = voiceConfig,
            systemPrompt = fullPrompt,
            tools = enabledTools,
            googleSearch = useSearch,
            onAgentAudio = { pcm ->
                audioHandler?.enqueuePlayback(pcm)
                recorder?.writePlaybackAudio(pcm, CallAudioHandler.PLAYBACK_RATE)
            },
            onTranscript = { speaker, text ->
                sessionLog.log(speaker, text)
                app.emitEvent("transcript", mapOf("speaker" to speaker, "text" to text))
            },
            onToolCall = { name, _, args -> handleToolCall(name, args) },
            onReady = {
                Log.i(TAG, "${voiceConfig.provider} voice ready - greeting ${callerConfig.name}")
                armInitialInterruptIgnore()
                gemini?.sendText("${callerConfig.name} is calling. Greet them warmly.")
            },
            onTurnComplete = {
                Log.i(TAG, "Agent turn complete")
                disarmInitialInterruptIgnore()
                recorder?.finishPlaybackTurn()
            },
            onInterrupted = {
                if (shouldIgnoreInitialInterruption()) {
                    Log.i(TAG, "Ignoring initial interruption during trusted call greeting")
                } else {
                    if (app.bargeInEnabled) audioHandler?.flushPlayback()
                    recorder?.finishPlaybackTurn()
                }
            },
            onSessionEnded = { reason -> Log.i(TAG, "${voiceConfig.provider} voice session ended: ${reason ?: "clean"}") }
        )

        audioHandler = CallAudioHandler(context, speakerMonitor = app.speakerMonitorEnabled) { capturedPcm ->
            gemini?.sendAudio(capturedPcm)
            recorder?.writeCaptureAudio(capturedPcm)
        }

        audioHandler?.start()
        gemini?.connect()
        Log.i(TAG, "Trusted session active: audio + ${voiceConfig.provider} voice (recording=${recorder != null})")
    }

    override fun stop() {
        active = false
        Log.i(TAG, "Trusted session stopping")
        gemini?.disconnect()
        audioHandler?.stop()
        recorder?.stop()
        recorder = null
        sessionLog.close()
        if (app.memoryExtractionEnabled) {
            val transcript = sessionLog.getTranscript()
            val logName = sessionLog.filename
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
    }

    override fun awaitTermination() {
        audioHandler?.awaitTermination()
    }

    private fun armInitialInterruptIgnore() {
        initialInterruptionIgnored = false
        initialInterruptIgnoreUntilMs = SystemClock.elapsedRealtime() + INITIAL_INTERRUPT_GRACE_MS
    }

    private fun disarmInitialInterruptIgnore() {
        initialInterruptIgnoreUntilMs = 0L
        initialInterruptionIgnored = true
    }

    private fun shouldIgnoreInitialInterruption(): Boolean {
        if (initialInterruptionIgnored) return false
        if (SystemClock.elapsedRealtime() > initialInterruptIgnoreUntilMs) return false
        initialInterruptionIgnored = true
        return true
    }

    private fun handleToolCall(name: String, args: JSONObject): JSONObject {
        sessionLog.logTool(name, args)
        if (name != "hangUp") {
            toolExecutor.handle(name, args)?.let { return it }
        }
        return when (name) {
            "hangUp" -> {
                Log.i(TAG, "Tool: hangUp")
                onHangUp()
                JSONObject().put("status", "ok")
            }
            "leave_message" -> {
                val text = args.optString("text", "")
                if (text.isNotBlank()) {
                    noteManager.save(callerConfig.name.ifBlank { callerNumber }, text)
                    Log.i(TAG, "Tool: leave_message saved")
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
}
