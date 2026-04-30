package com.pocketdaemon.pocket_daemon

import org.json.JSONArray
import org.json.JSONObject

object AgentToolRegistry {
    const val HANG_UP = "hangUp"
    const val LEAVE_MESSAGE = "leave_message"
    const val SEARCH_MEMORY = "search_memory"
    const val GET_LOCATION = "get_location"
    const val GET_NOTES = "get_notes"
    const val SEARCH_CONTACTS = "search_contacts"
    const val SEND_SMS = "send_sms"
    const val OPEN_MAPS = "open_maps"
    const val PLAY_YOUTUBE = "play_youtube"
    const val ASK_EXPERT = "ask_expert"
    const val ADD_CONTACT = "add_contact"
    const val DIAL_CONTACT = "dial_contact"
    const val DIAL_NUMBER = "dial_number"
    const val END_SESSION = "end_session"
    const val SCHEDULE_TASK = "schedule_task"
    const val LIST_SCHEDULED_TASKS = "list_scheduled_tasks"
    const val CANCEL_SCHEDULED_TASK = "cancel_scheduled_task"
    const val UPDATE_SCHEDULED_TASK = "update_scheduled_task"
    const val TAKE_PHOTO = "take_photo"
    const val USE_SKILL = "use_skill"
    const val GOOGLE_SEARCH = "google_search"

    val AGENT_TOOLS = mapOf(
        "call" to listOf(HANG_UP, LEAVE_MESSAGE, USE_SKILL, GOOGLE_SEARCH),
        "trusted" to listOf(
            HANG_UP,
            LEAVE_MESSAGE,
            SEARCH_MEMORY,
            GET_LOCATION,
            GET_NOTES,
            SEARCH_CONTACTS,
            SEND_SMS,
            LIST_SCHEDULED_TASKS,
            CANCEL_SCHEDULED_TASK,
            UPDATE_SCHEDULED_TASK,
            USE_SKILL,
            GOOGLE_SEARCH,
        ),
        "chat" to listOf(
            LEAVE_MESSAGE,
            SEARCH_MEMORY,
            GET_LOCATION,
            GET_NOTES,
            SEARCH_CONTACTS,
            SEND_SMS,
            OPEN_MAPS,
            PLAY_YOUTUBE,
            ASK_EXPERT,
            ADD_CONTACT,
            DIAL_CONTACT,
            DIAL_NUMBER,
            END_SESSION,
            SCHEDULE_TASK,
            LIST_SCHEDULED_TASKS,
            CANCEL_SCHEDULED_TASK,
            UPDATE_SCHEDULED_TASK,
            TAKE_PHOTO,
            USE_SKILL,
            GOOGLE_SEARCH,
        ),
        "scheduled" to listOf(
            LEAVE_MESSAGE,
            SEARCH_MEMORY,
            GET_LOCATION,
            GET_NOTES,
            SEARCH_CONTACTS,
            ASK_EXPERT,
            USE_SKILL,
            GOOGLE_SEARCH,
        ),
    )

    val ALWAYS_ON_TOOLS = setOf(HANG_UP)

    fun liveDeclarations(ownerName: String, agentType: String): List<GeminiLiveClient.ToolDeclaration> {
        return toolNames(agentType).mapNotNull { name -> declaration(ownerName, agentType, name) }
    }

    fun restTools(
        ownerName: String,
        agentType: String,
        isEnabled: (String) -> Boolean,
        includeGoogleSearch: Boolean,
    ): JSONArray {
        val toolsArr = JSONArray()
        val funcDecls = JSONArray()
        for (name in toolNames(agentType)) {
            if (name == GOOGLE_SEARCH) continue
            if (!isEnabled(name)) continue
            val decl = declaration(ownerName, agentType, name) ?: continue
            val declJson = JSONObject()
                .put("name", decl.name)
                .put("description", decl.description)
            val params = decl.parameters
            if (params != null) declJson.put("parameters", params)
            funcDecls.put(declJson)
        }
        if (funcDecls.length() > 0) {
            toolsArr.put(JSONObject().put("functionDeclarations", funcDecls))
        }
        if (includeGoogleSearch && toolNames(agentType).contains(GOOGLE_SEARCH) && isEnabled(GOOGLE_SEARCH)) {
            toolsArr.put(JSONObject().put("google_search", JSONObject()))
        }
        return toolsArr
    }

    private fun toolNames(agentType: String): List<String> = AGENT_TOOLS[agentType] ?: emptyList()

    private fun declaration(ownerName: String, agentType: String, name: String): GeminiLiveClient.ToolDeclaration? {
        if (name == GOOGLE_SEARCH) return null
        return when (name) {
            HANG_UP -> GeminiLiveClient.ToolDeclaration(
                name = name,
                description = if (agentType == "chat") {
                    "End the current outbound phone call and return to talking with $ownerName. Only works while a call started by dial_contact or dial_number is active."
                } else {
                    "End the current phone call. Use when the conversation is finished, the caller asks to hang up, or continuing is inappropriate."
                },
            )
            LEAVE_MESSAGE -> GeminiLiveClient.ToolDeclaration(
                name = name,
                description = "Save a notification/inbox message for $ownerName. Use for caller messages, call summaries, scheduled task results, or when $ownerName explicitly asks you to remember a short note. Include who said it and any requested follow-up.",
                parameters = objectSchema(
                    "text" to stringSchema("Complete message text to save. Include names, context, and requested action.")
                ),
            )
            SEARCH_MEMORY -> GeminiLiveClient.ToolDeclaration(
                name = name,
                description = "Search $ownerName's long-term memory and prior session logs. Use before saying you do not remember something from past conversations.",
                parameters = objectSchema(
                    "query" to stringSchema("Specific keywords, names, dates, or phrase to search for.")
                ),
            )
            GET_LOCATION -> GeminiLiveClient.ToolDeclaration(
                name = name,
                description = "Get $ownerName's current GPS location. A successful result has status='ok'. If status is 'stale' or 'unavailable', do not present coordinates as current; explain that a fresh location is unavailable.",
            )
            GET_NOTES -> GeminiLiveClient.ToolDeclaration(
                name = name,
                description = "Retrieve saved inbox messages and notes from calls, chats, and scheduled tasks. Use when asked what messages or notes are waiting.",
            )
            SEARCH_CONTACTS -> GeminiLiveClient.ToolDeclaration(
                name = name,
                description = "Search the phone contacts by name or phone number. This only returns matches; it does not call or message anyone.",
                parameters = objectSchema(
                    "query" to stringSchema("Name, partial name, or phone-number fragment to search."),
                    required = listOf("query"),
                    optional = mapOf("limit" to integerSchema("Maximum matches to return, 1-25. Default 10.", 1, 25)),
                ),
            )
            SEND_SMS -> GeminiLiveClient.ToolDeclaration(
                name = name,
                description = "Send an SMS text message from $ownerName's phone. Use only after explicit confirmation of the exact recipient and message. If not confirmed yet, call with confirmed=false to prepare a confirmation summary, then ask the user to confirm.",
                parameters = objectSchema(
                    "message" to stringSchema("Exact SMS body to send."),
                    required = listOf("message"),
                    optional = mapOf(
                        "contact_name" to stringSchema("Recipient contact name. Use search_contacts first if ambiguous."),
                        "phone_number" to stringSchema("Recipient phone number. Required if contact_name is omitted."),
                        "confirmed" to booleanSchema("Set true only after the user explicitly confirms the exact recipient and message."),
                    ),
                ),
            )
            OPEN_MAPS -> GeminiLiveClient.ToolDeclaration(
                name = name,
                description = "Open Google Maps with an address, place, or search query. Set navigate=true for turn-by-turn directions when $ownerName asks to go/drive/navigate somewhere. Navigation replaces any current destination.",
                parameters = objectSchema(
                    "address" to stringSchema("Address, place name, or map search query."),
                    required = listOf("address"),
                    optional = mapOf("navigate" to booleanSchema("true for turn-by-turn navigation; false for map search.")),
                ),
            )
            PLAY_YOUTUBE -> GeminiLiveClient.ToolDeclaration(
                name = name,
                description = "Play a specific YouTube video. Use google_search first to find a direct, age-appropriate video URL. If the daily limit is reached, tell the user the limit is used up.",
                parameters = objectSchema(
                    "url" to stringSchema("Full YouTube video URL."),
                    required = listOf("url"),
                    optional = mapOf("title" to stringSchema("Video title for logging and confirmation.")),
                ),
            )
            ASK_EXPERT -> GeminiLiveClient.ToolDeclaration(
                name = name,
                description = "Consult a stronger model for complex reasoning, planning, math, coding, analysis, or careful second opinions. Do not use for simple questions. For current facts, use google_search first.",
                parameters = objectSchema(
                    "question" to stringSchema("Self-contained question or problem for the expert model.")
                ),
            )
            ADD_CONTACT -> GeminiLiveClient.ToolDeclaration(
                name = name,
                description = "Add a contact to $ownerName's phone. Use only when $ownerName asks to save a contact. Phone numbers should be full international format when possible.",
                parameters = objectSchema(
                    "name" to stringSchema("Contact display name."),
                    "phone" to stringSchema("Phone number to save, preferably in international format."),
                    required = listOf("name", "phone"),
                ),
            )
            DIAL_CONTACT -> GeminiLiveClient.ToolDeclaration(
                name = name,
                description = "Search contacts by name and call the single matching contact. Do not guess: if there are zero or multiple matches, report the matches instead of dialing.",
                parameters = objectSchema(
                    "name" to stringSchema("Contact name to search and dial.")
                ),
            )
            DIAL_NUMBER -> GeminiLiveClient.ToolDeclaration(
                name = name,
                description = "Call a phone number directly. Use only when $ownerName explicitly provides the number or it came from a prior search_contacts/dial_contact result.",
                parameters = objectSchema(
                    "number" to stringSchema("Phone number to dial.")
                ),
            )
            END_SESSION -> GeminiLiveClient.ToolDeclaration(
                name = name,
                description = "End the current chat session. Use when the conversation is complete, $ownerName says goodbye, or there is nothing more to do.",
            )
            SCHEDULE_TASK -> GeminiLiveClient.ToolDeclaration(
                name = name,
                description = "Schedule a future task. If the user gives an absolute date/time, convert it to delayMinutes using the current date/time in the system prompt. The task will run later and save its result as a notification note.",
                parameters = objectSchema(
                    "description" to stringSchema("Short task name shown in the task list."),
                    "prompt" to stringSchema("Full instruction to execute when the task fires. Include all necessary context."),
                    "delayMinutes" to integerSchema("Minutes from now until first execution. Must be > 0.", 1),
                    required = listOf("description", "prompt", "delayMinutes"),
                    optional = mapOf(
                        "recurring" to booleanSchema("Whether this task repeats."),
                        "intervalMinutes" to integerSchema("Minutes between repeats. Required when recurring=true.", 1),
                    ),
                ),
            )
            LIST_SCHEDULED_TASKS -> GeminiLiveClient.ToolDeclaration(
                name = name,
                description = "List scheduled tasks, including inactive/cancelled tasks when requested.",
                parameters = objectSchema(
                    required = emptyList(),
                    optional = mapOf(
                        "includeInactive" to booleanSchema("Include inactive/cancelled tasks. Default false."),
                    ),
                ),
            )
            CANCEL_SCHEDULED_TASK -> GeminiLiveClient.ToolDeclaration(
                name = name,
                description = "Cancel a scheduled task by id. Use list_scheduled_tasks first if the id is unknown or the user describes the task by name.",
                parameters = objectSchema(
                    "id" to stringSchema("Scheduled task id to cancel.")
                ),
            )
            UPDATE_SCHEDULED_TASK -> GeminiLiveClient.ToolDeclaration(
                name = name,
                description = "Update a scheduled task by id. Use list_scheduled_tasks first if the id is unknown. If changing time from an absolute date/time, convert to delayMinutes or nextFireMs using current date/time.",
                parameters = objectSchema(
                    "id" to stringSchema("Scheduled task id to update."),
                    required = listOf("id"),
                    optional = mapOf(
                        "description" to stringSchema("New short task name."),
                        "prompt" to stringSchema("New full execution instruction."),
                        "delayMinutes" to integerSchema("Minutes from now until next execution.", 1),
                        "nextFireMs" to integerSchema("Absolute next fire time in Unix epoch milliseconds.", 1),
                        "active" to booleanSchema("Whether task should be active."),
                        "recurring" to booleanSchema("Whether task should repeat."),
                        "intervalMinutes" to integerSchema("Minutes between repeats.", 1),
                    ),
                ),
            )
            TAKE_PHOTO -> GeminiLiveClient.ToolDeclaration(
                name = name,
                description = "Take a photo using the phone camera and save it. Tell $ownerName before capturing so they can prepare. Supports an optional delay timer.",
                parameters = objectSchema(
                    required = emptyList(),
                    optional = mapOf(
                        "camera" to JSONObject()
                            .put("type", "string")
                            .put("enum", JSONArray().put("back").put("front"))
                            .put("description", "Camera to use. Default back."),
                        "delay_seconds" to integerSchema("Seconds to wait before taking photo, 0-30. Default 0.", 0, 30),
                    ),
                ),
            )
            USE_SKILL -> GeminiLiveClient.ToolDeclaration(
                name = name,
                description = "Load a skill's full instructions and optional live data by folder name. This only loads instructions; after reading the result, continue the task using those instructions.",
                parameters = objectSchema(
                    "name" to stringSchema("Skill folder name from the available skills list.")
                ),
            )
            else -> null
        }
    }

    private fun objectSchema(
        vararg requiredProps: Pair<String, JSONObject>,
        required: List<String> = requiredProps.map { it.first },
        optional: Map<String, JSONObject> = emptyMap(),
    ): JSONObject {
        val props = JSONObject()
        for ((name, schema) in requiredProps) props.put(name, schema)
        for ((name, schema) in optional) props.put(name, schema)
        val req = JSONArray()
        for (name in required) req.put(name)
        return JSONObject()
            .put("type", "object")
            .put("properties", props)
            .put("required", req)
    }

    private fun stringSchema(description: String): JSONObject =
        JSONObject().put("type", "string").put("description", description)

    private fun booleanSchema(description: String): JSONObject =
        JSONObject().put("type", "boolean").put("description", description)

    private fun integerSchema(description: String, min: Int? = null, max: Int? = null): JSONObject {
        val json = JSONObject().put("type", "integer").put("description", description)
        if (min != null) json.put("minimum", min)
        if (max != null) json.put("maximum", max)
        return json
    }
}
