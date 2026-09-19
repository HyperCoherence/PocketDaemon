package com.pocketdaemon.pocket_daemon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScheduledTaskReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScheduledTaskReceiver"
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

    /** Runs the task prompt through the scheduler role's model with the scheduled tool tier. */
    private fun executeTaskPrompt(context: Context, app: PocketDaemonApp, task: ScheduledTask): String? {
        val client = try {
            ReasoningClients.forRole(app, AgentRoles.SCHEDULER)
        } catch (e: ReasoningException) {
            Log.w(TAG, "Cannot run task ${task.id}: ${e.message}")
            return null
        }
        val executor = AgentToolExecutor(context, "scheduled:${task.description}", "scheduled")
        val isEnabled = { name: String -> app.isToolEnabled("scheduled", name) }
        val history = mutableListOf(ReasoningMessage.user(task.prompt))

        return try {
            ReasoningLoop.run(
                client = client,
                system = buildSystemText(context, app),
                history = history,
                tools = AgentToolRegistry.restToolSpecs(app.ownerName, "scheduled", isEnabled),
                webSearch = AgentToolRegistry.webSearchEnabled("scheduled", isEnabled),
                maxRounds = MAX_TOOL_ROUNDS,
            ) { name, args ->
                executor.handle(name, args)
                    ?: JSONObject().put("status", "error").put("error", "tool not available: $name")
            }
        } catch (e: ReasoningException) {
            Log.e(TAG, "Task prompt execution error: ${e.message}")
            null
        }
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
}
