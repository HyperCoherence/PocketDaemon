package com.pocketdaemon.pocket_daemon

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The ask_fable tool: a Claude-backed advisor with live web search and fetch.
 *
 * Answers come back in a spoken-summary / details / sources layout. Long answers are saved as a
 * note so the voice agent only has to read the summary. A rolling per-day thread lets follow-up
 * questions build on earlier ones.
 */
class FableAdvisor(
    private val context: Context,
    private val recentTranscript: (() -> String?)? = null,
) {
    companion object {
        private const val TAG = "FableAdvisor"
        private const val NOTE_THRESHOLD_CHARS = 400
        private const val MAX_THREAD_TURNS = 10
        private const val THREAD_FILE = "fable_thread.json"
        private const val MEMORY_CONTEXT_CHARS = 1500
        private const val TRANSCRIPT_CHARS = 1200
        private const val THREAD_ANSWER_CHARS = 1500
    }

    private val app = PocketDaemonApp.instance!!

    fun ask(question: String, mode: String?): JSONObject {
        if (question.isBlank()) return error("question is required")
        val config = app.agentConfig(AgentRoles.FABLE)
        if (config.apiKey.isBlank()) return error("Fable needs an Anthropic API key. Add one in settings.")

        val research = mode.equals("research", ignoreCase = true)
        val effort = if (research) "high" else (config.effort ?: PocketDaemonApp.DEFAULT_FABLE_EFFORT)
        val history = loadThread() + ReasoningMessage.user(question)
        Log.i(TAG, "Asking ${config.model} (effort=$effort, research=$research, thread=${history.size - 1} turns)")

        val response = try {
            ReasoningClients.forConfig(config).generate(
                ReasoningRequest(
                    system = buildSystem(),
                    messages = history,
                    webSearch = true,
                    effort = effort,
                    maxOutputTokens = if (research) 8192 else 4096,
                ),
            )
        } catch (e: ReasoningException) {
            Log.e(TAG, "Fable request failed: ${e.message}")
            return error("Fable is unavailable right now: ${e.message}")
        }
        if (response.refusal != null) return error("Fable declined this request: ${response.refusal}")

        val answer = FableAnswer.parse(response.text, response.sources)
        if (answer.summary.isBlank() && answer.details.isBlank()) return error("Fable returned an empty answer")
        saveThread(question, answer)

        val saveNote = answer.details.length > NOTE_THRESHOLD_CHARS || answer.sources.isNotEmpty()
        if (saveNote) {
            val note = buildString {
                append("Q: ").append(question.trim()).append("\n\n")
                append(answer.details.ifBlank { answer.summary })
                if (answer.sources.isNotEmpty()) {
                    append("\n\nSources:\n")
                    answer.sources.forEach { append("- ").append(it).append('\n') }
                }
            }
            NoteManager(context).save("fable", note.trim())
        }
        Log.i(TAG, "Fable answered (${answer.summary.length} summary chars, ${answer.details.length} detail chars, note=$saveNote)")

        val result = JSONObject()
            .put("status", "ok")
            .put("answer", answer.summary)
            .put("note_saved", saveNote)
            .put("sources", JSONArray(answer.sources))
        if (!saveNote && answer.details.isNotBlank() && answer.details != answer.summary) {
            result.put("details", answer.details)
        }
        return result
    }

    private fun buildSystem(): String {
        val owner = app.ownerName
        val now = SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a z", Locale.ENGLISH).format(Date())
        val memory = MemoryManager(context).getSessionContext().take(MEMORY_CONTEXT_CHARS)
        val transcript = recentTranscript?.invoke()?.takeLast(TRANSCRIPT_CHARS)
        val location = app.locationProvider.getLastLocationSummary()
        return buildString {
            append("You are Fable, a senior advisor consulted by $owner's phone assistant. ")
            append("The assistant relays your answer by voice, so lead with what matters and keep it grounded.\n")
            append("Reply in exactly this layout:\n")
            append("SUMMARY: one to three plain sentences that can be read aloud.\n")
            append("DETAILS: the full answer with the reasoning, steps, or figures that matter.\n")
            append("SOURCES: one URL per line when you used the web; write none otherwise.\n")
            append("Use web search and fetch whenever the question depends on current information or benefits from verification.\n")
            append("Current date and time: $now.\n")
            if (location != null) append("$location\n")
            if (memory.isNotBlank()) append("\nWhat the assistant remembers about $owner:\n$memory\n")
            if (!transcript.isNullOrBlank()) append("\nRecent conversation between $owner and the assistant:\n$transcript\n")
        }
    }

    // --- rolling per-day thread -------------------------------------------------------------

    private val threadFile: File
        get() = File(File(app.persistentDir, "memory").also { it.mkdirs() }, THREAD_FILE)

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun loadThread(): List<ReasoningMessage> {
        val file = threadFile
        if (!file.exists()) return emptyList()
        return try {
            val json = JSONObject(file.readText())
            if (json.optString("date", "") != today()) return emptyList()
            val turns = json.optJSONArray("turns") ?: return emptyList()
            val out = mutableListOf<ReasoningMessage>()
            for (i in 0 until turns.length()) {
                val turn = turns.optJSONObject(i) ?: continue
                val q = turn.optString("q", "")
                val a = turn.optString("a", "")
                if (q.isBlank() || a.isBlank()) continue
                out.add(ReasoningMessage.user(q))
                out.add(ReasoningMessage.assistant(a))
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "Could not read Fable thread: ${e.message}")
            emptyList()
        }
    }

    private fun saveThread(question: String, answer: FableAnswer) {
        try {
            val file = threadFile
            val existing = if (file.exists()) JSONObject(file.readText()) else JSONObject()
            val turns = if (existing.optString("date", "") == today()) {
                existing.optJSONArray("turns") ?: JSONArray()
            } else {
                JSONArray()
            }
            val stored = (answer.details.ifBlank { answer.summary }).take(THREAD_ANSWER_CHARS)
            turns.put(JSONObject().put("q", question.trim()).put("a", stored))
            while (turns.length() > MAX_THREAD_TURNS) turns.remove(0)
            file.writeText(JSONObject().put("date", today()).put("turns", turns).toString())
        } catch (e: Exception) {
            Log.w(TAG, "Could not save Fable thread: ${e.message}")
        }
    }

    private fun error(message: String): JSONObject =
        JSONObject().put("status", "error").put("error", message)
}

/** Fable's reply split into what gets spoken, what gets written down, and where it came from. */
data class FableAnswer(val summary: String, val details: String, val sources: List<String>) {
    companion object {
        private val URL_RE = Regex("""https?://\S+""")

        fun parse(text: String, reportedSources: List<String> = emptyList()): FableAnswer {
            val body = text.trim()
            val summaryAt = indexOfMarker(body, "SUMMARY:")
            val detailsAt = indexOfMarker(body, "DETAILS:")
            val sourcesAt = indexOfMarker(body, "SOURCES:")

            val summary: String
            val details: String
            val sourcesText: String
            if (summaryAt >= 0 || detailsAt >= 0) {
                val summaryEnd = listOf(detailsAt, sourcesAt).filter { it > summaryAt }.minOrNull() ?: body.length
                summary = if (summaryAt >= 0) body.substring(summaryAt + "SUMMARY:".length, summaryEnd).trim() else ""
                val detailsEnd = if (sourcesAt > detailsAt) sourcesAt else body.length
                details = if (detailsAt >= 0) body.substring(detailsAt + "DETAILS:".length, detailsEnd).trim() else summary
                sourcesText = if (sourcesAt >= 0) body.substring(sourcesAt + "SOURCES:".length).trim() else ""
            } else {
                summary = leadingSentences(body)
                details = body
                sourcesText = ""
            }

            val sources = LinkedHashSet<String>()
            URL_RE.findAll(sourcesText).forEach { sources.add(it.value.trimEnd('.', ',', ')', ']')) }
            reportedSources.forEach { if (it.isNotBlank()) sources.add(it) }

            return FableAnswer(
                summary = summary.ifBlank { leadingSentences(details) },
                details = details,
                sources = sources.toList(),
            )
        }

        private fun indexOfMarker(text: String, marker: String): Int {
            val idx = text.indexOf(marker, ignoreCase = true)
            return if (idx >= 0) idx else -1
        }

        /** First couple of sentences, capped, for when the model skipped the layout. */
        private fun leadingSentences(text: String): String {
            val flat = text.replace('\n', ' ').trim()
            if (flat.length <= 240) return flat
            val cut = Regex("""(?<=[.!?])\s""").split(flat).take(2).joinToString(" ").trim()
            return if (cut.isBlank() || cut.length > 320) flat.take(240).trimEnd() + "..." else cut
        }
    }
}
