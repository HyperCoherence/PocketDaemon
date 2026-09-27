package com.pocketdaemon.pocket_daemon

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * The saved recordings under PocketDaemon/recordings: listing, playback, deletion and
 * Gemini transcription. A transcript lives next to its WAV as `<name>.transcript.json`.
 *
 * Playback methods must be called on the main thread (the method channel already is).
 * Listing reads every WAV header and session-log header, so call it off the main thread.
 */
class RecordingLibrary(private val app: PocketDaemonApp) {

    companion object {
        private const val TAG = "RecordingLibrary"
        private const val TRANSCRIPT_SUFFIX = ".transcript.json"
        private const val TICK_MS = 250L
        /** A session log that started within this window of the recording belongs to it. */
        private const val LOG_MATCH_WINDOW_MS = 15_000L
        private val nameFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        private val displayFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val dir: File
        get() = File(Environment.getExternalStorageDirectory(), "PocketDaemon/recordings")

    private val transcribing = HashSet<String>()
    private var player: MediaPlayer? = null
    private var playingName: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            emitPlayback()
            if (player?.isPlaying == true) handler.postDelayed(this, TICK_MS)
        }
    }

    // ---- listing ----

    fun list(): List<Map<String, Any?>> {
        val d = dir
        if (!d.exists()) return emptyList()
        val logs = readLogIndex()
        val files = d.listFiles { f -> f.isFile && f.name.endsWith(".wav") && !f.name.startsWith(".") }
            ?: return emptyList()
        return files.sortedByDescending { it.name }.map { describe(it, logs) }
    }

    fun describe(name: String): Map<String, Any?>? {
        val f = fileFor(name) ?: return null
        return describe(f, readLogIndex())
    }

    private fun describe(file: File, logs: List<LogEntry>): Map<String, Any?> {
        val base = file.name.removeSuffix(".wav")
        val type = base.substringBefore('_')
        val stamp = base.substringAfter('_', "")
        val startedAt = try {
            nameFmt.parse(stamp)?.time ?: file.lastModified()
        } catch (_: Exception) {
            file.lastModified()
        }
        val match = logs.firstOrNull { it.matches(type, startedAt) }
        val transcript = readTranscript(file)
        return mapOf(
            "name" to file.name,
            "path" to file.absolutePath,
            "type" to type,
            "startedAt" to startedAt,
            "started" to displayFmt.format(Date(startedAt)),
            "durationMs" to wavDurationMs(file),
            "sizeBytes" to file.length(),
            "caller" to (match?.caller ?: ""),
            "callerName" to (match?.name ?: ""),
            "sessionLog" to (match?.filename ?: ""),
            "transcript" to transcript?.let { transcriptToMap(it) },
            "transcribing" to synchronized(transcribing) { file.name in transcribing },
        )
    }

    private fun wavDurationMs(file: File): Long {
        try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 44) return 0
                val header = ByteArray(44)
                raf.readFully(header)
                val channels = le16(header, 22).coerceAtLeast(1)
                val rate = le32(header, 24).coerceAtLeast(1)
                val bits = le16(header, 34).coerceAtLeast(8)
                var dataLen = le32(header, 40).toLong()
                if (dataLen <= 0 || dataLen > file.length() - 44) dataLen = file.length() - 44
                val bytesPerSec = rate.toLong() * channels * (bits / 8)
                return if (bytesPerSec > 0) dataLen * 1000 / bytesPerSec else 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "WAV header read failed for ${file.name}: ${e.message}")
        }
        val dataLen = (file.length() - 44).coerceAtLeast(0)
        return dataLen * 1000 / (AudioRecorder.OUTPUT_SAMPLE_RATE * 2L)
    }

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private data class LogEntry(
        val filename: String,
        val type: String,
        val caller: String,
        val name: String,
        val startedAt: Long,
    ) {
        fun matches(recordingType: String, recordingStart: Long): Boolean {
            val typeOk = when (recordingType) {
                "call" -> type == "call"
                "trusted-call" -> type == "trusted-call"
                "chat" -> type == "chat" || type == "conversation"
                else -> false
            }
            return typeOk && abs(startedAt - recordingStart) <= LOG_MATCH_WINDOW_MS
        }
    }

    /** Header metadata of every session log, so a recording can show who the call was with. */
    private fun readLogIndex(): List<LogEntry> {
        val logDir = File(app.persistentDir, "logs")
        val files = logDir.listFiles { f -> f.isFile && f.name.endsWith(".txt") } ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                val meta = HashMap<String, String>()
                f.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.trimEnd() == "---") break
                        val idx = line.indexOf(": ")
                        if (idx > 0) meta[line.substring(0, idx)] = line.substring(idx + 2)
                    }
                }
                val started = meta["started"]?.let { displayFmt.parse(it)?.time } ?: return@mapNotNull null
                LogEntry(f.name, meta["type"] ?: "", meta["caller"] ?: "", meta["name"] ?: "", started)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun fileFor(name: String): File? {
        if (name.isBlank() || name.contains('/') || name.contains('\\') || !name.endsWith(".wav")) return null
        val f = File(dir, name)
        return if (f.isFile) f else null
    }

    // ---- transcripts ----

    private fun transcriptFile(wav: File) =
        File(wav.parentFile, wav.name.removeSuffix(".wav") + TRANSCRIPT_SUFFIX)

    private fun readTranscript(wav: File): JSONObject? {
        val f = transcriptFile(wav)
        if (!f.exists()) return null
        return try {
            JSONObject(f.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable transcript ${f.name}: ${e.message}")
            null
        }
    }

    private fun transcriptToMap(json: JSONObject): Map<String, Any?> {
        val segs = json.optJSONArray("segments") ?: JSONArray()
        val segments = (0 until segs.length()).mapNotNull { i ->
            val s = segs.optJSONObject(i) ?: return@mapNotNull null
            mapOf(
                "speaker" to s.optString("speaker", "").ifBlank { null },
                "startSec" to s.optDouble("startSec", Double.NaN).takeIf { !it.isNaN() },
                "text" to s.optString("text", ""),
            )
        }
        return mapOf(
            "text" to json.optString("text", ""),
            "model" to json.optString("model", ""),
            "mode" to json.optString("mode", ""),
            "createdAt" to json.optString("createdAt", ""),
            "segments" to segments,
        )
    }

    /** Starts a background transcription. Returns an error message, or null when it started. */
    fun transcribe(name: String): String? {
        val f = fileFor(name) ?: return "Recording not found"
        val apiKey = app.providerApiKey(ProviderIds.GEMINI)
        if (apiKey.isBlank()) return "No Gemini API key configured"
        synchronized(transcribing) {
            if (!transcribing.add(name)) return "Already transcribing"
        }
        emitTranscription(name, "queued", null)
        Thread({
            try {
                val transcriber = GeminiTranscriber(apiKey, app.httpClient)
                val result = transcriber.transcribe(f, "audio/wav", wavDurationMs(f)) { status ->
                    emitTranscription(name, status, null)
                }
                val json = JSONObject()
                    .put("model", GeminiModels.TRANSCRIBE)
                    .put("mode", result.mode)
                    .put("createdAt", displayFmt.format(Date()))
                    .put("text", result.text)
                    .put("segments", JSONArray().apply {
                        for (s in result.segments) {
                            put(JSONObject().apply {
                                put("speaker", s.speaker ?: JSONObject.NULL)
                                put("startSec", s.startSec ?: JSONObject.NULL)
                                put("text", s.text)
                            })
                        }
                    })
                transcriptFile(f).writeText(json.toString(2))
                Log.i(TAG, "Transcribed $name (${result.segments.size} segments, ${result.mode})")
                emitTranscription(name, "done", null)
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed for $name: ${e.message}")
                emitTranscription(name, "error", e.message ?: "Transcription failed")
            } finally {
                synchronized(transcribing) { transcribing.remove(name) }
            }
        }, "recording-transcribe").start()
        return null
    }

    private fun emitTranscription(name: String, status: String, message: String?) {
        app.emitEvent("recordingTranscription", mapOf(
            "name" to name,
            "status" to status,
            "message" to (message ?: ""),
        ))
    }

    // ---- deletion ----

    fun delete(name: String): Boolean {
        val f = fileFor(name) ?: return false
        if (playingName == name) stop()
        val ok = f.delete()
        transcriptFile(f).delete()
        Log.i(TAG, "Deleted $name: $ok")
        return ok
    }

    // ---- playback ----

    fun play(name: String): Boolean {
        val f = fileFor(name) ?: return false
        if (playingName == name && player != null) {
            resume()
            return true
        }
        stop()
        return try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener {
                handler.post {
                    emitPlayback("finished")
                    releasePlayer()
                }
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Playback error $what/$extra for $name")
                handler.post {
                    emitPlayback("error")
                    releasePlayer()
                }
                true
            }
            mp.prepare()
            player = mp
            playingName = name
            mp.start()
            emitPlayback()
            handler.removeCallbacks(ticker)
            handler.postDelayed(ticker, TICK_MS)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Play failed for $name: ${e.message}")
            releasePlayer()
            false
        }
    }

    fun pause() {
        val mp = player ?: return
        if (mp.isPlaying) mp.pause()
        handler.removeCallbacks(ticker)
        emitPlayback()
    }

    fun resume() {
        val mp = player ?: return
        if (!mp.isPlaying) mp.start()
        emitPlayback()
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, TICK_MS)
    }

    fun seek(positionMs: Int) {
        val mp = player ?: return
        mp.seekTo(positionMs.coerceIn(0, mp.duration))
        emitPlayback()
    }

    fun stop() {
        if (player == null) return
        emitPlayback("stopped")
        releasePlayer()
    }

    fun playbackState(): Map<String, Any?> = snapshot(null)

    private fun releasePlayer() {
        handler.removeCallbacks(ticker)
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        playingName = null
    }

    private fun snapshot(override: String?): Map<String, Any?> {
        val mp = player
        val state = override ?: when {
            mp == null -> "stopped"
            mp.isPlaying -> "playing"
            else -> "paused"
        }
        val position = try { mp?.currentPosition ?: 0 } catch (_: Exception) { 0 }
        val duration = try { mp?.duration ?: 0 } catch (_: Exception) { 0 }
        return mapOf(
            "name" to (playingName ?: ""),
            "state" to state,
            "positionMs" to position,
            "durationMs" to duration,
        )
    }

    private fun emitPlayback(override: String? = null) {
        app.emitEvent("recordingPlayback", snapshot(override))
    }
}
