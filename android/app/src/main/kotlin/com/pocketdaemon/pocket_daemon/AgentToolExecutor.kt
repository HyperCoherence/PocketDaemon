package com.pocketdaemon.pocket_daemon

import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AgentToolExecutor(
    private val context: Context,
    private val source: String,
    private val createdBy: String,
    private val onAppSwitched: (() -> Unit)? = null,
) {
    companion object {
        private const val TAG = "AgentToolExecutor"
        private const val EXPERT_MODEL = "gemini-3.1-pro-preview"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    }

    private val app = PocketDaemonApp.instance!!
    private val noteManager = NoteManager(context)
    private val memoryManager = MemoryManager(context)

    fun handle(name: String, args: JSONObject): JSONObject? {
        return when (name) {
            AgentToolRegistry.LEAVE_MESSAGE -> leaveMessage(args)
            AgentToolRegistry.SEARCH_MEMORY -> searchMemory(args)
            AgentToolRegistry.GET_LOCATION -> app.locationProvider.getLocation()
            AgentToolRegistry.GET_NOTES -> getNotes()
            AgentToolRegistry.SEARCH_CONTACTS -> searchContactsTool(args)
            AgentToolRegistry.SEND_SMS -> sendSms(args)
            AgentToolRegistry.OPEN_MAPS -> openMaps(args)
            AgentToolRegistry.PLAY_YOUTUBE -> playYoutube(args)
            AgentToolRegistry.ASK_EXPERT -> askExpert(args)
            AgentToolRegistry.ADD_CONTACT -> addContact(args)
            AgentToolRegistry.SCHEDULE_TASK -> scheduleTask(args)
            AgentToolRegistry.LIST_SCHEDULED_TASKS -> listScheduledTasks(args)
            AgentToolRegistry.CANCEL_SCHEDULED_TASK -> cancelScheduledTask(args)
            AgentToolRegistry.UPDATE_SCHEDULED_TASK -> updateScheduledTask(args)
            AgentToolRegistry.TAKE_PHOTO -> takePhoto(args)
            AgentToolRegistry.USE_SKILL -> useSkill(args)
            else -> null
        }
    }

    private fun ok(vararg pairs: Pair<String, Any?>): JSONObject {
        val json = JSONObject().put("status", "ok")
        for ((key, value) in pairs) json.put(key, value ?: JSONObject.NULL)
        return json
    }

    private fun error(message: String): JSONObject =
        JSONObject().put("status", "error").put("error", message)

    private fun leaveMessage(args: JSONObject): JSONObject {
        val text = args.optString("text", "")
        return if (text.isNotBlank()) {
            noteManager.save(source, text)
            ok("message" to "saved")
        } else {
            error("text is required")
        }
    }

    private fun searchMemory(args: JSONObject): JSONObject {
        val query = args.optString("query", "")
        return if (query.isBlank()) {
            error("query is required")
        } else {
            ok("results" to memoryManager.searchMemory(query))
        }
    }

    private fun getNotes(): JSONObject {
        val arr = JSONArray()
        for (n in noteManager.getAll()) arr.put(JSONObject(n))
        return ok("notes" to arr)
    }

    private fun searchContactsTool(args: JSONObject): JSONObject {
        val query = args.optString("query", "")
        val limit = args.optInt("limit", 10).coerceIn(1, 25)
        if (query.isBlank()) return error("query is required")

        val arr = JSONArray()
        for ((name, phone) in searchContacts(query, limit)) {
            arr.put(JSONObject().put("name", name).put("phone", phone))
        }
        return ok("matches" to arr, "count" to arr.length())
    }

    private fun openMaps(args: JSONObject): JSONObject {
        val address = args.optString("address", "")
        val navigate = args.optBoolean("navigate", false)
        Log.i(TAG, "Tool: open_maps '$address' navigate=$navigate")
        if (address.isBlank()) return error("address is required")

        val uri = if (navigate) {
            Uri.parse("google.navigation:q=${Uri.encode(address)}")
        } else {
            Uri.parse("geo:0,0?q=${Uri.encode(address)}")
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            onAppSwitched?.invoke()
            ok("message" to if (navigate) "navigating" else "opened", "address" to address)
        } catch (_: Exception) {
            val webUri = Uri.parse("https://www.google.com/maps/search/${Uri.encode(address)}")
            val fallback = Intent(Intent.ACTION_VIEW, webUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(fallback)
                onAppSwitched?.invoke()
                ok("message" to "opened_browser", "address" to address)
            } catch (e2: Exception) {
                error("could not open maps: ${e2.message}")
            }
        }
    }

    private fun playYoutube(args: JSONObject): JSONObject {
        val url = args.optString("url", "")
        val title = args.optString("title", "")
        Log.i(TAG, "Tool: play_youtube '$title' '$url'")
        if (url.isBlank()) return error("url is required")

        val budget = app.configGet("youtubeMinutes", 15L).toInt()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val (fileDate, fileRemaining) = app.readYoutubeRemaining()
        val remaining = when {
            budget <= 0 -> -1
            fileDate != today -> {
                app.writeYoutubeRemaining(today, budget)
                budget
            }
            else -> fileRemaining
        }

        if (budget > 0 && remaining <= 0) {
            return error("YouTube time for today is used up. Ask for more time.")
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage("com.google.android.youtube")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            if (budget > 0) app.launchYoutubeTimer()
            onAppSwitched?.invoke()
            val timeInfo = if (remaining > 0) "$remaining minutes remaining" else "unlimited"
            ok("message" to "playing", "title" to title, "timeLimit" to timeInfo)
        } catch (e: Exception) {
            error("YouTube not available: ${e.message}")
        }
    }

    private fun addContact(args: JSONObject): JSONObject {
        val name = args.optString("name", "")
        val phone = args.optString("phone", "")
        if (name.isBlank() || phone.isBlank()) return error("name and phone are required")

        return try {
            val ops = ArrayList<ContentProviderOperation>()
            ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build())
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build())
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build())
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            ok("message" to "contact saved", "name" to name, "phone" to phone)
        } catch (e: Exception) {
            error("failed to add contact: ${e.message}")
        }
    }

    private fun scheduleTask(args: JSONObject): JSONObject {
        val desc = args.optString("description", "")
        val prompt = args.optString("prompt", "")
        val delayMin = args.optLong("delayMinutes", 0)
        if (desc.isBlank() || prompt.isBlank() || delayMin <= 0) {
            return error("description, prompt, and delayMinutes (>0) are required")
        }

        val recurring = args.optBoolean("recurring", false)
        val interval = if (args.has("intervalMinutes")) args.optInt("intervalMinutes") else null
        if (recurring && (interval == null || interval <= 0)) {
            return error("intervalMinutes (>0) is required when recurring=true")
        }

        val mgr = ScheduledTaskManager(context)
        val task = ScheduledTask(
            description = desc,
            prompt = prompt,
            recurring = recurring,
            intervalMinutes = interval,
            nextFireMs = System.currentTimeMillis() + delayMin * 60_000,
            createdBy = createdBy,
        )
        mgr.schedule(task)
        return ok(
            "message" to "scheduled",
            "id" to task.id,
            "nextFireMs" to task.nextFireMs,
            "recurring" to task.recurring,
            "intervalMinutes" to task.intervalMinutes,
        )
    }

    private fun listScheduledTasks(args: JSONObject): JSONObject {
        val includeInactive = args.optBoolean("includeInactive", false)
        val arr = JSONArray()
        val tasks = ScheduledTaskManager(context).getAll()
            .filter { includeInactive || it.active }
            .sortedWith(compareBy<ScheduledTask> { !it.active }.thenBy { it.nextFireMs })
        for (task in tasks) arr.put(taskJson(task))
        return ok("tasks" to arr, "count" to arr.length())
    }

    private fun cancelScheduledTask(args: JSONObject): JSONObject {
        val id = args.optString("id", "")
        if (id.isBlank()) return error("id is required")
        val mgr = ScheduledTaskManager(context)
        val task = mgr.findTask(id) ?: return error("task not found: $id")
        mgr.setActive(id, false)
        return ok("message" to "cancelled", "task" to taskJson(task.copy(active = false)))
    }

    private fun updateScheduledTask(args: JSONObject): JSONObject {
        val id = args.optString("id", "")
        if (id.isBlank()) return error("id is required")
        val mgr = ScheduledTaskManager(context)
        val old = mgr.findTask(id) ?: return error("task not found: $id")

        val nextFireMs = when {
            args.has("nextFireMs") -> args.optLong("nextFireMs", old.nextFireMs)
            args.has("delayMinutes") -> {
                val delay = args.optLong("delayMinutes", 0)
                if (delay <= 0) return error("delayMinutes must be > 0")
                System.currentTimeMillis() + delay * 60_000
            }
            else -> old.nextFireMs
        }
        val recurring = if (args.has("recurring")) args.optBoolean("recurring") else old.recurring
        val interval = if (args.has("intervalMinutes")) args.optInt("intervalMinutes") else old.intervalMinutes
        if (recurring && (interval == null || interval <= 0)) {
            return error("intervalMinutes (>0) is required when recurring=true")
        }

        val updated = old.copy(
            description = args.optString("description", old.description).ifBlank { old.description },
            prompt = args.optString("prompt", old.prompt).ifBlank { old.prompt },
            recurring = recurring,
            intervalMinutes = interval,
            nextFireMs = nextFireMs,
            active = if (args.has("active")) args.optBoolean("active") else old.active,
        )
        if (!mgr.update(updated)) return error("task not found: $id")
        return ok("message" to "updated", "task" to taskJson(updated))
    }

    private fun takePhoto(args: JSONObject): JSONObject {
        val camera = args.optString("camera", "back")
        val delay = args.optInt("delay_seconds", 0)
        return CameraCapture(context).takePhoto(
            useBackCamera = camera != "front",
            delaySeconds = delay,
        )
    }

    private fun useSkill(args: JSONObject): JSONObject {
        val skillName = args.optString("name", "")
        if (skillName.isBlank()) return error("name is required")
        val content = SkillManager(context).getSkillContent(skillName)
        return if (content != null) {
            ok("content" to content)
        } else {
            error("skill not found: $skillName")
        }
    }

    private fun askExpert(args: JSONObject): JSONObject {
        val question = args.optString("question", "")
        if (question.isBlank()) return error("question is required")

        val url = "$BASE_URL/$EXPERT_MODEL:generateContent?key=${app.apiKey}"
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text", question)))))
            .put("systemInstruction", JSONObject()
                .put("parts", JSONArray().put(JSONObject()
                    .put("text", "You are an expert advisor. Give a direct, thorough answer. No preamble."))))
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            app.httpClient.newCall(request).execute().use { response ->
                val raw = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return error("expert returned ${response.code}: ${raw.take(200)}")
                }
                val text = JSONObject(raw)
                    .optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text", "") ?: ""
                ok("answer" to text)
            }
        } catch (e: Exception) {
            error("expert query failed: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun sendSms(args: JSONObject): JSONObject {
        val message = args.optString("message", "")
        if (message.isBlank()) return error("message is required")

        val resolved = resolveSmsRecipient(args)
        if (resolved.status != null) return resolved.status

        val phone = resolved.phone ?: return error("phone_number or contact_name is required")
        val confirmed = args.optBoolean("confirmed", false)
        if (!confirmed) {
            return JSONObject()
                .put("status", "needs_confirmation")
                .put("message", "Confirm before sending SMS.")
                .put("recipient_name", resolved.name ?: "")
                .put("phone_number", phone)
                .put("sms_text", message)
        }

        return try {
            val sms = SmsManager.getDefault()
            val parts = sms.divideMessage(message)
            if (parts.size > 1) {
                sms.sendMultipartTextMessage(phone, null, parts, null, null)
            } else {
                sms.sendTextMessage(phone, null, message, null, null)
            }
            ok("message" to "sms sent", "recipient_name" to (resolved.name ?: ""), "phone_number" to phone)
        } catch (e: SecurityException) {
            error("SEND_SMS permission denied: ${e.message}")
        } catch (e: Exception) {
            error("failed to send SMS: ${e.message}")
        }
    }

    private data class SmsRecipient(val phone: String? = null, val name: String? = null, val status: JSONObject? = null)

    private fun resolveSmsRecipient(args: JSONObject): SmsRecipient {
        val explicitPhone = args.optString("phone_number", "").ifBlank { args.optString("phone", "") }.trim()
        val contactName = args.optString("contact_name", "").ifBlank { args.optString("name", "") }.trim()
        if (explicitPhone.isNotBlank()) {
            return SmsRecipient(phone = explicitPhone, name = contactName.ifBlank { null })
        }
        if (contactName.isBlank()) return SmsRecipient(status = error("phone_number or contact_name is required"))

        val matches = searchContacts(contactName, 5)
        val exact = matches.filter { it.first.equals(contactName, ignoreCase = true) }
        val candidates = if (exact.isNotEmpty()) exact else matches
        return when {
            candidates.isEmpty() -> SmsRecipient(status = error("no contact found for '$contactName'"))
            candidates.size > 1 -> {
                val arr = JSONArray()
                for ((name, phone) in candidates) arr.put(JSONObject().put("name", name).put("phone", phone))
                SmsRecipient(status = JSONObject()
                    .put("status", "error")
                    .put("error", "multiple contacts match '$contactName'; use search_contacts and specify phone_number")
                    .put("matches", arr))
            }
            else -> SmsRecipient(phone = candidates[0].second, name = candidates[0].first)
        }
    }

    private fun searchContacts(query: String, limit: Int): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
        val selArgs = arrayOf("%$query%", "%$query%")
        try {
            context.contentResolver.query(uri, projection, selection, selArgs, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext() && results.size < limit) {
                    val name = cursor.getString(nameIdx) ?: continue
                    val phone = cursor.getString(numIdx)?.replace("\\s".toRegex(), "") ?: continue
                    val key = "${name.lowercase(Locale.US)}|$phone"
                    if (seen.add(key)) results.add(name to phone)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Contact search failed: ${e.message}")
        }
        return results
    }

    private fun taskJson(task: ScheduledTask): JSONObject = JSONObject()
        .put("id", task.id)
        .put("description", task.description)
        .put("prompt", task.prompt)
        .put("recurring", task.recurring)
        .put("intervalMinutes", task.intervalMinutes ?: JSONObject.NULL)
        .put("nextFireMs", task.nextFireMs)
        .put("active", task.active)
        .put("createdBy", task.createdBy)
        .put("createdAt", task.createdAt)
}
