package com.pocketdaemon.pocket_daemon

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MemoryManager(private val context: Context) {

    companion object {
        private const val TAG = "MemoryManager"
        private const val MEMORY_DIR = "memory"
        private const val LOGS_DIR = "logs"
        private const val MEMORY_FILE = "MEMORY.md"
        private const val INDEX_FILE = "INDEX.md"
        private const val SOUL_FILE = "SOUL.md"
        private const val MAX_SEARCH_CHARS = 4000
        private const val MAX_RESULTS = 10

        private val DEFAULT_SOUL = """
You are {{ownerName}}'s {{role}}.
Be natural, concise, and helpful.
Always respond in the language {{ownerName}} uses.
        """.trimIndent()
    }

    private val app: PocketDaemonApp
        get() = PocketDaemonApp.instance!!

    private val memDir: File
        get() = File(app.persistentDir, MEMORY_DIR).also { it.mkdirs() }

    private val logsDir: File
        get() = File(app.persistentDir, LOGS_DIR)

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    // -----------------------------------------------------------------------
    // SOUL.md — agent personality / identity
    // -----------------------------------------------------------------------

    fun readSoul(): String {
        val file = File(memDir, SOUL_FILE)
        return if (file.exists() && file.length() > 0) file.readText() else DEFAULT_SOUL
    }

    fun writeSoul(text: String) {
        File(memDir, SOUL_FILE).writeText(text)
        Log.i(TAG, "SOUL.md updated (${text.length} chars)")
    }

    private fun resolvedSoul(): String {
        val app = PocketDaemonApp.instance ?: return ""
        val raw = readSoul()
        val resolved = raw
            .replace("{{ownerName}}", app.ownerName)
            .replace("{{agentName}}", app.agentName)
            .replace("{{role}}", app.agentRole)
        val lines = resolved.lines()
            .filter { line -> line.any { it.isLetterOrDigit() } }
            .toMutableList()
        if (app.agentName.isNotBlank()) {
            lines.add(0, "Your name is ${app.agentName}.")
        }
        return lines.joinToString("\n")
    }

    // -----------------------------------------------------------------------
    // Session context — loaded into system prompt at session start
    // -----------------------------------------------------------------------

    fun getSessionContext(excludeFile: String? = null): String {
        val parts = mutableListOf<String>()

        val soul = resolvedSoul()
        if (soul.isNotBlank()) {
            parts.add("## Identity\n$soul")
        }

        val memory = File(memDir, MEMORY_FILE)
        if (memory.exists() && memory.length() > 0) {
            parts.add("## Long-term memory\n${memory.readText().trim()}")
        }

        val index = File(memDir, INDEX_FILE)
        if (index.exists() && index.length() > 0) {
            parts.add("## Session history\n${index.readText().trim()}\nEach date has a summary file with per-session details. Use search_memory to recall specifics from past days.")
        }

        val today = dateFormat.format(Date())
        val yesterday = dateFormat.format(Date(System.currentTimeMillis() - 86_400_000))

        val todayTranscripts = loadNormalizedTranscripts(today, excludeFile)
        if (todayTranscripts.isNotBlank()) {
            parts.add("## Today's earlier conversations ($today)\n$todayTranscripts")
        }

        if (yesterday != today) {
            val yesterdayTranscripts = loadNormalizedTranscripts(yesterday, null)
            if (yesterdayTranscripts.isNotBlank()) {
                parts.add("## Yesterday's conversations ($yesterday)\n$yesterdayTranscripts")
            }
        }

        return parts.joinToString("\n\n")
    }

    private fun loadNormalizedTranscripts(date: String, excludeFile: String?): String {
        if (!logsDir.exists()) return ""
        val files = logsDir.listFiles { f ->
            f.name.startsWith(date) && f.name.endsWith(".txt")
        }?.sortedBy { it.name } ?: return ""

        val summaryLines = loadSummaryLines(date)

        val sb = StringBuilder()
        for (file in files) {
            if (excludeFile != null && file.name == excludeFile) continue

            val isCall = file.name.contains("-call.")
            if (isCall) {
                val line = summaryLines[file.nameWithoutExtension]
                if (line != null) {
                    if (sb.isNotEmpty()) sb.append("\n\n---\n\n")
                    sb.append("### ${file.nameWithoutExtension} (phone call)\n$line")
                }
                continue
            }

            val raw = try { file.readText() } catch (_: Exception) { continue }
            val normalized = MemoryExtractor.normalizeTranscript(raw)
            if (normalized.length < 30) continue
            if (sb.isNotEmpty()) sb.append("\n\n---\n\n")
            sb.append("### ${file.nameWithoutExtension}\n$normalized")
        }
        return sb.toString()
    }

    private fun loadSummaryLines(date: String): Map<String, String> {
        val file = File(memDir, "$date-summary.txt")
        if (!file.exists()) return emptyMap()
        val map = mutableMapOf<String, String>()
        for (line in file.readLines()) {
            val sep = line.indexOf(" — ")
            if (sep > 0) map[line.substring(0, sep).trim()] = line.substring(sep + 3).trim()
        }
        return map
    }

    // -----------------------------------------------------------------------
    // Search
    // -----------------------------------------------------------------------

    fun searchMemory(query: String): String {
        if (query.isBlank()) return "Empty query."

        val terms = query.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
        if (terms.isEmpty()) return "No valid search terms."

        data class Hit(val file: String, val text: String, val score: Int, val sortKey: String)

        val hits = mutableListOf<Hit>()

        val memFiles = memDir.listFiles { f ->
            (f.extension == "md" || f.extension == "txt") && f.name != "processed_logs.txt"
        } ?: emptyArray()
        for (file in memFiles) {
            val lower = file.name.lowercase()
            if (lower == SOUL_FILE.lowercase()) continue
            val sortKey = if (lower == MEMORY_FILE.lowercase()) "0000-00-00" else file.nameWithoutExtension

            val paragraphs = file.readText().split(Regex("\n{2,}"))
                .map { it.trim() }
                .filter { it.isNotBlank() }
            for (para in paragraphs) {
                val paraLower = para.lowercase()
                val score = terms.count { paraLower.contains(it) }
                if (score > 0) {
                    hits.add(Hit(file.name, para.take(500), score, sortKey))
                }
            }
        }

        if (logsDir.exists()) {
            val logFiles = logsDir.listFiles { f -> f.name.endsWith(".txt") } ?: emptyArray()
            for (file in logFiles) {
                val content = try { file.readText() } catch (_: Exception) { continue }
                val paragraphs = content.split(Regex("\n{2,}"))
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                for (para in paragraphs) {
                    val paraLower = para.lowercase()
                    val score = terms.count { paraLower.contains(it) }
                    if (score > 0) {
                        hits.add(Hit(file.name, para.take(500), score, file.nameWithoutExtension))
                    }
                }
            }
        }

        if (hits.isEmpty()) return "No results for: $query"

        hits.sortWith(compareByDescending<Hit> { it.score }.thenByDescending { it.sortKey })

        val sb = StringBuilder()
        var count = 0
        for (hit in hits) {
            if (count >= MAX_RESULTS || sb.length >= MAX_SEARCH_CHARS) break
            sb.append("[${hit.file}] ${hit.text}\n\n")
            count++
        }

        return sb.toString().trimEnd()
    }
}
