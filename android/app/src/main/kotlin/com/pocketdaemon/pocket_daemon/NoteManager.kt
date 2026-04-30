package com.pocketdaemon.pocket_daemon

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class NoteManager(private val context: Context) {

    companion object {
        private const val TAG = "NoteManager"
        private const val FILE_NAME = "agent_notes.json"
        const val EXTRA_NOTE_ID = "note_id"
    }

    private val file: File
        get() = File(PocketDaemonApp.instance!!.persistentDir, FILE_NAME)

    @Synchronized
    fun save(source: String, text: String) {
        val entries = loadRaw()
        val id = UUID.randomUUID().toString()
        val entry = JSONObject().apply {
            put("id", id)
            put("source", source)
            put("text", text)
            put("timestamp", System.currentTimeMillis())
            put("date", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
        }
        entries.put(entry)
        file.writeText(entries.toString())
        Log.i(TAG, "Note saved from $source (${entries.length()} total)")

        PocketDaemonApp.instance?.emitEvent("noteAdded", mapOf(
            "id" to id,
            "source" to source,
            "text" to text,
            "date" to entry.getString("date"),
        ))

        postNotification(id, source, text)
    }

    private fun postNotification(id: String, source: String, text: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_NOTE_ID, id)
            }
            val pi = PendingIntent.getActivity(
                context, id.hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification = Notification.Builder(context, PocketDaemonApp.CHANNEL_NOTES)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle("Message from $source")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()

            nm.notify(id.hashCode(), notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post notification: ${e.message}")
        }
    }

    @Synchronized
    fun getAll(): List<Map<String, Any?>> {
        val entries = loadRaw()
        val list = mutableListOf<Map<String, Any?>>()
        for (i in (entries.length() - 1) downTo 0) {
            val obj = entries.getJSONObject(i)
            list.add(mapOf(
                "id" to obj.optString("id"),
                "source" to obj.optString("source"),
                "text" to obj.optString("text"),
                "timestamp" to obj.optLong("timestamp"),
                "date" to obj.optString("date"),
            ))
        }
        return list
    }

    @Synchronized
    fun dismiss(id: String): Boolean {
        val entries = loadRaw()
        var found = false
        val updated = JSONArray()
        for (i in 0 until entries.length()) {
            val obj = entries.getJSONObject(i)
            if (obj.optString("id") == id) {
                found = true
            } else {
                updated.put(obj)
            }
        }
        if (found) {
            file.writeText(updated.toString())
            Log.i(TAG, "Note dismissed: $id")
        }
        return found
    }

    private fun loadRaw(): JSONArray {
        return try {
            if (file.exists()) JSONArray(file.readText()) else JSONArray()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load notes: ${e.message}")
            JSONArray()
        }
    }
}
