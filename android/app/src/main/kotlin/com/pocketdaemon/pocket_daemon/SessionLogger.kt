package com.pocketdaemon.pocket_daemon

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class SessionLogger private constructor(
    private val file: File,
) {
    companion object {
        private const val TAG = "SessionLogger"
        private const val DIR_NAME = "logs"
        private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        private val timestampFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

        fun create(
            context: Context,
            sessionType: String,
            metadata: Map<String, String> = emptyMap(),
        ): SessionLogger {
            val dir = File(PocketDaemonApp.instance!!.persistentDir, DIR_NAME).also { it.mkdirs() }
            val today = dateFmt.format(Date())
            val seq = nextSequence(dir, today)
            val name = "$today-${seq.toString().padStart(3, '0')}-$sessionType.txt"
            val file = File(dir, name)
            val header = buildString {
                appendLine("type: $sessionType")
                for ((k, v) in metadata) {
                    appendLine("$k: $v")
                }
                appendLine("started: ${timestampFmt.format(Date())}")
                appendLine("---")
            }
            file.writeText(header)
            Log.i(TAG, "Session log created: $name")
            return SessionLogger(file)
        }

        fun reopen(context: Context, filename: String): SessionLogger? {
            val dir = File(PocketDaemonApp.instance!!.persistentDir, DIR_NAME)
            val file = File(dir, filename)
            if (!file.exists()) {
                Log.w(TAG, "Cannot reopen, file missing: $filename")
                return null
            }
            val logger = SessionLogger(file)
            val ts = timestampFmt.format(Date())
            logger.append("--- reconnected: $ts ---")
            Log.i(TAG, "Session log reopened: $filename")
            return logger
        }

        fun pruneOldLogs(context: Context, keepDays: Int = 30) {
            val dir = File(PocketDaemonApp.instance!!.persistentDir, DIR_NAME)
            if (!dir.exists()) return
            val cutoff = dateFmt.format(Date(System.currentTimeMillis() - keepDays.toLong() * 86_400_000))
            val files = dir.listFiles { f -> f.name.endsWith(".txt") } ?: return
            var deleted = 0
            for (f in files) {
                val datePrefix = f.name.take(10)
                if (datePrefix < cutoff) {
                    f.delete()
                    deleted++
                }
            }
            if (deleted > 0) Log.i(TAG, "Pruned $deleted session logs older than $keepDays days")
        }

        private fun nextSequence(dir: File, today: String): Int {
            val existing = dir.listFiles { f -> f.name.startsWith(today) && f.name.endsWith(".txt") }
                ?: return 1
            var max = 0
            for (f in existing) {
                val parts = f.nameWithoutExtension.split("-")
                if (parts.size >= 4) {
                    val num = parts[3].toIntOrNull() ?: continue
                    if (num > max) max = num
                }
            }
            return max + 1
        }
    }

    val filename: String get() = file.name

    fun getTranscript(): String = try {
        file.readText()
    } catch (e: Exception) {
        Log.w(TAG, "Failed to read transcript: ${e.message}")
        ""
    }

    private var closed = false
    private var pendingSpeaker: String? = null
    private var pendingTimestamp: String? = null
    private val pendingText = StringBuilder()

    @Synchronized
    fun log(speaker: String, text: String) {
        if (closed) return
        if (speaker != pendingSpeaker) {
            flushPending()
            pendingSpeaker = speaker
            pendingTimestamp = timeFmt.format(Date())
        }
        if (pendingText.isNotEmpty()) pendingText.append(' ')
        pendingText.append(text.trim())
    }

    @Synchronized
    fun logTool(name: String, args: JSONObject = JSONObject()) {
        if (closed) return
        flushPending()
        val ts = timeFmt.format(Date())
        val paramStr = if (args.length() > 0) " $args" else ""
        append("[$ts] tool: $name$paramStr")
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        flushPending()
        append("---")
        append("ended: ${timestampFmt.format(Date())}")
        Log.i(TAG, "Session log closed: ${file.name}")
    }

    private fun flushPending() {
        val speaker = pendingSpeaker ?: return
        val ts = pendingTimestamp ?: return
        if (pendingText.isNotEmpty()) {
            append("[$ts] $speaker: $pendingText")
            pendingText.clear()
        }
        pendingSpeaker = null
        pendingTimestamp = null
    }

    private fun append(line: String) {
        try {
            FileWriter(file, true).use { it.appendLine(line) }
        } catch (e: Exception) {
            Log.w(TAG, "Write failed: ${e.message}")
        }
    }

}
