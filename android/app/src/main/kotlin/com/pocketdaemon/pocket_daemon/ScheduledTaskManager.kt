package com.pocketdaemon.pocket_daemon

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ScheduledTask(
    val id: String = UUID.randomUUID().toString(),
    val description: String,
    val prompt: String,
    val recurring: Boolean = false,
    val intervalMinutes: Int? = null,
    val nextFireMs: Long,
    val active: Boolean = true,
    val createdBy: String,
    val createdAt: Long = System.currentTimeMillis(),
)

class ScheduledTaskManager(private val context: Context) {

    companion object {
        private const val TAG = "ScheduledTaskManager"
        private const val FILE_NAME = "scheduled_tasks.json"
    }

    private val file: File
        get() = File(PocketDaemonApp.instance!!.persistentDir, FILE_NAME)

    @Synchronized
    fun schedule(task: ScheduledTask) {
        val tasks = loadAll().toMutableList()
        tasks.add(task)
        save(tasks)
        if (task.active) setAlarm(task)
        Log.i(TAG, "Scheduled task '${task.description}' id=${task.id} fire=${task.nextFireMs} recurring=${task.recurring}")
        PocketDaemonApp.instance?.emitEvent("scheduledTaskAdded", mapOf(
            "id" to task.id,
            "description" to task.description,
        ))
    }

    fun getAll(): List<ScheduledTask> = loadAll()

    fun getAllAsMaps(): List<Map<String, Any?>> = loadAll().map { it.toMap() }

    @Synchronized
    fun setActive(id: String, active: Boolean) {
        val tasks = loadAll().toMutableList()
        val idx = tasks.indexOfFirst { it.id == id }
        if (idx < 0) return
        val old = tasks[idx]
        val updated = old.copy(active = active)
        tasks[idx] = updated
        save(tasks)
        if (active) {
            setAlarm(updated)
        } else {
            cancelAlarm(id)
        }
        Log.i(TAG, "Task $id active=$active")
    }

    @Synchronized
    fun remove(id: String) {
        val tasks = loadAll().toMutableList()
        val removed = tasks.removeAll { it.id == id }
        if (removed) {
            save(tasks)
            cancelAlarm(id)
            Log.i(TAG, "Removed task $id")
        }
    }

    @Synchronized
    fun update(task: ScheduledTask): Boolean {
        val tasks = loadAll().toMutableList()
        val idx = tasks.indexOfFirst { it.id == task.id }
        if (idx < 0) return false
        tasks[idx] = task
        save(tasks)
        cancelAlarm(task.id)
        if (task.active) setAlarm(task)
        Log.i(TAG, "Updated task '${task.description}' id=${task.id} active=${task.active} next=${task.nextFireMs}")
        PocketDaemonApp.instance?.emitEvent("scheduledTaskUpdated", mapOf(
            "id" to task.id,
            "description" to task.description,
            "active" to task.active,
        ))
        return true
    }

    fun rescheduleAll() {
        val tasks = loadAll()
        var count = 0
        for (task in tasks) {
            if (!task.active) continue
            if (!task.recurring && task.nextFireMs < System.currentTimeMillis()) continue
            setAlarm(task)
            count++
        }
        Log.i(TAG, "Rescheduled $count active tasks")
    }

    @Synchronized
    fun onTaskFired(id: String) {
        val tasks = loadAll().toMutableList()
        val idx = tasks.indexOfFirst { it.id == id }
        if (idx < 0) {
            Log.w(TAG, "Fired task $id not found")
            return
        }
        val task = tasks[idx]
        if (task.recurring && task.intervalMinutes != null) {
            val next = System.currentTimeMillis() + task.intervalMinutes.toLong() * 60_000
            val updated = task.copy(nextFireMs = next)
            tasks[idx] = updated
            save(tasks)
            setAlarm(updated)
            Log.i(TAG, "Recurring task $id rescheduled, next=${updated.nextFireMs}")
        } else {
            tasks.removeAt(idx)
            save(tasks)
            Log.i(TAG, "One-time task $id completed and removed")
        }
    }

    fun findTask(id: String): ScheduledTask? = loadAll().find { it.id == id }

    private fun setAlarm(task: ScheduledTask) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = buildPendingIntent(task.id)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.nextFireMs, pi)
            Log.w(TAG, "Exact alarm not permitted, using inexact for ${task.id}")
        } else {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.nextFireMs, pi)
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.nextFireMs, pi)
                Log.w(TAG, "setExact failed, fell back to inexact: ${e.message}")
            }
        }
    }

    private fun cancelAlarm(id: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(buildPendingIntent(id))
    }

    private fun buildPendingIntent(taskId: String): PendingIntent {
        val intent = Intent(context, ScheduledTaskReceiver::class.java).apply {
            action = "com.pocketdaemon.SCHEDULED_TASK"
            putExtra("task_id", taskId)
        }
        return PendingIntent.getBroadcast(
            context,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun loadAll(): List<ScheduledTask> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                ScheduledTask(
                    id = o.getString("id"),
                    description = o.optString("description", ""),
                    prompt = o.optString("prompt", ""),
                    recurring = o.optBoolean("recurring", false),
                    intervalMinutes = if (o.has("intervalMinutes") && !o.isNull("intervalMinutes"))
                        o.getInt("intervalMinutes") else null,
                    nextFireMs = o.optLong("nextFireMs", 0),
                    active = o.optBoolean("active", true),
                    createdBy = o.optString("createdBy", "unknown"),
                    createdAt = o.optLong("createdAt", 0),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load tasks: ${e.message}")
            emptyList()
        }
    }

    private fun save(tasks: List<ScheduledTask>) {
        val arr = JSONArray()
        for (t in tasks) arr.put(t.toJson())
        file.writeText(arr.toString())
    }

    private fun ScheduledTask.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("description", description)
        put("prompt", prompt)
        put("recurring", recurring)
        if (intervalMinutes != null) put("intervalMinutes", intervalMinutes) else put("intervalMinutes", JSONObject.NULL)
        put("nextFireMs", nextFireMs)
        put("active", active)
        put("createdBy", createdBy)
        put("createdAt", createdAt)
    }

    private fun ScheduledTask.toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "description" to description,
        "prompt" to prompt,
        "recurring" to recurring,
        "intervalMinutes" to intervalMinutes,
        "nextFireMs" to nextFireMs,
        "active" to active,
        "createdBy" to createdBy,
        "createdAt" to createdAt,
    )
}
