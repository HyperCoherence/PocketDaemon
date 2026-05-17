package com.pocketdaemon.pocket_daemon

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallSession(
    private val context: Context,
    val callerNumber: String,
    private val callerName: String?,
    private val onHangUp: () -> Unit,
) : ActiveCallSession {
    companion object {
        private const val TAG = "CallSession"
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
                description = "Leave a message for $ownerName to read. Appears in their notification inbox. Use for caller messages, call summaries, or anything $ownerName needs to see and act on. IMPORTANT: Always ask who is calling before using this tool — every message must include the caller's name.",
                parameters = stringParam("text", "The message content"),
            ),
            ToolSpec(
                name = "use_skill",
                description = "Load a skill's full instructions by name. Call this when one of the available skills is relevant to the current task.",
                parameters = stringParam("name", "The skill folder name from the available skills list"),
            ),
        )

        fun tools(ownerName: String) = AgentToolRegistry.liveDeclarations(ownerName, "call")
    }

    private val app = PocketDaemonApp.instance!!
    private val noteManager = NoteManager(context)
    private val memoryManager = MemoryManager(context)
    private val toolExecutor = AgentToolExecutor(context, callerNumber, "call")
    private val sessionLog = SessionLogger.create(context, "call", mapOf("caller" to callerNumber))

    private var audioHandler: CallAudioHandler? = null
    private var gemini: VoiceSessionClient? = null
    private var recorder: AudioRecorder? = null
    private var initialInterruptIgnoreUntilMs = 0L
    private var initialInterruptionIgnored = false
    @Volatile private var active = false

    override fun start() {
        active = true
        Log.i(TAG, "Session starting for $callerNumber")

        val voiceConfig = app.agentConfig(AgentRoles.VOICE)
        val apiKey = voiceConfig.apiKey

        if (apiKey.isBlank()) {
            Log.w(TAG, "No API key configured — audio only, no AI")
            startAudioOnly()
            return
        }

        val memoryCtx = memoryManager.getSessionContext(excludeFile = sessionLog.filename)
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
            append("You are answering a phone call from an unknown caller.")
            append("\n---\n")
            append(app.systemPrompt)
        }

        val enabledTools = tools(app.ownerName).filter { app.isToolEnabled("call", it.name) }
        val useSearch = app.isToolEnabled("call", "google_search")

        if (app.recordAgentCallsEnabled) {
            recorder = AudioRecorder("call").also { it.start() }
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
                Log.i(TAG, "${voiceConfig.provider} voice ready - sending greeting prompt")
                val greeting = if (callerName != null) {
                    "A caller named $callerName is on the line from $callerNumber. Greet them by name."
                } else {
                    "A caller is on the line from $callerNumber. Greet them."
                }
                armInitialInterruptIgnore()
                gemini?.sendText(greeting)
            },
            onTurnComplete = {
                Log.i(TAG, "Agent turn complete")
                disarmInitialInterruptIgnore()
                recorder?.finishPlaybackTurn()
            },
            onInterrupted = {
                if (shouldIgnoreInitialInterruption()) {
                    Log.i(TAG, "Ignoring initial interruption during call greeting")
                } else {
                    if (app.bargeInEnabled) audioHandler?.flushPlayback()
                    recorder?.finishPlaybackTurn()
                }
            },
            onSessionEnded = { reason ->
                Log.i(TAG, "${voiceConfig.provider} voice session ended: ${reason ?: "clean"}")
            }
        )

        audioHandler = CallAudioHandler(context, speakerMonitor = app.speakerMonitorEnabled) { capturedPcm ->
            gemini?.sendAudio(capturedPcm)
            recorder?.writeCaptureAudio(capturedPcm)
        }

        gemini?.connect()
        audioHandler?.start()

        Log.i(TAG, "Session active: audio + ${voiceConfig.provider} voice (recording=${recorder != null})")
    }

    private fun startAudioOnly() {
        audioHandler = CallAudioHandler(context, speakerMonitor = app.speakerMonitorEnabled) { }
        audioHandler?.start()
        Log.i(TAG, "Session active: audio only (no agent)")
    }

    override fun stop() {
        active = false
        Log.i(TAG, "Session stopping")
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
                    val newFacts = extractor.extract(transcript, logName, app.apiKey)
                    extractor.refreshIndex()
                    if (newFacts > 0) extractor.compactMemory(app.apiKey)
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
                    noteManager.save(callerNumber, text)
                    Log.i(TAG, "Tool: leave_message saved")
                    JSONObject().put("status", "saved")
                } else {
                    JSONObject().put("error", "empty text")
                }
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
