package com.pocketdaemon.pocket_daemon

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.io.File

class MainActivity : FlutterActivity() {

    companion object {
        private const val TAG = "PocketDaemonMain"
        private const val CALL_CONTROL = "pocket_daemon/call_control"
        private const val CALL_EVENTS = "pocket_daemon/call_events"
        private const val EXTRACTION_EVENTS = "pocket_daemon/extraction_events"
        private const val PERM_REQ = 100
    }

    private val handler = Handler(Looper.getMainLooper())
    private var eventSink: EventChannel.EventSink? = null
    private var extractionSink: EventChannel.EventSink? = null
    private lateinit var noteManager: NoteManager
    private var chatSession: ChatSession? = null
    private var textChatSession: TextChatSession? = null

    private val eventListener: (String, Map<String, Any?>) -> Unit = { type, data ->
        handler.post { emitToFlutter(type, data) }
    }

    private val actionListener: (String) -> Unit = { action ->
        if (action == ChatForegroundService.ACTION_DISCONNECT) {
            handler.post {
                chatSession?.endConversation()
                chatSession = null
                textChatSession?.end()
                textChatSession = null
            }
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        noteManager = NoteManager(applicationContext)
        app.addEventListener(eventListener)
        app.addActionListener(actionListener)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CALL_CONTROL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "requestPermissions" -> {
                        requestAllPermissions()
                        result.success(true)
                    }
                    "getPermissionStatus" -> result.success(getPermissionStatus())
                    "getStatus" -> {
                        val pInfo = packageManager.getPackageInfo(packageName, 0)
                        result.success(mapOf(
                            "agentEnabled" to app.agentEnabled,
                            "hasApiKey" to app.apiKey.isNotBlank(),
                            "appVersion" to (pInfo.versionName ?: "unknown"),
                            "chatMode" to app.configGet("chatMode", "conversation"),
                        ))
                    }
                    "setAgentEnabled" -> {
                        val enabled = call.argument<Boolean>("enabled") ?: false
                        app.configPut("agentEnabled", enabled)
                        app.emitEvent("agentToggled", mapOf("enabled" to enabled))
                        result.success(true)
                    }
                    "setConfig" -> {
                        val systemPrompt = call.argument<String>("systemPrompt")
                        if (systemPrompt != null) app.writeSystemPrompt(systemPrompt)
                        val pairs = mutableMapOf<String, Any?>()
                        call.argument<String>("apiKey")?.let { pairs["apiKey"] = it }
                        call.argument<String>("model")?.let { pairs["model"] = it }
                        call.argument<Int>("answerDelay")?.let { pairs["answerDelay"] = it.toLong() }
                        call.argument<String>("ownerName")?.let { pairs["ownerName"] = it }
                        call.argument<String>("agentName")?.let { pairs["agentName"] = it }
                        call.argument<String>("agentRole")?.let { pairs["agentRole"] = it }
                        call.argument<String>("voice")?.let { pairs["voice"] = it }
                        if (pairs.isNotEmpty()) app.configPutAll(pairs)
                        result.success(true)
                    }
                    "getConfig" -> {
                        val pInfo = packageManager.getPackageInfo(packageName, 0)
                        result.success(mapOf(
                            "apiKey" to app.apiKey,
                            "model" to app.model,
                            "systemPrompt" to app.systemPrompt,
                            "answerDelay" to app.answerDelayMs.toInt(),
                            "speakerMonitor" to app.speakerMonitorEnabled,
                            "bargeIn" to app.bargeInEnabled,
                            "ownerName" to app.ownerName,
                            "agentName" to app.agentName,
                            "agentRole" to app.agentRole,
                            "voice" to app.voice,
                            "assistantButton" to app.assistantButtonEnabled,
                            "chatMode" to app.configGet("chatMode", "conversation"),
                            "recordAgentCalls" to app.recordAgentCallsEnabled,
                            "recordAgentConversations" to app.recordAgentConversationsEnabled,
                            "recordPhoneCalls" to app.recordPhoneCallsEnabled,
                            "appVersion" to (pInfo.versionName ?: "unknown"),
                        ))
                    }
                    "setChatMode" -> {
                        val mode = call.argument<String>("mode") ?: "conversation"
                        app.configPut("chatMode", mode)
                        result.success(true)
                    }
                    "setSpeakerMonitor" -> {
                        val enabled = call.argument<Boolean>("enabled") ?: true
                        app.configPut("speakerMonitor", enabled)
                        result.success(true)
                    }
                    "setBargeIn" -> {
                        val enabled = call.argument<Boolean>("enabled") ?: true
                        app.configPut("bargeIn", enabled)
                        result.success(true)
                    }
                    "setAssistantButton" -> {
                        val enabled = call.argument<Boolean>("enabled") ?: false
                        app.configPut("assistantButton", enabled)
                        Thread {
                            try {
                                val pkg = "com.pocketdaemon.pocket_daemon"
                                if (enabled) {
                                    Runtime.getRuntime().exec(arrayOf("su", "-c",
                                        "settings put global power_button_long_press 5; " +
                                        "cmd role add-role-holder --user 0 android.app.role.ASSISTANT $pkg; " +
                                        "settings put secure assistant $pkg/.MainActivity"
                                    )).waitFor()
                                } else {
                                    Runtime.getRuntime().exec(arrayOf("su", "-c",
                                        "settings put global power_button_long_press 1; " +
                                        "cmd role clear-role-holders --user 0 android.app.role.ASSISTANT; " +
                                        "settings put secure assistant ''"
                                    )).waitFor()
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to toggle assistant button: ${e.message}")
                            }
                        }.start()
                        result.success(true)
                    }

                    "setRecording" -> {
                        val key = call.argument<String>("key") ?: ""
                        val enabled = call.argument<Boolean>("enabled") ?: false
                        if (key in listOf("recordAgentCalls", "recordAgentConversations", "recordPhoneCalls")) {
                            app.configPut(key, enabled)
                            result.success(true)
                        } else {
                            result.error("INVALID_KEY", "Unknown recording key: $key", null)
                        }
                    }

                    "takeOverCall" -> {
                        app.sendAction(PocketDaemonInCallService.ACTION_TAKE_OVER)
                        result.success(true)
                    }
                    "hangUpCall" -> {
                        app.sendAction(PocketDaemonInCallService.ACTION_HANG_UP)
                        result.success(true)
                    }

                    "startChat" -> {
                        if (chatSession?.active == true) {
                            result.success(mapOf("status" to "already_active"))
                        } else {
                            textChatSession?.end()
                            textChatSession = null
                            val mode = call.argument<String>("mode") ?: "ptt"
                            val existing = chatSession
                            if (existing == null || existing.conversationMode != (mode == "conversation")) {
                                existing?.cancel()
                                chatSession = ChatSession(
                                    context = applicationContext,
                                    conversationMode = mode == "conversation",
                                )
                            }
                            chatSession!!.startTalk()
                            result.success(mapOf("status" to "started", "mode" to mode))
                        }
                    }
                    "stopChat" -> {
                        chatSession?.stopTalk()
                        result.success(mapOf("status" to "stopping"))
                    }
                    "endConversation" -> {
                        chatSession?.endConversation()
                        chatSession = null
                        result.success(mapOf("status" to "ended"))
                    }
                    "cancelChat" -> {
                        chatSession?.cancel()
                        chatSession = null
                        result.success(mapOf("status" to "cancelled"))
                    }
                    "setKeepScreenOn" -> {
                        val enabled = call.argument<Boolean>("enabled") ?: false
                        if (enabled) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                        result.success(true)
                    }

                    "getAgentToolConfig" -> {
                        val config = app.getToolConfig()
                        val out = HashMap<String, Any>()
                        for ((agent, tools) in config) {
                            out[agent] = tools
                        }
                        result.success(out)
                    }
                    "setToolEnabled" -> {
                        val agentType = call.argument<String>("agentType") ?: ""
                        val toolName = call.argument<String>("toolName") ?: ""
                        val enabled = call.argument<Boolean>("enabled") ?: true
                        app.setToolEnabled(agentType, toolName, enabled)
                        result.success(true)
                    }

                    "getMemoryExtractionEnabled" -> {
                        result.success(app.memoryExtractionEnabled)
                    }

                    "setMemoryExtractionEnabled" -> {
                        val enabled = call.argument<Boolean>("enabled") ?: true
                        app.configPut("memoryExtraction", enabled)
                        result.success(true)
                    }

                    "runMemoryExtraction" -> {
                        Thread {
                            try {
                                val stats = MemoryExtractor(applicationContext)
                                    .processAll(app.apiKey) { current, total, filename ->
                                        handler.post {
                                            extractionSink?.success(mapOf(
                                                "current" to current,
                                                "total" to total,
                                                "filename" to filename,
                                            ))
                                        }
                                    }
                                handler.post { result.success(stats) }
                            } catch (e: Exception) {
                                handler.post { result.error("EXTRACT_FAIL", e.message, null) }
                            }
                        }.start()
                    }

                    "getSoul" -> {
                        val mm = MemoryManager(applicationContext)
                        result.success(mm.readSoul())
                    }
                    "setSoul" -> {
                        val text = call.argument<String>("text") ?: ""
                        val mm = MemoryManager(applicationContext)
                        mm.writeSoul(text)
                        result.success(true)
                    }

                    "getInitialNote" -> {
                        val noteId = intent.getStringExtra(NoteManager.EXTRA_NOTE_ID)
                        intent.removeExtra(NoteManager.EXTRA_NOTE_ID)
                        result.success(noteId)
                    }
                    "getSessionLogs" -> result.success(readSessionLogs())
                    "getNotes" -> result.success(noteManager.getAll())
                    "dismissNote" -> {
                        val id = call.argument<String>("id") ?: ""
                        val ok = noteManager.dismiss(id)
                        result.success(mapOf("dismissed" to ok))
                    }

                    "getTrustedContacts" -> {
                        result.success(app.trustedContacts.map { c ->
                            mapOf("number" to c.number, "name" to c.name,
                                  "relation" to c.relation, "prompt" to c.prompt)
                        })
                    }
                    "setTrustedContacts" -> {
                        @Suppress("UNCHECKED_CAST")
                        val list = call.argument<List<Map<String, String>>>("contacts") ?: emptyList()
                        val contacts = list.map { m ->
                            TrustedContactConfig(
                                number = m["number"] ?: "",
                                name = m["name"] ?: "",
                                relation = m["relation"] ?: "",
                                prompt = m["prompt"] ?: "",
                            )
                        }.filter { it.number.isNotBlank() }
                        app.setTrustedContacts(contacts)
                        result.success(true)
                    }
                    "addTrustedContact" -> {
                        val number = call.argument<String>("number") ?: ""
                        val name = call.argument<String>("name") ?: ""
                        val relation = call.argument<String>("relation") ?: ""
                        val prompt = call.argument<String>("prompt") ?: ""
                        if (number.isBlank()) {
                            result.success(mapOf("error" to "number required"))
                        } else {
                            val current = app.trustedContacts.toMutableList()
                            current.removeAll { it.number == number }
                            current.add(TrustedContactConfig(number, name, relation, prompt))
                            app.setTrustedContacts(current)
                            result.success(mapOf("status" to "added", "count" to current.size))
                        }
                    }
                    "getPhoneContacts" -> {
                        result.success(queryPhoneContacts())
                    }
                    "removeTrustedContact" -> {
                        val number = call.argument<String>("number") ?: ""
                        val current = app.trustedContacts.toMutableList()
                        val removed = current.removeAll { it.number == number }
                        app.setTrustedContacts(current)
                        result.success(mapOf("removed" to removed, "count" to current.size))
                    }

                    "getScheduledTasks" -> {
                        val mgr = ScheduledTaskManager(applicationContext)
                        result.success(mgr.getAllAsMaps())
                    }
                    "setScheduledTaskActive" -> {
                        val id = call.argument<String>("id") ?: ""
                        val active = call.argument<Boolean>("active") ?: true
                        val mgr = ScheduledTaskManager(applicationContext)
                        mgr.setActive(id, active)
                        result.success(true)
                    }
                    "removeScheduledTask" -> {
                        val id = call.argument<String>("id") ?: ""
                        val mgr = ScheduledTaskManager(applicationContext)
                        mgr.remove(id)
                        result.success(true)
                    }

                    "startTextChat" -> {
                        if (textChatSession?.active == true) {
                            result.success(mapOf("status" to "already_active"))
                        } else {
                            chatSession?.endConversation()
                            chatSession?.cancel()
                            chatSession = null
                            textChatSession = TextChatSession(applicationContext)
                            textChatSession!!.start()
                            result.success(mapOf("status" to "started"))
                        }
                    }
                    "sendTextMessage" -> {
                        val text = call.argument<String>("text") ?: ""
                        val imageBase64 = call.argument<String>("imageBase64")
                        val imageMimeType = call.argument<String>("imageMimeType")
                        if (text.isBlank() && imageBase64.isNullOrBlank()) {
                            result.success(mapOf("status" to "empty"))
                        } else if (textChatSession?.active != true) {
                            result.success(mapOf("status" to "no_session"))
                        } else {
                            textChatSession!!.sendMessage(text, imageBase64, imageMimeType)
                            result.success(mapOf("status" to "sent"))
                        }
                    }
                    "sendVoiceImage" -> {
                        val b64 = call.argument<String>("imageBase64") ?: ""
                        val mime = call.argument<String>("imageMimeType") ?: "image/jpeg"
                        val caption = call.argument<String>("caption")
                        if (b64.isBlank() || chatSession?.active != true) {
                            result.success(mapOf("status" to "no_session"))
                        } else {
                            chatSession!!.sendImage(b64, mime, caption)
                            result.success(mapOf("status" to "sent"))
                        }
                    }
                    "endTextChat" -> {
                        textChatSession?.end()
                        textChatSession = null
                        result.success(mapOf("status" to "ended"))
                    }

                    else -> result.notImplemented()
                }
            }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, CALL_EVENTS)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    eventSink = events
                }
                override fun onCancel(arguments: Any?) {
                    eventSink = null
                }
            })

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EXTRACTION_EVENTS)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    extractionSink = events
                }
                override fun onCancel(arguments: Any?) {
                    extractionSink = null
                }
            })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(NoteManager.EXTRA_NOTE_ID)?.let { noteId ->
            intent.removeExtra(NoteManager.EXTRA_NOTE_ID)
            handler.post { emitToFlutter("openNote", mapOf("id" to noteId)) }
        }
    }

    override fun onResume() {
        super.onResume()
        app.locationProvider.refreshInBackground()
    }

    override fun onDestroy() {
        chatSession?.cancel()
        textChatSession?.end()
        textChatSession = null
        app.removeActionListener(actionListener)
        app.removeEventListener(eventListener)
        super.onDestroy()
    }

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.ANSWER_PHONE_CALLS)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.MODIFY_AUDIO_SETTINGS)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.WRITE_CONTACTS)
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }.toTypedArray()

    private fun requestAllPermissions() {
        val needed = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERM_REQ)
        } else {
            emitToFlutter("permissionsResult", mapOf(
                "granted" to true, "details" to getPermissionStatus()
            ))
        }
    }

    private fun getPermissionStatus(): Map<String, Boolean> =
        requiredPermissions.associate { perm ->
            perm.substringAfterLast('.') to
                (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED)
        }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQ) {
            emitToFlutter("permissionsResult", mapOf(
                "granted" to grantResults.all { it == PackageManager.PERMISSION_GRANTED },
                "details" to getPermissionStatus()
            ))
        }
    }

    private fun emitToFlutter(type: String, data: Map<String, Any?>) {
        val event = HashMap<String, Any?>()
        event["type"] = type
        event.putAll(data)
        eventSink?.success(event)
    }

    private fun queryPhoneContacts(): List<Map<String, String>> {
        val contacts = mutableListOf<Map<String, String>>()
        val seen = mutableSetOf<String>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        contentResolver.query(uri, projection, null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx) ?: continue
                val phone = cursor.getString(numIdx)?.replace("\\s".toRegex(), "") ?: continue
                val key = "$name|$phone"
                if (seen.add(key)) {
                    contacts.add(mapOf("name" to name, "phone" to phone))
                }
            }
        }
        return contacts
    }

    private fun readSessionLogs(): List<Map<String, Any?>> {
        val dir = File((application as PocketDaemonApp).persistentDir, "logs")
        if (!dir.exists()) return emptyList()

        return (dir.listFiles { f -> f.name.endsWith(".txt") } ?: emptyArray())
            .sortedByDescending { it.name }
            .take(50)
            .map { file ->
                val lines = file.readLines()
                val meta = mutableMapOf<String, String>()
                val transcript = mutableListOf<Map<String, String>>()
                var inBody = false
                var ended = ""

                for (line in lines) {
                    if (!inBody) {
                        if (line.trimEnd() == "---") { inBody = true; continue }
                        val idx = line.indexOf(": ")
                        if (idx > 0) meta[line.substring(0, idx)] = line.substring(idx + 2)
                    } else {
                        if (line.trimEnd() == "---") continue
                        if (line.startsWith("ended: ")) { ended = line.substring(7); continue }
                        val m = Regex("^\\[(\\d{2}:\\d{2}:\\d{2})] (\\S+): (.*)$").find(line)
                        if (m != null) {
                            transcript.add(mapOf(
                                "time" to m.groupValues[1],
                                "speaker" to m.groupValues[2],
                                "text" to m.groupValues[3],
                            ))
                        }
                    }
                }

                mapOf<String, Any?>(
                    "filename" to file.name,
                    "type" to (meta["type"] ?: "unknown"),
                    "caller" to (meta["caller"] ?: ""),
                    "name" to (meta["name"] ?: ""),
                    "started" to (meta["started"] ?: ""),
                    "ended" to ended,
                    "transcript" to transcript,
                )
            }
    }

    private val app: PocketDaemonApp
        get() = application as PocketDaemonApp
}
