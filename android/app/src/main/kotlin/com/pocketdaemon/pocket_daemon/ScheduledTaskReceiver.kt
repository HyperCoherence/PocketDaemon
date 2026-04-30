package com.pocketdaemon.pocket_daemon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScheduledTaskReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScheduledTaskReceiver"
        private const val MODEL = "gemini-3.1-pro-preview"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val MAX_TOOL_ROUNDS = 6
    }

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra("task_id") ?: return
        Log.i(TAG, "Alarm fired for task $taskId")

        val app = PocketDaemonApp.instance ?: run {
            Log.w(TAG, "App not initialized, skipping task $taskId")
            return
        }

        val mgr = ScheduledTaskManager(context)
        val task = mgr.findTask(taskId) ?: run {
            Log.w(TAG, "Task $taskId not found")
            return
        }

        if (!task.active) {
            Log.i(TAG, "Task $taskId is inactive, skipping")
            return
        }

        mgr.onTaskFired(taskId)

        app.emitEvent("scheduledTaskFired", mapOf(
            "id" to task.id,
            "description" to task.description,
        ))

        val pendingResult = goAsync()
        Thread({
            try {
                val response = executeTaskPrompt(context, app, task)
                if (response != null) {
                    val noteManager = NoteManager(context)
                    noteManager.save("scheduled:${task.description}", response)
                    app.emitEvent("scheduledTaskResult", mapOf(
                        "id" to task.id,
                        "description" to task.description,
                        "result" to response,
                    ))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Task execution failed for $taskId: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }, "scheduled-task-$taskId").start()
    }

    private fun executeTaskPrompt(context: Context, app: PocketDaemonApp, task: ScheduledTask): String? {
        val apiKey = app.apiKey
        if (apiKey.isBlank()) return null

        return executeTaskPromptWithTools(context, app, task)

        val now = SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a z", Locale.ENGLISH).format(Date())
        val memoryManager = MemoryManager(context)
        val memoryCtx = memoryManager.getSessionContext()
        val locationCtx = app.locationProvider.getLastLocationSummary()

        val skillsBlock = SkillManager(context).getSkillsPromptBlock()
        val systemText = buildString {
            if (memoryCtx.isNotBlank()) {
                append(memoryCtx)
                append("\n---\n")
            }
            append(app.systemPrompt)
            append("\n---\n")
            if (locationCtx != null) {
                append(locationCtx)
                append("\n---\n")
            }
            append("Current date and time: $now\n---\n")
            if (skillsBlock.isNotBlank()) {
                append(skillsBlock)
                append("\n---\n")
            }
            append("This is an automated scheduled task execution. The task was scheduled by ${app.ownerName}. ")
            append("Execute the task and provide a concise result. Do not ask follow-up questions.")
        }

        val url = "$BASE_URL/$MODEL:generateContent?key=$apiKey"
        val body = JSONObject().apply {
            put("systemInstruction", JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text", systemText))))
            put("contents", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", task.prompt)))))
        }

        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            app.httpClient.newCall(request).execute().use { response ->
                val raw = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.e(TAG, "generateContent failed: ${response.code} ${raw.take(500)}")
                    return null
                }
                JSONObject(raw)
                    .optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Task prompt execution error: ${e.message}", e)
            null
        }
    }

    private fun executeTaskPromptWithTools(context: Context, app: PocketDaemonApp, task: ScheduledTask): String? {
        val systemText = buildSystemText(context, app)
        val history = mutableListOf<JSONObject>()
        history.add(JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", task.prompt))))

        val executor = AgentToolExecutor(context, "scheduled:${task.description}", "scheduled")

        repeat(MAX_TOOL_ROUNDS) {
            val responseContent = callGenerateContent(app, systemText, history) ?: return null
            history.add(responseContent)

            val parts = responseContent.optJSONArray("parts") ?: return null
            val functionCalls = mutableListOf<Pair<String, JSONObject>>()
            val textParts = StringBuilder()

            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.has("functionCall")) {
                    val fc = part.getJSONObject("functionCall")
                    functionCalls.add(fc.getString("name") to fc)
                }
                if (part.has("text")) {
                    textParts.append(part.getString("text"))
                }
            }

            if (functionCalls.isEmpty()) {
                return textParts.toString().ifBlank { null }
            }

            val functionResponseParts = JSONArray()
            for ((name, fc) in functionCalls) {
                val args = fc.optJSONObject("args") ?: JSONObject()
                val id = fc.optString("id", "")
                Log.i(TAG, "Scheduled tool call: $name args=$args")
                val result = executor.handle(name, args)
                    ?: JSONObject().put("status", "error").put("error", "tool not available: $name")
                val responseObj = JSONObject()
                    .put("name", name)
                    .put("response", result)
                if (id.isNotBlank()) responseObj.put("id", id)
                functionResponseParts.put(JSONObject().put("functionResponse", responseObj))
            }

            history.add(JSONObject()
                .put("role", "user")
                .put("parts", functionResponseParts))
        }

        Log.w(TAG, "Max scheduled tool rounds reached ($MAX_TOOL_ROUNDS)")
        return null
    }

    private fun buildSystemText(context: Context, app: PocketDaemonApp): String {
        val now = SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a z", Locale.ENGLISH).format(Date())
        val memoryManager = MemoryManager(context)
        val memoryCtx = memoryManager.getSessionContext()
        val locationCtx = app.locationProvider.getLastLocationSummary()
        val skillsBlock = SkillManager(context).getSkillsPromptBlock()

        return buildString {
            if (memoryCtx.isNotBlank()) {
                append(memoryCtx)
                append("\n---\n")
            }
            append(app.systemPrompt)
            append("\n---\n")
            if (locationCtx != null) {
                append(locationCtx)
                append("\n---\n")
            }
            append("Current date and time: $now\n---\n")
            if (skillsBlock.isNotBlank()) {
                append(skillsBlock)
                append("\n---\n")
            }
            append("This is an automated scheduled task execution. The task was scheduled by ${app.ownerName}. ")
            append("Use tools when needed, then provide a concise final result. Do not ask follow-up questions.")
        }
    }

    private fun callGenerateContent(
        app: PocketDaemonApp,
        systemText: String,
        history: List<JSONObject>,
    ): JSONObject? {
        val url = "$BASE_URL/$MODEL:generateContent?key=${app.apiKey}"
        val body = JSONObject().apply {
            put("systemInstruction", JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text", systemText))))
            put("contents", JSONArray().apply { history.forEach { put(it) } })
            val toolsArr = AgentToolRegistry.restTools(
                ownerName = app.ownerName,
                agentType = "scheduled",
                isEnabled = { app.isToolEnabled("scheduled", it) },
                includeGoogleSearch = true,
            )
            if (toolsArr.length() > 0) put("tools", toolsArr)
        }

        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            app.httpClient.newCall(request).execute().use { response ->
                val raw = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.e(TAG, "generateContent failed: ${response.code} ${raw.take(500)}")
                    return null
                }
                JSONObject(raw)
                    .optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Task prompt execution error: ${e.message}", e)
            null
        }
    }
}
