package com.pocketdaemon.pocket_daemon

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.util.Log
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class PocketDaemonApp : Application() {

    companion object {
        private const val TAG = "PocketDaemonApp"
        const val CHANNEL_ACTIVE_CALL = "active_call"
        const val CHANNEL_ACTIVE_CHAT = "active_chat"
        const val CHANNEL_NOTES = "agent_notes"
        const val PREFS_NAME = "pocket_daemon_prefs"
        const val CONFIG_FILENAME = "config.json"

        const val DEFAULT_API_KEY = ""
        const val DEFAULT_MODEL = "gemini-3.1-flash-live-preview"
        fun defaultSystemPrompt(ownerName: String): String {
            val owner = ownerName.ifBlank { "the phone owner" }
            return "You are a helpful phone assistant answering calls on behalf of $owner. Be concise and natural. Greet callers briefly. If the caller asks for $owner, let them know they are unavailable, ask for the reason of the call and offer to take a message or help them directly. IMPORTANT: Always ask the caller for their name before leaving a message. Every message must identify who called."
        }
        const val DEFAULT_OWNER_NAME = "Phone owner"
        const val DEFAULT_AGENT_NAME = ""
        const val DEFAULT_AGENT_ROLE = "personal AI assistant"
        const val DEFAULT_ANSWER_DELAY_MS = 2000L
        const val DEFAULT_VOICE = "Kore"

        val AGENT_TOOLS = AgentToolRegistry.AGENT_TOOLS
        val TOOL_DEFAULTS_OFF = setOf<String>()
        val ALWAYS_ON_TOOLS = AgentToolRegistry.ALWAYS_ON_TOOLS

        @Volatile
        var instance: PocketDaemonApp? = null
            private set
    }

    @Volatile var outboundBridge: ChatSession? = null

    private var youtubeTimerProcess: Process? = null

    val youtubeRemainingFile: File
        get() = File(persistentDir, "youtube_remaining.txt")

    fun readYoutubeRemaining(): Pair<String, Int> {
        val file = youtubeRemainingFile
        if (!file.exists()) return "" to -1
        val parts = file.readText().trim().split(" ", limit = 2)
        if (parts.size < 2) return "" to -1
        return parts[0] to (parts[1].toIntOrNull() ?: -1)
    }

    fun writeYoutubeRemaining(date: String, minutes: Int) {
        youtubeRemainingFile.writeText("$date $minutes")
    }

    fun launchYoutubeTimer() {
        if (youtubeTimerProcess?.isAlive == true) {
            Log.i(TAG, "YouTube timer already running, skipping")
            return
        }
        val script = """
            FILE=/sdcard/PocketDaemon/youtube_remaining.txt
            IDLE=0
            while true; do
              sleep 60
              if pidof com.google.android.youtube > /dev/null 2>&1; then
                IDLE=0
                R=$(cat ${'$'}FILE 2>/dev/null | awk '{print ${'$'}2}')
                R=${'$'}((R - 1))
                D=$(cat ${'$'}FILE 2>/dev/null | awk '{print ${'$'}1}')
                echo "${'$'}D ${'$'}R" > ${'$'}FILE
                if [ ${'$'}R -le 0 ]; then
                  am force-stop com.google.android.youtube
                  exit 0
                fi
              else
                IDLE=${'$'}((IDLE + 1))
                if [ ${'$'}IDLE -ge 3 ]; then
                  exit 0
                fi
              fi
            done
        """.trimIndent()
        youtubeTimerProcess = Runtime.getRuntime().exec(arrayOf("sh", "-c", script))
        Log.i(TAG, "YouTube timer script launched")
    }

    val persistentDir: File
        get() = File(Environment.getExternalStorageDirectory(), "PocketDaemon").also { it.mkdirs() }

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    lateinit var prefs: SharedPreferences
        private set
    lateinit var locationProvider: LocationProvider
        private set

    private val configLock = Any()
    private var cachedConfig: JSONObject = JSONObject()

    private val configFile: File
        get() = File(persistentDir, CONFIG_FILENAME)

    private fun readConfigFromDisk(): JSONObject {
        val f = configFile
        if (!f.exists() || f.length() == 0L) return JSONObject()
        return try { JSONObject(f.readText()) } catch (e: Exception) {
            Log.w(TAG, "Corrupt config.json, resetting: ${e.message}")
            JSONObject()
        }
    }

    private fun writeConfigToDisk(json: JSONObject) {
        try {
            configFile.writeText(json.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write config.json: ${e.message}")
        }
    }

    fun configGet(key: String, default: String): String = synchronized(configLock) {
        cachedConfig.optString(key, default)
    }
    fun configGet(key: String, default: Boolean): Boolean = synchronized(configLock) {
        if (cachedConfig.has(key)) cachedConfig.optBoolean(key, default) else default
    }
    fun configGet(key: String, default: Long): Long = synchronized(configLock) {
        if (cachedConfig.has(key)) cachedConfig.optLong(key, default) else default
    }

    fun configPut(key: String, value: Any?) = synchronized(configLock) {
        cachedConfig.put(key, value ?: JSONObject.NULL)
        writeConfigToDisk(cachedConfig)
    }

    fun configPutAll(pairs: Map<String, Any?>) = synchronized(configLock) {
        for ((k, v) in pairs) cachedConfig.put(k, v ?: JSONObject.NULL)
        writeConfigToDisk(cachedConfig)
    }

    val agentEnabled: Boolean
        get() = configGet("agentEnabled", false)

    val apiKey: String
        get() = configGet("apiKey", DEFAULT_API_KEY)

    val model: String
        get() = configGet("model", DEFAULT_MODEL)

    val systemPrompt: String
        get() {
            val file = File(persistentDir, "CALL_PROMPT.md")
            if (file.exists() && file.length() > 0) return file.readText().trim()
            return defaultSystemPrompt(ownerName)
        }

    fun writeSystemPrompt(text: String) {
        File(persistentDir, "CALL_PROMPT.md").writeText(text)
    }

    val ownerName: String
        get() = configGet("ownerName", DEFAULT_OWNER_NAME)

    val agentName: String
        get() = configGet("agentName", DEFAULT_AGENT_NAME)

    val agentRole: String
        get() = configGet("agentRole", DEFAULT_AGENT_ROLE)

    val answerDelayMs: Long
        get() = configGet("answerDelay", DEFAULT_ANSWER_DELAY_MS)

    val speakerMonitorEnabled: Boolean
        get() = configGet("speakerMonitor", true)

    val bargeInEnabled: Boolean
        get() = configGet("bargeIn", true)

    val voice: String
        get() = configGet("voice", DEFAULT_VOICE)

    val memoryExtractionEnabled: Boolean
        get() = configGet("memoryExtraction", true)

    val assistantButtonEnabled: Boolean
        get() = configGet("assistantButton", false)

    val recordAgentCallsEnabled: Boolean
        get() = configGet("recordAgentCalls", false)

    val recordAgentConversationsEnabled: Boolean
        get() = configGet("recordAgentConversations", false)

    val recordPhoneCallsEnabled: Boolean
        get() = configGet("recordPhoneCalls", false)

    val trustedContacts: List<TrustedContactConfig>
        get() = synchronized(configLock) {
            parseTrustedContacts(cachedConfig.optJSONArray("trustedContacts"))
        }

    fun setTrustedContacts(contacts: List<TrustedContactConfig>) {
        val arr = JSONArray()
        for (c in contacts) {
            arr.put(JSONObject().apply {
                put("number", c.number)
                put("name", c.name)
                put("relation", c.relation)
                put("prompt", c.prompt)
            })
        }
        configPut("trustedContacts", arr)
        Log.i(TAG, "Trusted contacts updated (${contacts.size})")
    }

    fun isToolEnabled(agentType: String, toolName: String): Boolean {
        if (toolName in ALWAYS_ON_TOOLS) return true
        val config = loadToolConfig()
        val agentObj = config.optJSONObject(agentType) ?: return true
        return agentObj.optBoolean(toolName, true)
    }

    fun setToolEnabled(agentType: String, toolName: String, enabled: Boolean) {
        val config = loadToolConfig()
        val agentObj = config.optJSONObject(agentType) ?: JSONObject()
        agentObj.put(toolName, enabled)
        config.put(agentType, agentObj)
        configPut("toolConfig", config)
    }

    fun getToolConfig(): Map<String, Map<String, Boolean>> {
        val config = loadToolConfig()
        return AGENT_TOOLS.map { (agentType, tools) ->
            val agentObj = config.optJSONObject(agentType)
            agentType to tools.associate { tool ->
                tool to if (tool in ALWAYS_ON_TOOLS) true
                        else agentObj?.optBoolean(tool, true) ?: true
            }
        }.toMap()
    }

    private fun loadToolConfig(): JSONObject = synchronized(configLock) {
        val raw = cachedConfig.optJSONObject("toolConfig")
        return if (raw != null) try { JSONObject(raw.toString()) } catch (_: Exception) { JSONObject() }
               else JSONObject()
    }

    fun findTrustedContact(number: String): TrustedContactConfig? {
        val normalized = number.replace(Regex("[\\s\\-()]"), "")
        return trustedContacts.find { contact ->
            val cn = contact.number.replace(Regex("[\\s\\-()]"), "")
            cn == normalized
                || (cn.length >= 7 && normalized.length >= 7
                    && (normalized.endsWith(cn) || cn.endsWith(normalized)))
        }
    }

    private val eventListeners = mutableListOf<(String, Map<String, Any?>) -> Unit>()
    private val actionListeners = mutableListOf<(String) -> Unit>()

    fun addActionListener(listener: (String) -> Unit) {
        synchronized(actionListeners) { actionListeners.add(listener) }
    }

    fun removeActionListener(listener: (String) -> Unit) {
        synchronized(actionListeners) { actionListeners.remove(listener) }
    }

    fun sendAction(action: String) {
        Log.i(TAG, "Action: $action")
        synchronized(actionListeners) {
            for (l in actionListeners) {
                try { l(action) } catch (e: Exception) {
                    Log.w(TAG, "Action listener error", e)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        migrateToExternalStorage()
        initConfig()
        loadConfigFromDownloads()
        createNotificationChannels()
        locationProvider = LocationProvider(this)
        try {
            ScheduledTaskManager(this).rescheduleAll()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to reschedule tasks on startup: ${e.message}")
        }
        Thread { SessionLogger.pruneOldLogs(this) }.start()
        Log.i(TAG, "PocketDaemonApp initialized (config at ${configFile.absolutePath})")
    }

    private fun initConfig() {
        if (configFile.exists() && configFile.length() > 0) {
            cachedConfig = readConfigFromDisk()
            Log.i(TAG, "Config loaded from ${configFile.absolutePath}")
            return
        }
        // Migrate from SharedPreferences into config.json
        val json = JSONObject()
        json.put("apiKey", prefs.getString("gemini_api_key", DEFAULT_API_KEY) ?: DEFAULT_API_KEY)
        json.put("model", prefs.getString("gemini_model", DEFAULT_MODEL) ?: DEFAULT_MODEL)
        json.put("agentEnabled", prefs.getBoolean("agent_enabled", true))
        json.put("answerDelay", prefs.getLong("answer_delay_ms", DEFAULT_ANSWER_DELAY_MS))
        json.put("ownerName", prefs.getString("owner_name", DEFAULT_OWNER_NAME) ?: DEFAULT_OWNER_NAME)
        json.put("agentName", prefs.getString("agent_name", DEFAULT_AGENT_NAME) ?: DEFAULT_AGENT_NAME)
        json.put("agentRole", prefs.getString("agent_role", DEFAULT_AGENT_ROLE) ?: DEFAULT_AGENT_ROLE)
        json.put("voice", prefs.getString("gemini_voice", DEFAULT_VOICE) ?: DEFAULT_VOICE)
        json.put("memoryExtraction", prefs.getBoolean("memory_extraction_enabled", true))
        json.put("speakerMonitor", prefs.getBoolean("speaker_monitor_enabled", true))
        json.put("bargeIn", prefs.getBoolean("barge_in_enabled", true))
        val tc = prefs.getString("trusted_contacts", null)
        if (!tc.isNullOrBlank()) {
            try { json.put("trustedContacts", JSONArray(tc)) } catch (_: Exception) {}
        }
        val toolCfg = prefs.getString("agent_tool_config", null)
        if (!toolCfg.isNullOrBlank()) {
            try { json.put("toolConfig", JSONObject(toolCfg)) } catch (_: Exception) {}
        }
        cachedConfig = json
        writeConfigToDisk(json)
        Log.i(TAG, "Migrated SharedPreferences → config.json")
    }

    private fun migrateToExternalStorage() {
        val extMem = File(persistentDir, "memory")
        val intMem = File(filesDir, "agent_memory")
        if (!extMem.exists() && intMem.exists() && intMem.listFiles()?.isNotEmpty() == true) {
            extMem.mkdirs()
            intMem.listFiles()?.forEach { it.copyTo(File(extMem, it.name), overwrite = false) }
            Log.i(TAG, "Migrated agent_memory → external storage")
        }
        val extLogs = File(persistentDir, "logs")
        val intLogs = File(filesDir, "session_logs")
        if (!extLogs.exists() && intLogs.exists() && intLogs.listFiles()?.isNotEmpty() == true) {
            extLogs.mkdirs()
            intLogs.listFiles()?.forEach { it.copyTo(File(extLogs, it.name), overwrite = false) }
            Log.i(TAG, "Migrated session_logs → external storage")
        }
        for (name in listOf("agent_notes.json", "scheduled_tasks.json")) {
            val intFile = File(filesDir, name)
            val extFile = File(persistentDir, name)
            if (intFile.exists() && !extFile.exists()) {
                intFile.copyTo(extFile, overwrite = false)
                Log.i(TAG, "Migrated $name → external storage")
            }
        }
        val callPromptFile = File(persistentDir, "CALL_PROMPT.md")
        if (!callPromptFile.exists()) {
            val fromPrefs = prefs.getString("system_prompt", null)?.takeIf { it.isNotBlank() }
            if (fromPrefs != null) {
                callPromptFile.writeText(fromPrefs)
                Log.i(TAG, "Migrated system_prompt → CALL_PROMPT.md")
            }
        }
    }

    private val RUNTIME_ONLY_KEYS = setOf("agentEnabled")
    private val DOWNLOAD_CONFIG_KEYS = setOf(
        "apiKey",
        "model",
        "ownerName",
        "agentName",
        "agentRole",
        "answerDelay",
        "voice",
        "memoryExtraction",
        "speakerMonitor",
        "bargeIn",
        "assistantButton",
        "recordAgentCalls",
        "recordAgentConversations",
        "recordPhoneCalls",
        "trustedContacts",
        "toolConfig",
        "youtubeMinutes",
    )

    private fun loadConfigFromDownloads() {
        val file = java.io.File("/sdcard/Download/pocketdaemon_config.json")
        if (!file.exists()) return
        try {
            if (file.length() > 256_000) {
                Log.w(TAG, "Ignoring oversized sideload config")
                return
            }
            val json = JSONObject(file.readText())
            if (json.has("systemPrompt")) writeSystemPrompt(json.getString("systemPrompt"))
            synchronized(configLock) {
                for (key in json.keys()) {
                    if (key == "systemPrompt" || key in RUNTIME_ONLY_KEYS) continue
                    if (key !in DOWNLOAD_CONFIG_KEYS) {
                        Log.w(TAG, "Ignoring unsupported sideload config key: $key")
                        continue
                    }
                    cachedConfig.put(key, json.get(key))
                }
                writeConfigToDisk(cachedConfig)
            }
            file.delete()
            Log.i(TAG, "Config merged from Downloads and deleted")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load config from Downloads: ${e.message}")
        }
    }

    private fun parseTrustedContacts(arr: JSONArray?): List<TrustedContactConfig> {
        if (arr == null || arr.length() == 0) return emptyList()
        return try {
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                TrustedContactConfig(
                    number = obj.getString("number"),
                    name = obj.optString("name", ""),
                    relation = obj.optString("relation", ""),
                    prompt = obj.optString("prompt", ""),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse trusted contacts: ${e.message}")
            emptyList()
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ACTIVE_CALL, "Active Call",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shown while the agent is handling a call" })

            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ACTIVE_CHAT, "Active Chat",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shown while a chat session is active" })

            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_NOTES, "Agent Notes",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Notifications for agent notes" })
        }
    }

    fun addEventListener(listener: (String, Map<String, Any?>) -> Unit) {
        synchronized(eventListeners) { eventListeners.add(listener) }
    }

    fun removeEventListener(listener: (String, Map<String, Any?>) -> Unit) {
        synchronized(eventListeners) { eventListeners.remove(listener) }
    }

    fun emitEvent(type: String, data: Map<String, Any?> = emptyMap()) {
        Log.i(TAG, "Event: $type $data")
        synchronized(eventListeners) {
            for (l in eventListeners) {
                try { l(type, data) } catch (e: Exception) {
                    Log.w(TAG, "Event listener error", e)
                }
            }
        }
    }
}
