package com.pocketdaemon.pocket_daemon

import android.util.Log
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaImageBlockParam
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.BetaToolUseBlockParam
import com.anthropic.models.beta.messages.BetaWebFetchTool20260209
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Claude through the Anthropic Java SDK. Uses the beta messages surface so the server-side web
 * search and fetch tools and refusal fallbacks are available.
 */
class AnthropicReasoningClient(
    private val apiKey: String,
    override val model: String,
    private val defaultEffort: String? = null,
) : ReasoningClient {
    companion object {
        private const val TAG = "AnthropicReasoning"

        /** Server-side refusal fallbacks: a declined request is re-run on a suitable model. */
        private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        private const val MAX_CONTINUATIONS = 5
        private const val WEB_TOOL_MAX_USES = 8L

        private val clients = ConcurrentHashMap<String, AnthropicClient>()

        private fun clientFor(apiKey: String): AnthropicClient = clients.getOrPut(apiKey) {
            AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(Duration.ofMinutes(10))
                .maxRetries(2)
                .build()
        }

        /** Fallbacks are documented for the Opus 5 and Fable families; other models send a plain request. */
        private fun supportsFallbacks(model: String): Boolean =
            model.startsWith("claude-fable") || model.startsWith("claude-mythos") || model.startsWith("claude-opus-5")
    }

    override val provider: String = ProviderIds.ANTHROPIC

    override fun generate(request: ReasoningRequest): ReasoningResponse {
        val client = clientFor(apiKey)
        val messages = request.messages.map { AnthropicReasoningFormat.toMessageParam(it) }.toMutableList()
        var continuations = 0
        while (true) {
            val params = buildParams(request, messages)
            val message = try {
                client.beta().messages().create(params)
            } catch (e: AnthropicServiceException) {
                Log.e(TAG, "$model returned ${e.statusCode()}: ${e.message?.take(300)}")
                throw ReasoningException("Claude $model returned ${e.statusCode()}: ${e.message?.take(200)}", e.statusCode(), e)
            } catch (e: Exception) {
                throw ReasoningException("Claude request failed: ${e.message}", cause = e)
            }
            val stop = message.stopReason().map { it.asString() }.orElse(StopReasons.END_TURN)
            if (stop == "pause_turn" && continuations < MAX_CONTINUATIONS) {
                // The server-side web tool loop hit its iteration limit. Re-sending with the partial
                // assistant turn appended makes it resume where it stopped.
                continuations++
                Log.i(TAG, "pause_turn from $model, resuming (continuation $continuations)")
                messages.add(AnthropicReasoningFormat.assistantParam(message))
                continue
            }
            return AnthropicReasoningFormat.toResponse(message, stop)
        }
    }

    private fun buildParams(request: ReasoningRequest, messages: List<BetaMessageParam>): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(request.maxOutputTokens.toLong())
            .thinking(BetaThinkingConfigAdaptive.builder().build())

        // Claude gets a requested JSON schema as an instruction; callers parse the reply leniently.
        val system = if (request.jsonSchema != null) {
            request.system + "\n\nRespond with only a JSON object that matches this JSON schema, with no prose around it:\n" + request.jsonSchema
        } else {
            request.system
        }
        if (system.isNotBlank()) builder.system(system)

        for (message in messages) builder.addMessage(message)
        for (tool in request.tools) builder.addTool(AnthropicReasoningFormat.toTool(tool))
        if (request.webSearch) {
            builder.addTool(BetaWebSearchTool20260209.builder().maxUses(WEB_TOOL_MAX_USES).build())
            builder.addTool(BetaWebFetchTool20260209.builder().maxUses(WEB_TOOL_MAX_USES).build())
        }
        AnthropicReasoningFormat.effortOf(request.effort ?: defaultEffort)?.let { effort ->
            builder.outputConfig(BetaOutputConfig.builder().effort(effort).build())
        }
        if (supportsFallbacks(model)) {
            builder.addBeta(FALLBACK_BETA)
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        return builder.build()
    }
}

/** Translation between the neutral model and the SDK's beta message types. */
object AnthropicReasoningFormat {
    fun effortOf(level: String?): BetaOutputConfig.Effort? = when (level?.trim()?.lowercase()) {
        "low" -> BetaOutputConfig.Effort.LOW
        "medium" -> BetaOutputConfig.Effort.MEDIUM
        "high" -> BetaOutputConfig.Effort.HIGH
        else -> null
    }

    fun toTool(spec: ToolSpec): BetaTool {
        val schema = spec.parameters ?: ReasoningSchemas.emptyObjectSchema()
        val properties = BetaTool.InputSchema.Properties.builder()
        schema.optJSONObject("properties")?.let { props ->
            val keys = props.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                properties.putAdditionalProperty(key, JsonValue.from(toJava(props.get(key))))
            }
        }
        val inputSchema = BetaTool.InputSchema.builder().properties(properties.build())
        schema.optJSONArray("required")?.let { required ->
            inputSchema.required((0 until required.length()).map { required.getString(it) })
        }
        return BetaTool.builder()
            .name(spec.name)
            .description(spec.description)
            .inputSchema(inputSchema.build())
            .build()
    }

    fun toMessageParam(message: ReasoningMessage): BetaMessageParam {
        val role = if (message.role == ReasoningMessage.ASSISTANT) {
            BetaMessageParam.Role.ASSISTANT
        } else {
            BetaMessageParam.Role.USER
        }
        val raw = message.raw
        if (message.rawProvider == ProviderIds.ANTHROPIC && raw is List<*>) {
            @Suppress("UNCHECKED_CAST")
            val blocks = raw as List<BetaContentBlockParam>
            return BetaMessageParam.builder().role(role).contentOfBetaContentBlockParams(blocks).build()
        }
        val blocks = message.parts.map { part ->
            when (part) {
                is ReasoningPart.Text -> BetaContentBlockParam.ofText(
                    BetaTextBlockParam.builder().text(part.text).build(),
                )
                is ReasoningPart.Image -> BetaContentBlockParam.ofImage(
                    BetaImageBlockParam.builder()
                        .source(
                            BetaBase64ImageSource.builder()
                                .data(part.base64)
                                .mediaType(mediaTypeOf(part.mimeType))
                                .build(),
                        )
                        .build(),
                )
                is ReasoningPart.ToolCall -> {
                    val input = BetaToolUseBlockParam.Input.builder()
                    val keys = part.args.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        input.putAdditionalProperty(key, JsonValue.from(toJava(part.args.get(key))))
                    }
                    BetaContentBlockParam.ofToolUse(
                        BetaToolUseBlockParam.builder().id(part.id).name(part.name).input(input.build()).build(),
                    )
                }
                is ReasoningPart.ToolResult -> BetaContentBlockParam.ofToolResult(
                    BetaToolResultBlockParam.builder().toolUseId(part.id).content(part.result.toString()).build(),
                )
            }
        }
        return BetaMessageParam.builder().role(role).contentOfBetaContentBlockParams(blocks).build()
    }

    fun assistantParam(message: BetaMessage): BetaMessageParam =
        BetaMessageParam.builder()
            .role(BetaMessageParam.Role.ASSISTANT)
            .contentOfBetaContentBlockParams(message.content().map { it.toParam() })
            .build()

    fun toResponse(message: BetaMessage, stop: String): ReasoningResponse {
        val text = StringBuilder()
        val calls = mutableListOf<ReasoningPart.ToolCall>()
        val neutral = mutableListOf<ReasoningPart>()
        val sources = LinkedHashSet<String>()
        for (block in message.content()) {
            block.text().ifPresent { textBlock ->
                text.append(textBlock.text())
                neutral.add(ReasoningPart.Text(textBlock.text()))
            }
            block.toolUse().ifPresent { use ->
                val call = ReasoningPart.ToolCall(use.id(), use.name(), toJsonObject(use._input()))
                calls.add(call)
                neutral.add(call)
            }
            block.webSearchToolResult().ifPresent { result ->
                result.content().resultBlocks().ifPresent { blocks ->
                    for (hit in blocks) sources.add(hit.url())
                }
            }
        }

        val refusal = if (stop == StopReasons.REFUSAL) {
            message.stopDetails().map { details ->
                listOfNotNull(
                    details.category().map { it.toString() }.orElse(null),
                    details.explanation().orElse(null),
                ).joinToString(": ")
            }.orElse("").ifBlank { "refused" }
        } else {
            null
        }
        val stopReason = when {
            calls.isNotEmpty() -> StopReasons.TOOL_USE
            stop == StopReasons.REFUSAL -> StopReasons.REFUSAL
            stop == StopReasons.MAX_TOKENS -> StopReasons.MAX_TOKENS
            else -> StopReasons.END_TURN
        }
        return ReasoningResponse(
            text = text.toString(),
            toolCalls = calls,
            assistantMessage = ReasoningMessage(
                role = ReasoningMessage.ASSISTANT,
                parts = neutral,
                raw = message.content().map { it.toParam() },
                rawProvider = ProviderIds.ANTHROPIC,
            ),
            stopReason = stopReason,
            sources = sources.toList(),
            refusal = refusal,
        )
    }

    private fun mediaTypeOf(mime: String): BetaBase64ImageSource.MediaType = when (mime.lowercase()) {
        "image/png" -> BetaBase64ImageSource.MediaType.IMAGE_PNG
        "image/gif" -> BetaBase64ImageSource.MediaType.IMAGE_GIF
        "image/webp" -> BetaBase64ImageSource.MediaType.IMAGE_WEBP
        else -> BetaBase64ImageSource.MediaType.IMAGE_JPEG
    }

    /** org.json values to plain Java collections so the SDK's JsonValue can wrap them. */
    fun toJava(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> LinkedHashMap<String, Any?>().also { map ->
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = toJava(value.get(key))
            }
        }
        is JSONArray -> ArrayList<Any?>().also { list ->
            for (i in 0 until value.length()) list.add(toJava(value.get(i)))
        }
        else -> value
    }

    fun toJsonObject(value: JsonValue): JSONObject = try {
        val map = value.convert(Map::class.java)
        if (map == null) JSONObject() else JSONObject(map as Map<*, *>)
    } catch (e: Exception) {
        JSONObject()
    }
}
