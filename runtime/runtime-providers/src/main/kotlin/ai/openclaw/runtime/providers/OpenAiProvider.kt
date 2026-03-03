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
 * OpenAI Chat Completions API provider.
 * Compatible with OpenAI, Azure OpenAI, and OpenAI-compatible APIs (e.g., OpenRouter).
 */
class OpenAiProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val client: OkHttpClient = OkHttpClient(),
    private val orgId: String? = null,
    private val extraHeaders: Map<String, String> = emptyMap(),
) : LlmProvider {

    override val id = "openai"

    override fun supportsModel(modelId: String): Boolean {
        return modelId.startsWith("openai/") || modelId.startsWith("gpt-") || modelId.startsWith("o1") || modelId.startsWith("o3")
    }

    override fun streamCompletion(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        val modelId = request.model.removePrefix("openai/")

        val messagesArray = buildJsonArray {
            // System prompt as first message
            if (request.systemPrompt != null) {
                addJsonObject {
                    put("role", "system")
                    put("content", request.systemPrompt)
                }
            }
            for (msg in request.messages) {
                if (msg.role == LlmMessage.Role.SYSTEM) continue
                addJsonObject {
                    put("role", when (msg.role) {
                        LlmMessage.Role.USER -> "user"
                        LlmMessage.Role.ASSISTANT -> "assistant"
                        LlmMessage.Role.TOOL -> "tool"
                        else -> "user"
                    })
                    val blocks = msg.normalizedContentBlocks()
                    if (msg.role == LlmMessage.Role.USER && blocks.isNotEmpty()) {
                        putJsonArray("content") {
                            for (block in blocks) {
                                when (block) {
                                    is LlmContentBlock.Text -> {
                                        addJsonObject {
                                            put("type", "text")
                                            put("text", block.text)
                                        }
                                    }
                                    is LlmContentBlock.ImageUrl -> {
                                        addJsonObject {
                                            put("type", "image_url")
                                            putJsonObject("image_url") {
                                                put("url", block.url)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        put("content", msg.plainTextContent())
                    }
                    if (msg.toolCallId != null) {
                        put("tool_call_id", msg.toolCallId)
                    }
                    val toolCalls = msg.toolCalls
                    if (!toolCalls.isNullOrEmpty()) {
                        putJsonArray("tool_calls") {
                            for (tc in toolCalls) {
                                addJsonObject {
                                    put("id", tc.id)
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", tc.name)
                                        put("arguments", tc.arguments)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val body = buildJsonObject {
            put("model", modelId)
            put("messages", messagesArray)
            put("stream", true)
            put("stream_options", buildJsonObject { put("include_usage", true) })
            if (request.maxTokens > 0) {
                put("max_tokens", request.maxTokens)
            }
            if (request.temperature != null) {
                put("temperature", request.temperature)
            }
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    for (tool in request.tools) {
                        addJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", Json.parseToJsonElement(tool.parameters))
                            }
                        }
                    }
                }
            }
        }

        val requestBuilder = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))

        if (orgId != null) {
            requestBuilder.header("OpenAI-Organization", orgId)
        }
        for ((header, value) in extraHeaders) {
            if (header.isNotBlank() && value.isNotBlank()) {
                requestBuilder.header(header, value)
            }
        }

        val response = client.newCall(requestBuilder.build()).execute()
        try {
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                emit(LlmStreamEvent.Error(
                    message = "OpenAI API error ${response.code}: $errorBody",
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
        // Track tool calls across deltas
        val toolCallBuilders = mutableMapOf<Int, ToolCallBuilder>()
        var stopReason: String? = null

        for (data in reader.sseEvents()) {
            try {
                val chunk = json.parseToJsonElement(data).jsonObject
                val choices = chunk["choices"]?.jsonArray
                if (choices != null && choices.isNotEmpty()) {
                    val choice = choices[0].jsonObject
                    val delta = choice["delta"]?.jsonObject
                    val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull

                    if (delta != null) {
                        // Text content
                        val content = delta["content"]?.jsonPrimitive?.contentOrNull
                        if (content != null) {
                            emit(LlmStreamEvent.TextDelta(content))
                        }

                        // Tool calls
                        val toolCalls = delta["tool_calls"]?.jsonArray
                        if (toolCalls != null) {
                            for (tc in toolCalls) {
                                val tcObj = tc.jsonObject
                                val index = tcObj["index"]?.jsonPrimitive?.intOrNull ?: 0
                                val builder = toolCallBuilders.getOrPut(index) { ToolCallBuilder() }
                                tcObj["id"]?.jsonPrimitive?.contentOrNull?.let { builder.id = it }
                                val fn = tcObj["function"]?.jsonObject
                                fn?.get("name")?.jsonPrimitive?.contentOrNull?.let { builder.name = it }
                                fn?.get("arguments")?.jsonPrimitive?.contentOrNull?.let { builder.arguments.append(it) }
                            }
                        }
                    }

                    if (finishReason != null) {
                        stopReason = finishReason
                    }
                }

                // Usage info
                val usage = chunk["usage"]?.jsonObject
                if (usage != null) {
                    emit(LlmStreamEvent.Usage(
                        inputTokens = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                        outputTokens = usage["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                    ))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Skip malformed SSE events
            }
        }

        // Emit accumulated tool calls
        for ((index, builder) in toolCallBuilders.entries.sortedBy { it.key }) {
            if (builder.name.isNotEmpty()) {
                emit(LlmStreamEvent.ToolUse(
                    id = builder.id.ifBlank { "call_auto_${index + 1}" },
                    name = builder.name,
                    input = builder.arguments.toString().ifEmpty { "{}" },
                ))
            }
        }

        emit(LlmStreamEvent.Done(stopReason = stopReason ?: "stop"))
    }

    private class ToolCallBuilder {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }
}
