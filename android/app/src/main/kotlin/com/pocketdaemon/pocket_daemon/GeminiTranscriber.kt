package com.pocketdaemon.pocket_daemon

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Transcribes a recorded audio file with Gemini 3.5 Transcribe over REST.
 *
 * The audio goes up through the Files API (resumable upload) and is then handed to the
 * Interactions endpoint by URI. That is the documented path for pre-recorded audio and it
 * sidesteps the inline request size limit, which a long phone call would exceed.
 * Speaker diarization is capped at 30 minutes of audio, so longer files fall back to
 * smart mode without speaker labels.
 */
class GeminiTranscriber(
    private val apiKey: String,
    baseHttp: OkHttpClient,
    private val model: String = GeminiModels.TRANSCRIBE,
) {
    companion object {
        private const val TAG = "GeminiTranscriber"
        private const val API = "https://generativelanguage.googleapis.com"
        private val JSON = "application/json".toMediaType()
        const val DIARIZATION_LIMIT_MS = 30L * 60_000L
        private const val FILE_READY_TIMEOUT_MS = 120_000L
        private const val INTERACTION_TIMEOUT_MS = 15L * 60_000L
    }

    data class Segment(val speaker: String?, val startSec: Double?, val text: String)
    data class Result(val text: String, val segments: List<Segment>, val mode: String)

    class TranscribeException(message: String) : Exception(message)

    private val http = baseHttp.newBuilder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    /** Runs the whole upload → transcribe → cleanup flow; blocks the calling thread. */
    fun transcribe(file: File, mimeType: String, durationMs: Long, onStatus: (String) -> Unit): Result {
        onStatus("uploading")
        val uploaded = uploadFile(file, mimeType)
        try {
            waitUntilActive(uploaded.name)
            onStatus("transcribing")
            val diarize = durationMs in 1..DIARIZATION_LIMIT_MS
            val interaction = createInteraction(uploaded.uri, mimeType, diarize)
            return parse(interaction, if (diarize) "verbatim" else "smart")
        } finally {
            deleteFile(uploaded.name)
        }
    }

    private data class UploadedFile(val name: String, val uri: String)

    private fun uploadFile(file: File, mimeType: String): UploadedFile {
        val start = Request.Builder()
            .url("$API/upload/v1beta/files")
            .header("x-goog-api-key", apiKey)
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", file.length().toString())
            .header("X-Goog-Upload-Header-Content-Type", mimeType)
            .post(
                JSONObject().put("file", JSONObject().put("display_name", file.name))
                    .toString().toRequestBody(JSON)
            )
            .build()
        val uploadUrl = http.newCall(start).execute().use { response ->
            if (!response.isSuccessful) {
                val text = response.body?.string() ?: ""
                throw TranscribeException("Upload start failed (${response.code}): ${text.take(200)}")
            }
            response.header("x-goog-upload-url")
                ?: throw TranscribeException("Upload start returned no upload URL")
        }

        val upload = Request.Builder()
            .url(uploadUrl)
            .header("X-Goog-Upload-Offset", "0")
            .header("X-Goog-Upload-Command", "upload, finalize")
            .post(file.asRequestBody(mimeType.toMediaType()))
            .build()
        val json = http.newCall(upload).execute().use { response ->
            val text = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw TranscribeException("Upload failed (${response.code}): ${text.take(200)}")
            }
            JSONObject(text)
        }
        val fileObj = json.optJSONObject("file")
            ?: throw TranscribeException("Upload response had no file: ${json.toString().take(200)}")
        val name = fileObj.optString("name", "")
        val uri = fileObj.optString("uri", "")
        if (name.isBlank() || uri.isBlank()) {
            throw TranscribeException("Upload response is missing the file name or uri")
        }
        Log.i(TAG, "Uploaded ${file.name} as $name")
        return UploadedFile(name, uri)
    }

    private fun waitUntilActive(name: String) {
        val deadline = System.currentTimeMillis() + FILE_READY_TIMEOUT_MS
        while (true) {
            val request = Request.Builder()
                .url("$API/v1beta/$name")
                .header("x-goog-api-key", apiKey)
                .get()
                .build()
            val state = http.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    throw TranscribeException("File status failed (${response.code}): ${text.take(200)}")
                }
                JSONObject(text).optString("state", "ACTIVE")
            }
            when (state) {
                "ACTIVE" -> return
                "FAILED" -> throw TranscribeException("Gemini could not process the audio file")
            }
            if (System.currentTimeMillis() > deadline) {
                throw TranscribeException("Timed out waiting for the uploaded file")
            }
            Thread.sleep(1000)
        }
    }

    private fun createInteraction(uri: String, mimeType: String, diarize: Boolean): JSONObject {
        val mode: Any = if (diarize) {
            JSONObject().put("type", "verbatim").put("diarization_mode", "speaker")
        } else {
            "smart"
        }
        val body = JSONObject()
            .put("model", model)
            .put("input", JSONArray().put(
                JSONObject()
                    .put("type", "audio")
                    .put("uri", uri)
                    .put("mime_type", mimeType)
            ))
            .put("generation_config", JSONObject()
                .put("transcription_config", JSONObject().put("mode", mode)))

        val request = Request.Builder()
            .url("$API/v1beta/interactions")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody(JSON))
            .build()
        var interaction = http.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                Log.e(TAG, "interactions returned ${response.code}: ${text.take(400)}")
                throw TranscribeException("Gemini returned ${response.code}: ${text.take(200)}")
            }
            JSONObject(text)
        }

        // Unary requests normally come back completed; poll if the server queued it anyway.
        val deadline = System.currentTimeMillis() + INTERACTION_TIMEOUT_MS
        while (interaction.optString("status", "completed") in setOf("queued", "in_progress")) {
            val id = interaction.optString("id", "")
            if (id.isBlank()) break
            if (System.currentTimeMillis() > deadline) throw TranscribeException("Transcription timed out")
            Thread.sleep(2000)
            val poll = Request.Builder()
                .url("$API/v1beta/interactions/$id")
                .header("x-goog-api-key", apiKey)
                .get()
                .build()
            interaction = http.newCall(poll).execute().use { response ->
                val text = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    throw TranscribeException("Polling failed (${response.code}): ${text.take(200)}")
                }
                JSONObject(text)
            }
        }

        val status = interaction.optString("status", "completed")
        if (status == "failed" || status == "cancelled") {
            val err = interaction.optJSONArray("errors")?.optJSONObject(0)?.optString("message", "") ?: ""
            throw TranscribeException("Transcription $status" + if (err.isNotBlank()) ": $err" else "")
        }
        return interaction
    }

    private fun deleteFile(name: String) {
        try {
            val request = Request.Builder()
                .url("$API/v1beta/$name")
                .header("x-goog-api-key", apiKey)
                .delete()
                .build()
            http.newCall(request).execute().close()
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete uploaded file $name: ${e.message}")
        }
    }

    // ---- response parsing ----

    private fun parse(interaction: JSONObject, mode: String): Result {
        val textItems = mutableListOf<JSONObject>()
        interaction.optJSONArray("outputs")?.let { collectText(it, textItems) }
        if (textItems.isEmpty()) {
            val steps = interaction.optJSONArray("steps")
            if (steps != null) for (i in 0 until steps.length()) {
                steps.optJSONObject(i)?.optJSONArray("content")?.let { collectText(it, textItems) }
            }
        }
        if (textItems.isEmpty()) {
            Log.w(TAG, "No text in interaction: ${interaction.toString().take(400)}")
            throw TranscribeException("Gemini returned no transcript")
        }

        val full = StringBuilder()
        val segments = mutableListOf<Segment>()
        for (item in textItems) {
            val text = item.optString("text", "")
            if (text.isBlank()) continue
            if (full.isNotEmpty()) full.append('\n')
            full.append(text)
            segments.addAll(segmentsFor(text, item.optJSONArray("annotations")))
        }
        return Result(full.toString().trim(), mergeAdjacent(segments), mode)
    }

    private fun collectText(items: JSONArray, into: MutableList<JSONObject>) {
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optString("type", "") == "text" && item.has("text")) into.add(item)
        }
    }

    /**
     * Groups word_info annotations into runs of the same speaker. Each run is cut out of
     * the original text by byte index so punctuation and formatting survive; when the
     * indices are missing the run falls back to joining the word texts.
     */
    private fun segmentsFor(text: String, annotations: JSONArray?): List<Segment> {
        val words = mutableListOf<JSONObject>()
        if (annotations != null) for (i in 0 until annotations.length()) {
            val a = annotations.optJSONObject(i) ?: continue
            if (a.optString("type", "") == "word_info") words.add(a)
        }
        if (words.isEmpty()) return listOf(Segment(null, null, text.trim()))

        val bytes = text.toByteArray(Charsets.UTF_8)
        val runs = mutableListOf<Segment>()
        var speaker: String? = null
        var runStart = -1
        var runEnd = -1
        var runStartSec: Double? = null
        val runWords = StringBuilder()
        var open = false

        fun flush() {
            val sliced = if (runStart in 0 until runEnd && runEnd <= bytes.size) {
                String(bytes, runStart, runEnd - runStart, Charsets.UTF_8)
            } else {
                runWords.toString()
            }
            val t = sliced.trim()
            if (t.isNotEmpty()) runs.add(Segment(speaker, runStartSec, t))
            runWords.clear()
            runStart = -1
            runEnd = -1
            runStartSec = null
        }

        for (w in words) {
            val s = w.optString("speaker", "").ifBlank { null }
            if (open && s != speaker) {
                flush()
                open = false
            }
            if (!open) {
                open = true
                speaker = s
                runStart = w.optInt("start_index", -1)
                runStartSec = parseOffset(w.optString("start_offset", ""))
            }
            val end = w.optInt("end_index", -1)
            if (end > runEnd) runEnd = end
            if (runWords.isNotEmpty()) runWords.append(' ')
            runWords.append(w.optString("text", ""))
        }
        if (open) flush()
        return runs
    }

    private fun mergeAdjacent(segments: List<Segment>): List<Segment> {
        val out = mutableListOf<Segment>()
        for (s in segments) {
            val last = out.lastOrNull()
            if (last != null && last.speaker == s.speaker && last.speaker != null) {
                out[out.size - 1] = Segment(last.speaker, last.startSec, last.text + " " + s.text)
            } else {
                out.add(s)
            }
        }
        return out
    }

    /** Offsets arrive as protobuf durations such as "12.5s". */
    private fun parseOffset(raw: String): Double? {
        val trimmed = raw.trim().removeSuffix("s")
        return trimmed.toDoubleOrNull()
    }
}
