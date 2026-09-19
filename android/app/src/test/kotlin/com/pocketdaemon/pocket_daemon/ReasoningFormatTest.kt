package com.pocketdaemon.pocket_daemon

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningFormatTest {

    private val searchTool = ToolSpec(
        name = "search_memory",
        description = "Search memory",
        parameters = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("query", JSONObject().put("type", "string")))
            .put("required", JSONArray().put("query")),
    )

    private val noArgTool = ToolSpec(name = "get_notes", description = "Get notes")

    // ---------------------------------------------------------------- Gemini

    @Test
    fun geminiRequestCarriesSystemToolsSearchAndSchema() {
        val request = ReasoningRequest(
            system = "sys",
            messages = listOf(ReasoningMessage.user("hi")),
            tools = listOf(searchTool, noArgTool),
            webSearch = true,
            jsonSchema = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject().put("facts", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))))
                .put("required", JSONArray().put("facts")),
        )
        val body = GeminiReasoningFormat.buildRequest(request)

        assertEquals("sys", body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        val contents = body.getJSONArray("contents")
        assertEquals("user", contents.getJSONObject(0).getString("role"))

        val tools = body.getJSONArray("tools")
        val declarations = tools.getJSONObject(0).getJSONArray("functionDeclarations")
        assertEquals(2, declarations.length())
        assertEquals("search_memory", declarations.getJSONObject(0).getString("name"))
        assertFalse(declarations.getJSONObject(1).has("parameters"))
        assertTrue(tools.getJSONObject(1).has("google_search"))

        val generation = body.getJSONObject("generationConfig")
        assertEquals("application/json", generation.getString("responseMimeType"))
        val schema = generation.getJSONObject("responseSchema")
        assertEquals("OBJECT", schema.getString("type"))
        assertEquals("ARRAY", schema.getJSONObject("properties").getJSONObject("facts").getString("type"))
        assertEquals("STRING", schema.getJSONObject("properties").getJSONObject("facts").getJSONObject("items").getString("type"))
    }

    @Test
    fun geminiResponseParsesToolCallsAndReplaysRawContent() {
        val content = JSONObject()
            .put("role", "model")
            .put("parts", JSONArray()
                .put(JSONObject().put("text", "Let me check."))
                .put(JSONObject().put("thoughtSignature", "abc").put("functionCall", JSONObject()
                    .put("id", "call-1")
                    .put("name", "search_memory")
                    .put("args", JSONObject().put("query", "dentist")))))
        val json = JSONObject().put("candidates", JSONArray().put(JSONObject()
            .put("content", content)
            .put("finishReason", "STOP")
            .put("groundingMetadata", JSONObject().put("groundingChunks", JSONArray()
                .put(JSONObject().put("web", JSONObject().put("uri", "https://example.com/a")))))))

        val response = GeminiReasoningFormat.parseResponse(json)

        assertEquals(StopReasons.TOOL_USE, response.stopReason)
        assertEquals("Let me check.", response.text)
        assertEquals(1, response.toolCalls.size)
        assertEquals("search_memory", response.toolCalls[0].name)
        assertEquals("dentist", response.toolCalls[0].args.getString("query"))
        assertEquals(listOf("https://example.com/a"), response.sources)

        // The raw model turn (including the thought signature) is replayed verbatim.
        val replayed = GeminiReasoningFormat.toContent(response.assistantMessage)
        assertTrue(replayed.getJSONArray("parts").getJSONObject(1).has("thoughtSignature"))

        // Tool results go back as a user turn with functionResponse parts carrying the id.
        val results = ReasoningMessage.toolResults(listOf(
            ReasoningPart.ToolResult("call-1", "search_memory", JSONObject().put("results", "none")),
        ))
        val resultContent = GeminiReasoningFormat.toContent(results)
        assertEquals("user", resultContent.getString("role"))
        val fr = resultContent.getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse")
        assertEquals("call-1", fr.getString("id"))
        assertEquals("none", fr.getJSONObject("response").getString("results"))
    }

    @Test
    fun geminiSafetyBlockIsReportedAsRefusal() {
        val json = JSONObject().put("candidates", JSONArray().put(JSONObject().put("finishReason", "SAFETY")))
        val response = GeminiReasoningFormat.parseResponse(json)
        assertEquals(StopReasons.REFUSAL, response.stopReason)
        assertNotNull(response.refusal)
    }

    // ---------------------------------------------------------------- OpenAI-compatible (xAI)

    @Test
    fun openAiRequestShapesMessagesToolsAndSchema() {
        val history = listOf(
            ReasoningMessage(ReasoningMessage.USER, listOf(
                ReasoningPart.Image("AAA=", "image/png"),
                ReasoningPart.Text("what is this"),
            )),
            ReasoningMessage(ReasoningMessage.ASSISTANT, listOf(
                ReasoningPart.ToolCall("c1", "search_memory", JSONObject().put("query", "x")),
            )),
            ReasoningMessage.toolResults(listOf(
                ReasoningPart.ToolResult("c1", "search_memory", JSONObject().put("results", "y")),
            )),
        )
        val body = OpenAiReasoningFormat.buildRequest(
            "grok-4.6",
            ReasoningRequest(
                system = "sys",
                messages = history,
                tools = listOf(searchTool, noArgTool),
                jsonSchema = JSONObject().put("type", "object"),
            ),
        )

        assertEquals("grok-4.6", body.getString("model"))
        val messages = body.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        val user = messages.getJSONObject(1)
        assertEquals("user", user.getString("role"))
        val content = user.getJSONArray("content")
        assertEquals("image_url", content.getJSONObject(0).getString("type"))
        assertTrue(content.getJSONObject(0).getJSONObject("image_url").getString("url").startsWith("data:image/png;base64,"))
        assertEquals("what is this", content.getJSONObject(1).getString("text"))

        val assistant = messages.getJSONObject(2)
        assertEquals("assistant", assistant.getString("role"))
        val call = assistant.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("c1", call.getString("id"))
        assertEquals("search_memory", call.getJSONObject("function").getString("name"))
        assertEquals("x", JSONObject(call.getJSONObject("function").getString("arguments")).getString("query"))

        val toolMsg = messages.getJSONObject(3)
        assertEquals("tool", toolMsg.getString("role"))
        assertEquals("c1", toolMsg.getString("tool_call_id"))

        val tools = body.getJSONArray("tools")
        assertEquals("function", tools.getJSONObject(0).getString("type"))
        assertEquals("object", tools.getJSONObject(1).getJSONObject("function").getJSONObject("parameters").getString("type"))
        assertEquals("json_schema", body.getJSONObject("response_format").getString("type"))
    }

    @Test
    fun openAiResponseParsesToolCallsAndReplaysRawMessage() {
        val message = JSONObject()
            .put("role", "assistant")
            .put("content", JSONObject.NULL)
            .put("tool_calls", JSONArray().put(JSONObject()
                .put("id", "call_9")
                .put("type", "function")
                .put("function", JSONObject().put("name", "get_notes").put("arguments", "{}"))))
        val json = JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("message", message)
            .put("finish_reason", "tool_calls")))

        val response = OpenAiReasoningFormat.parseResponse(json, ProviderIds.XAI)

        assertEquals(StopReasons.TOOL_USE, response.stopReason)
        assertEquals("", response.text)
        assertEquals("get_notes", response.toolCalls[0].name)
        assertEquals("call_9", response.toolCalls[0].id)
        val replayed = OpenAiReasoningFormat.toMessages(response.assistantMessage)
        assertEquals(1, replayed.size)
        assertTrue(replayed[0].has("tool_calls"))
    }

    @Test
    fun openAiLengthAndFilterFinishReasons() {
        fun parse(reason: String) = OpenAiReasoningFormat.parseResponse(
            JSONObject().put("choices", JSONArray().put(JSONObject()
                .put("message", JSONObject().put("role", "assistant").put("content", "partial"))
                .put("finish_reason", reason))),
            ProviderIds.XAI,
        )
        assertEquals(StopReasons.MAX_TOKENS, parse("length").stopReason)
        assertEquals(StopReasons.REFUSAL, parse("content_filter").stopReason)
        assertEquals(StopReasons.END_TURN, parse("stop").stopReason)
    }

    // ---------------------------------------------------------------- Anthropic helpers (offline)

    @Test
    fun anthropicEffortAndJavaConversion() {
        assertNotNull(AnthropicReasoningFormat.effortOf("High"))
        assertNull(AnthropicReasoningFormat.effortOf("max"))
        assertNull(AnthropicReasoningFormat.effortOf(null))

        val java = AnthropicReasoningFormat.toJava(
            JSONObject().put("a", JSONArray().put(1).put("x")).put("b", JSONObject.NULL),
        ) as Map<*, *>
        assertEquals(listOf(1, "x"), java["a"])
        assertTrue(java.containsKey("b"))
        assertNull(java["b"])

        val tool = AnthropicReasoningFormat.toTool(searchTool)
        assertEquals("search_memory", tool.name())
    }

    // ---------------------------------------------------------------- shared helpers

    @Test
    fun parseJsonObjectToleratesFencesAndProse() {
        val fenced = "```json\n{\"text\": \"ok\"}\n```"
        assertEquals("ok", ReasoningSchemas.parseJsonObject(fenced)!!.getString("text"))
        val prose = "Here you go: {\"facts\": [\"a\"], \"summary\": \"s\"} hope that helps"
        assertEquals("s", ReasoningSchemas.parseJsonObject(prose)!!.getString("summary"))
        assertNull(ReasoningSchemas.parseJsonObject("no json here"))
    }

    // ---------------------------------------------------------------- Fable answer layout

    @Test
    fun fableAnswerParsesLayoutAndSources() {
        val text = """
            SUMMARY: The store closes at 9 pm tonight.
            DETAILS: Opening hours are 9 am to 9 pm Monday to Saturday.
            It closes at 6 pm on Sundays.
            SOURCES:
            https://example.com/hours
            none
        """.trimIndent()
        val answer = FableAnswer.parse(text, listOf("https://example.com/hours", "https://example.org/other"))
        assertEquals("The store closes at 9 pm tonight.", answer.summary)
        assertTrue(answer.details.startsWith("Opening hours"))
        assertEquals(listOf("https://example.com/hours", "https://example.org/other"), answer.sources)
    }

    @Test
    fun fableAnswerFallsBackWhenLayoutMissing() {
        val text = "First sentence here. Second sentence follows. Third sentence is extra and long enough to be cut."
        val answer = FableAnswer.parse(text)
        assertEquals(text, answer.details)
        assertEquals(text, answer.summary)

        val long = ("Alpha beta gamma delta. " * 30).trim()
        val longAnswer = FableAnswer.parse(long)
        assertTrue(longAnswer.summary.length < long.length)
        assertTrue(longAnswer.summary.startsWith("Alpha beta gamma delta. Alpha beta gamma delta."))
    }

    private operator fun String.times(n: Int): String = repeat(n)
}
