package com.pocketdaemon.pocket_daemon

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MemoryExtractor(private val context: Context) {

    companion object {
        private const val TAG = "MemoryExtractor"
        private const val MEMORY_DIR = "memory"
        private const val LOGS_DIR = "logs"
        private const val MEMORY_FILE = "MEMORY.md"
        private const val INDEX_FILE = "INDEX.md"
        private const val PROCESSED_FILE = "processed_logs.txt"
        private const val MIN_TRANSCRIPT_CHARS = 50
        private const val GAP_THRESHOLD_MINUTES = 5

        private fun systemInstruction(ownerName: String) = """You are a memory extraction agent for $ownerName's phone assistant. After each conversation, you extract two things:
1. **facts** — permanent information about $ownerName, their contacts, preferences, relationships, decisions, or anything that would be useful across all future conversations. Do NOT duplicate facts already in existing memory.
2. **summary** — a 1-2 sentence summary of what happened in this session (who called/spoke, what was discussed, what was decided/requested).

Return ONLY a JSON object: {"facts": ["...", ...], "summary": "..."}. Return {"facts": [], "summary": ""} if the session is trivial or contains no meaningful content (e.g. just a greeting with no substance)."""

        private const val COMPACT_INSTRUCTION = """Rewrite these facts as concise flowing prose. Merge related facts into sentences. Drop the subject name where context makes it obvious. Keep ALL information — do not summarize or omit anything. No bullet points, no markdown headers. Just clean paragraphs separated by blank lines."""

        private val TIME_RE = Regex("""\[(\d{2}):(\d{2}):(\d{2})]""")
        private val LINE_RE = Regex("""\[(\d{2}:\d{2}:\d{2})]\s+(user|agent|tool):\s?(.*)""")
        private val DATE_RE = Regex("""^(\d{4}-\d{2}-\d{2})""")

        fun normalizeTranscript(raw: String): String {
            val lines = raw.lines()
            val result = mutableListOf<String>()
            var currentSpeaker: String? = null
            val currentText = mutableListOf<String>()
            var lastMinutes = -1

            for (line in lines) {
                val match = LINE_RE.matchEntire(line.trim()) ?: continue
                val (tsStr, speaker, text) = match.destructured

                val timeMatch = TIME_RE.find(tsStr)
                if (timeMatch != null) {
                    val (h, m, _) = timeMatch.destructured
                    val totalMin = h.toInt() * 60 + m.toInt()
                    if (lastMinutes >= 0 && totalMin - lastMinutes >= GAP_THRESHOLD_MINUTES) {
                        if (currentSpeaker != null && currentText.isNotEmpty()) {
                            result.add("$currentSpeaker: ${currentText.joinToString(" ")}")
                            currentSpeaker = null
                            currentText.clear()
                        }
                        result.add("--- ${h}:${m} ---")
                    }
                    lastMinutes = totalMin
                }

                if (speaker == "tool") {
                    if (currentSpeaker != null && currentText.isNotEmpty()) {
                        result.add("$currentSpeaker: ${currentText.joinToString(" ")}")
                        currentSpeaker = null
                        currentText.clear()
                    }
                    result.add("tool: $text")
                    continue
                }

                if (speaker == currentSpeaker) {
                    currentText.add(text.trim())
                } else {
                    if (currentSpeaker != null && currentText.isNotEmpty()) {
                        result.add("$currentSpeaker: ${currentText.joinToString(" ")}")
                    }
                    currentSpeaker = speaker
                    currentText.clear()
                    currentText.add(text.trim())
                }
            }

            if (currentSpeaker != null && currentText.isNotEmpty()) {
                result.add("$currentSpeaker: ${currentText.joinToString(" ")}")
            }

            return result.joinToString("\n")
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
        }
    }

    private val app: PocketDaemonApp
        get() = PocketDaemonApp.instance!!

    private val memDir: File
        get() = File(app.persistentDir, MEMORY_DIR).also { it.mkdirs() }

    private val logsDir: File
        get() = File(app.persistentDir, LOGS_DIR)

    /**
     * Run extraction on a completed session log. Call from a background thread.
     * Skips if already processed. Returns number of new facts added.
     */
    fun extract(rawTranscript: String, logFilename: String): Int {
        if (isProcessed(logFilename)) {
            Log.i(TAG, "Already processed: $logFilename")
            return 0
        }

        val isCall = isCallSession(rawTranscript)
        val normalized = normalizeTranscript(rawTranscript)

        if (normalized.length < MIN_TRANSCRIPT_CHARS) {
            Log.i(TAG, "Skipped $logFilename: too short (${normalized.length} chars)")
            markProcessed(logFilename)
            return 0
        }

        val existingMemory = readExistingMemory()

        val result = callModel(normalized, existingMemory) ?: return 0
        val facts = result.first
        val summary = result.second

        if (isCall && facts.isNotEmpty()) {
            Log.i(TAG, "Suppressed ${facts.size} facts from call session $logFilename")
        }

        val effectiveFacts = if (isCall) emptyList() else facts

        if (effectiveFacts.isNotEmpty()) {
            appendFacts(effectiveFacts)
        }
        if (summary.isNotBlank()) {
            appendDaySummary(logFilename, summary)
        }

        markProcessed(logFilename)
        Log.i(TAG, "Extracted from $logFilename: ${effectiveFacts.size} facts, summary=${summary.length} chars")
        return effectiveFacts.size
    }

    /**
     * Process all unprocessed session logs. Call from a background thread.
     * [onProgress] is called after each file with (current, total, filename).
     * Returns map with processing stats.
     */
    fun processAll(
        onProgress: ((current: Int, total: Int, filename: String) -> Unit)? = null,
    ): Map<String, Int> {
        if (!logsDir.exists()) return mapOf("total" to 0, "processed" to 0, "skipped" to 0)

        val allLogs = logsDir.listFiles { f ->
            f.name.endsWith(".txt") && f.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}-\\d{3}-.*\\.txt"))
        }?.sortedBy { it.name } ?: return mapOf("total" to 0, "processed" to 0, "skipped" to 0)

        val processed = loadProcessedSet()
        val toProcess = allLogs.filter { it.name !in processed }
        val total = toProcess.size

        var newlyProcessed = 0
        var totalNewFacts = 0

        Log.i(TAG, "processAll: ${allLogs.size} logs total, $total to process")

        for ((i, file) in toProcess.withIndex()) {
            onProgress?.invoke(i + 1, total, file.name)

            val raw = try { file.readText() } catch (e: Exception) {
                Log.w(TAG, "Cannot read ${file.name}: ${e.message}")
                continue
            }

            try {
                totalNewFacts += extract(raw, file.name)
                newlyProcessed++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract ${file.name}: ${e.message}")
            }

            Thread.sleep(1000)
        }

        if (total > 0) onProgress?.invoke(total, total, "compacting...")

        refreshIndex()

        if (totalNewFacts > 0) {
            compactMemory()
        }

        Log.i(TAG, "processAll done: $newlyProcessed processed, $totalNewFacts new facts")
        return mapOf("total" to allLogs.size, "processed" to newlyProcessed, "skipped" to allLogs.size - total)
    }

    /**
     * Rebuild INDEX.md from daily summary files. One line per day.
     */
    fun refreshIndex() {
        try {
            val summaryFiles = memDir.listFiles { f ->
                f.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}-summary\\.txt"))
            }?.sortedBy { it.name } ?: return

            if (summaryFiles.isEmpty()) return

            val sb = StringBuilder("# Session Index\n\n")
            for (file in summaryFiles) {
                val date = file.name.substringBefore("-summary.txt")
                val lines = file.readLines().filter { it.isNotBlank() }
                val count = lines.size
                val preview = lines.firstOrNull()
                    ?.substringAfter(" — ", "")
                    ?.take(80) ?: ""
                sb.append("- **$date** — $preview ($count sessions)\n")
            }

            File(memDir, INDEX_FILE).writeText(sb.toString())
            Log.i(TAG, "Refreshed INDEX.md (${summaryFiles.size} days)")
        } catch (e: Exception) {
            Log.w(TAG, "Index refresh failed: ${e.message}")
        }
    }

    /**
     * Rewrite MEMORY.md as concise flowing prose via LLM.
     */
    fun compactMemory() {
        val file = File(memDir, MEMORY_FILE)
        if (!file.exists()) return
        val original = file.readText().trim()
        if (original.length < 100) return

        val schema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("text", JSONObject().put("type", "string")))
            .put("required", JSONArray().put("text"))

        try {
            val response = ReasoningClients.forRole(app, AgentRoles.MEMORY).generate(
                ReasoningRequest(
                    system = COMPACT_INSTRUCTION,
                    messages = listOf(ReasoningMessage.user(original)),
                    jsonSchema = schema,
                    maxOutputTokens = 8192,
                ),
            )
            val compacted = ReasoningSchemas.parseJsonObject(response.text)?.optString("text", "")
                ?.takeIf { it.isNotBlank() } ?: response.text

            if (compacted.isBlank() || compacted.length < original.length * 0.3) {
                Log.w(TAG, "Compaction rejected: output ${compacted.length} chars vs original ${original.length}")
                return
            }

            val tmp = File(memDir, "$MEMORY_FILE.tmp")
            tmp.writeText(compacted.trim() + "\n")
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
            Log.i(TAG, "Compacted MEMORY.md: ${original.length} -> ${compacted.length} chars")
        } catch (e: Exception) {
            Log.e(TAG, "Compaction failed: ${e.message}")
        }
    }

    // -----------------------------------------------------------------------

    private fun isCallSession(raw: String): Boolean {
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed == "---") break
            if (trimmed.startsWith("type:")) {
                val t = trimmed.substringAfter(":").trim().lowercase()
                return t == "call" || t == "trusted-call"
            }
        }
        return false
    }

    private fun readExistingMemory(): String {
        val file = File(memDir, MEMORY_FILE)
        return if (file.exists()) file.readText().trim() else ""
    }

    private fun loadProcessedSet(): Set<String> {
        val file = File(memDir, PROCESSED_FILE)
        if (!file.exists()) return emptySet()
        return file.readLines().map { it.trim() }.filter { it.isNotBlank() }.toSet()
    }

    private fun isProcessed(logFilename: String): Boolean =
        logFilename in loadProcessedSet()

    @Synchronized
    private fun markProcessed(logFilename: String) {
        val file = File(memDir, PROCESSED_FILE)
        file.appendText("$logFilename\n")
    }

    private fun callModel(transcript: String, existingMemory: String): Pair<List<String>, String>? {
        val userMsg = "Existing memory:\n$existingMemory\n\n---\n\nSession transcript:\n$transcript"
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("facts", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                .put("summary", JSONObject().put("type", "string")))
            .put("required", JSONArray().put("facts").put("summary"))

        return try {
            val response = ReasoningClients.forRole(app, AgentRoles.MEMORY).generate(
                ReasoningRequest(
                    system = systemInstruction(app.ownerName),
                    messages = listOf(ReasoningMessage.user(userMsg)),
                    jsonSchema = schema,
                    maxOutputTokens = 4096,
                ),
            )
            val parsed = ReasoningSchemas.parseJsonObject(response.text) ?: JSONObject()
            val factsArr = parsed.optJSONArray("facts") ?: JSONArray()
            val facts = (0 until factsArr.length()).map { factsArr.getString(it) }
            Pair(facts, parsed.optString("summary", ""))
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed: ${e.message}")
            null
        }
    }

    @Synchronized
    private fun appendFacts(facts: List<String>) {
        val file = File(memDir, MEMORY_FILE)
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val block = buildString {
            append("\n### $ts\n")
            for (fact in facts) {
                append("- $fact\n")
            }
        }
        file.appendText(block)
        Log.i(TAG, "Appended ${facts.size} facts to $MEMORY_FILE")
    }

    @Synchronized
    private fun appendDaySummary(logFilename: String, summary: String) {
        val date = DATE_RE.find(logFilename)?.groupValues?.get(1)
            ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val file = File(memDir, "$date-summary.txt")
        val session = logFilename.substringBeforeLast(".txt")
        file.appendText("$session — $summary\n")
        Log.i(TAG, "Appended summary to $date-summary.txt")
    }
}
