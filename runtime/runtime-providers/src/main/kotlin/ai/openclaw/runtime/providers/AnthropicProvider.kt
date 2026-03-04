package ai.openclaw.runtime.providers

import ai.openclaw.core.agent.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader

/**
 * Anthropic Messages API provider.
 * Streams responses via SSE from POST /v1/messages.
 */
class AnthropicProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://api.anthropic.com",
    private val client: OkHttpClient = OkHttpClient(),
) : LlmProvider {
    private data class InlineImage(
        val mediaType: String,
        val data: String,
    )

    override val id = "anthropic"

    override fun supportsModel(modelId: String): Boolean {
        return modelId.startsWith("anthropic/") || modelId.startsWith("claude-")
    }

    override fun streamCompletion(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        val modelId = request.model.removePrefix("anthropic/")

        val messagesArray = buildJsonArray {
            for (msg in request.messages) {
                if (msg.role == LlmMessage.Role.SYSTEM) continue
                addJsonObject {
                    put("role", when (msg.role) {
                        LlmMessage.Role.ASSISTANT -> "assistant"
                        else -> "user"
                    })
                    val blocks = msg.normalizedContentBlocks()
                    // Build content blocks
                    val toolCalls = msg.toolCalls
                    if (msg.toolCallId != null) {
                        // Tool result message
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", msg.toolCallId)
                                put("content", msg.plainTextContent())
                            }
                        }
                    } else if (!toolCalls.isNullOrEmpty()) {
                        // Assistant message with tool calls
                        putJsonArray("content") {
                            if (blocks.isNotEmpty()) {
                                for (block in blocks) {
                                    when (block) {
                                        is LlmContentBlock.Text -> {
                                            addJsonObject {
                                                put("type", "text")
                                                put("text", block.text)
                                            }
                                        }
                                        is LlmContentBlock.ImageUrl -> {
                                            val inlineImage = parseInlineImage(block)
                                            if (inlineImage != null) {
                                                addJsonObject {
                                                    put("type", "image")
                                                    putJsonObject("source") {
                                                        put("type", "base64")
                                                        put("media_type", inlineImage.mediaType)
                                                        put("data", inlineImage.data)
                                                    }
                                                }
                                            } else {
                                                addJsonObject {
                                                    put("type", "text")
                                                    put("text", "[image] ${block.url}")
                                                }
                                            }
                                        }
                                    }
                                }
                            } else if (msg.content.isNotEmpty()) {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", msg.content)
                                }
                            }
                            for (tc in toolCalls) {
                                addJsonObject {
                                    put("type", "tool_use")
                                    put("id", tc.id)
                                    put("name", tc.name)
                                    put("input", Json.parseToJsonElement(tc.arguments))
                                }
                            }
                        }
                    } else {
                        if (blocks.isNotEmpty()) {
                            putJsonArray("content") {
                                for (block in blocks) {
                                    when (block) {
                                        is LlmContentBlock.Text -> addJsonObject {
                                            put("type", "text")
                                            put("text", block.text)
                                        }
                                        is LlmContentBlock.ImageUrl -> addJsonObject {
                                            val inlineImage = parseInlineImage(block)
                                            if (inlineImage != null) {
                                                put("type", "image")
                                                putJsonObject("source") {
                                                    put("type", "base64")
                                                    put("media_type", inlineImage.mediaType)
                                                    put("data", inlineImage.data)
                                                }
                                            } else {
                                                put("type", "text")
                                                put("text", "[image] ${block.url}")
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            put("content", msg.content)
                        }
                    }
                }
            }
        }

        val thinkingBudget = request.thinkingBudget

        val body = buildJsonObject {
            put("model", modelId)
            put("max_tokens", request.maxTokens)
            put("messages", messagesArray)
            put("stream", true)
            // System prompt with prompt caching
            if (request.systemPrompt != null) {
                putJsonArray("system") {
                    addJsonObject {
                        put("type", "text")
                        put("text", request.systemPrompt)
                        putJsonObject("cache_control") {
                            put("type", "ephemeral")
                        }
                    }
                }
            }
            // Extended thinking support
            if (thinkingBudget != null && thinkingBudget > 0) {
                putJsonObject("thinking") {
                    put("type", "enabled")
                    put("budget_tokens", thinkingBudget)
                }
                // Temperature must not be set when thinking is enabled
            } else if (request.temperature != null) {
                put("temperature", request.temperature)
            }
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    for (tool in request.tools) {
                        addJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", Json.parseToJsonElement(tool.parameters))
                        }
                    }
                }
            }
        }

        val betaFeatures = mutableListOf("prompt-caching-2024-07-31")
        if (thinkingBudget != null && thinkingBudget > 0) {
            betaFeatures.add("interleaved-thinking-2025-05-14")
        }
        val requestBaseUrl = normalizeBaseUrl(baseUrl)

        val httpRequest = Request.Builder()
            .url("$requestBaseUrl/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("anthropic-beta", betaFeatures.joinToString(","))
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(httpRequest).executeCancellable()
        try {
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                emit(LlmStreamEvent.Error(
                    message = "Anthropic API error ${response.code}: $errorBody",
                    code = response.code.toString(),
                    retryable = response.code in listOf(429, 500, 502, 503),
                ))
                return@flow
            }

            val reader = response.body?.charStream()?.buffered()
                ?: run {
                    emit(LlmStreamEvent.Error(message = "Empty response body"))
                    return@flow
                }

            try {
                parseSseStream(reader)
            } finally {
                reader.close()
            }
        } finally {
            response.close()
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<LlmStreamEvent>.parseSseStream(
        reader: BufferedReader,
    ) {
        val json = Json { ignoreUnknownKeys = true }
        var currentToolId = ""
        var currentToolName = ""
        val toolInputBuffer = StringBuilder()
        var stopReason: String? = null

        for (data in reader.sseEvents()) {
            try {
                val event = json.parseToJsonElement(data).jsonObject
                val type = event["type"]?.jsonPrimitive?.contentOrNull

                when (type) {
                    "content_block_start" -> {
                        val contentBlock = event["content_block"]?.jsonObject
                        val blockType = contentBlock?.get("type")?.jsonPrimitive?.contentOrNull
                        if (blockType == "tool_use") {
                            currentToolId = contentBlock["id"]?.jsonPrimitive?.contentOrNull ?: ""
                            currentToolName = contentBlock["name"]?.jsonPrimitive?.contentOrNull ?: ""
                            toolInputBuffer.clear()
                        }
                    }
                    "content_block_delta" -> {
                        val delta = event["delta"]?.jsonObject
                        val deltaType = delta?.get("type")?.jsonPrimitive?.contentOrNull
                        when (deltaType) {
                            "text_delta" -> {
                                val text = delta["text"]?.jsonPrimitive?.contentOrNull ?: ""
                                if (text.isNotEmpty()) {
                                    emit(LlmStreamEvent.TextDelta(text))
                                }
                            }
                            "input_json_delta" -> {
                                val partial = delta["partial_json"]?.jsonPrimitive?.contentOrNull ?: ""
                                toolInputBuffer.append(partial)
                            }
                            "thinking" -> {
                                val thinking = delta["thinking"]?.jsonPrimitive?.contentOrNull ?: ""
                                if (thinking.isNotEmpty()) {
                                    emit(LlmStreamEvent.ThinkingDelta(thinking))
                                }
                            }
                        }
                    }
                    "content_block_stop" -> {
                        if (currentToolName.isNotEmpty()) {
                            emit(LlmStreamEvent.ToolUse(
                                id = currentToolId,
                                name = currentToolName,
                                input = toolInputBuffer.toString().ifEmpty { "{}" },
                            ))
                            currentToolId = ""
                            currentToolName = ""
                            toolInputBuffer.clear()
                        }
                    }
                    "message_start" -> {
                        val message = event["message"]?.jsonObject
                        val usage = message?.get("usage")?.jsonObject
                        if (usage != null) {
                            emit(LlmStreamEvent.Usage(
                                inputTokens = usage["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                                outputTokens = usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                                cacheRead = usage["cache_read_input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                                cacheWrite = usage["cache_creation_input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                            ))
                        }
                    }
                    "message_delta" -> {
                        val delta = event["delta"]?.jsonObject
                        stopReason = delta?.get("stop_reason")?.jsonPrimitive?.contentOrNull
                        val usage = event["usage"]?.jsonObject
                        if (usage != null) {
                            emit(LlmStreamEvent.Usage(
                                inputTokens = 0,
                                outputTokens = usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                            ))
                        }
                    }
                    "message_stop" -> {
                        // Final event
                    }
                    "error" -> {
                        val error = event["error"]?.jsonObject
                        val errMsg = error?.get("message")?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                        val errType = error?.get("type")?.jsonPrimitive?.contentOrNull
                        emit(LlmStreamEvent.Error(
                            message = errMsg,
                            code = errType,
                            retryable = errType == "overloaded_error",
                        ))
                        return
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Skip malformed SSE events
            }
        }

        emit(LlmStreamEvent.Done(stopReason = stopReason ?: "end_turn"))
    }

    private fun parseInlineImage(block: LlmContentBlock.ImageUrl): InlineImage? {
        val url = block.url.trim()
        if (!url.startsWith("data:", ignoreCase = true)) return null
        val match = DATA_URL_PATTERN.matchEntire(url) ?: return null
        if (match.groupValues[2].lowercase() != ";base64") return null
        val mediaType = block.mimeType?.takeIf { it.isNotBlank() }
            ?: match.groupValues[1].ifBlank { "application/octet-stream" }
        val data = match.groupValues[3].trim()
        if (data.isEmpty()) return null
        return InlineImage(mediaType = mediaType, data = data)
    }

    companion object {
        private val DATA_URL_PATTERN = Regex("^data:([^;,]+)?(;base64),(.+)$", RegexOption.IGNORE_CASE)

        private fun normalizeBaseUrl(baseUrl: String): String {
            val trimmed = baseUrl.trim().removeSuffix("/")
            return trimmed.replace(Regex("/v1$", RegexOption.IGNORE_CASE), "")
        }
    }
}
