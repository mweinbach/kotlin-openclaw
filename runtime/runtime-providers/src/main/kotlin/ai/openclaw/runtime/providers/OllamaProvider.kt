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
import java.util.UUID

/**
 * Ollama local model provider.
 * Uses native Ollama /api/chat NDJSON streaming endpoint.
 */
class OllamaProvider(
    private val baseUrl: String = "http://localhost:11434",
    private val apiKey: String? = null,
    private val client: OkHttpClient = OkHttpClient(),
) : LlmProvider {

    override val id = "ollama"

    override fun supportsModel(modelId: String): Boolean {
        return modelId.startsWith("ollama/")
    }

    override fun streamCompletion(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        val modelId = request.model.removePrefix("ollama/")

        val messagesArray = buildJsonArray {
            if (request.systemPrompt != null) {
                addJsonObject {
                    put("role", "system")
                    put("content", request.systemPrompt)
                }
            }
            for (msg in request.messages) {
                if (msg.role == LlmMessage.Role.SYSTEM) continue
                addJsonObject {
                    val role = when (msg.role) {
                        LlmMessage.Role.USER -> "user"
                        LlmMessage.Role.ASSISTANT -> "assistant"
                        LlmMessage.Role.TOOL -> "tool"
                        else -> "user"
                    }
                    put("role", role)
                    val blocks = msg.normalizedContentBlocks()
                    val textContent = blocks
                        .filterIsInstance<LlmContentBlock.Text>()
                        .joinToString("\n") { it.text }
                        .ifBlank { msg.plainTextContent() }
                    put("content", textContent)

                    if (role == "user") {
                        val images = blocks
                            .filterIsInstance<LlmContentBlock.ImageUrl>()
                            .map { block -> parseInlineImageData(block.url) ?: block.url }
                        if (images.isNotEmpty()) {
                            putJsonArray("images") {
                                for (image in images) add(image)
                            }
                        }
                    }

                    if (role == "assistant") {
                        val toolCalls = msg.toolCalls
                        if (!toolCalls.isNullOrEmpty()) {
                            putJsonArray("tool_calls") {
                                for (tc in toolCalls) {
                                    addJsonObject {
                                        tc.id.takeIf { it.isNotBlank() }?.let { put("id", it) }
                                        putJsonObject("function") {
                                            put("name", tc.name)
                                            put("arguments", Json.parseToJsonElement(tc.arguments))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (role == "tool") {
                        msg.name?.takeIf { it.isNotBlank() }?.let { put("tool_name", it) }
                    }

                    msg.toolCallId?.let { put("tool_call_id", it) }
                }
            }
        }

        val body = buildJsonObject {
            put("model", modelId)
            put("messages", messagesArray)
            put("stream", true)
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
            putJsonObject("options") {
                put("num_ctx", 65536)
                if (request.temperature != null) {
                    put("temperature", request.temperature)
                }
                if (request.maxTokens > 0) {
                    put("num_predict", request.maxTokens)
                }
            }
        }

        val requestBuilder = Request.Builder()
            .url(resolveChatUrl(baseUrl))
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (!apiKey.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }
        val httpRequest = requestBuilder.build()

        val response = client.newCall(httpRequest).executeCancellable()
        try {
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                emit(
                    LlmStreamEvent.Error(
                        message = "Ollama API error ${response.code}: $errorBody",
                        code = response.code.toString(),
                        retryable = response.code in listOf(500, 502, 503),
                    ),
                )
                return@flow
            }

            val reader = response.body?.charStream()?.buffered()
                ?: run {
                    emit(LlmStreamEvent.Error(message = "Empty response body"))
                    return@flow
                }

            try {
                parseNdjsonStream(reader)
            } finally {
                reader.close()
            }
        } finally {
            response.close()
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<LlmStreamEvent>.parseNdjsonStream(
        reader: BufferedReader,
    ) {
        val json = Json { ignoreUnknownKeys = true }
        val accumulatedToolCalls = mutableListOf<AccumulatedToolCall>()
        var stopReason: String? = null
        var usageInputTokens = 0
        var usageOutputTokens = 0

        while (true) {
            val line = reader.readLine() ?: break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            try {
                val chunk = json.parseToJsonElement(trimmed).jsonObject
                val message = chunk["message"] as? JsonObject

                val content = message
                    ?.get("content")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.takeIf { it.isNotEmpty() }
                if (content != null) {
                    emit(LlmStreamEvent.TextDelta(content))
                }

                val reasoning = message
                    ?.get("reasoning")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.takeIf { it.isNotEmpty() }
                if (reasoning != null && content == null) {
                    emit(LlmStreamEvent.TextDelta(reasoning))
                }

                val toolCalls = message?.get("tool_calls") as? JsonArray
                if (toolCalls != null) {
                    for (toolCall in toolCalls) {
                        val toolCallObject = toolCall as? JsonObject ?: continue
                        val function = toolCallObject["function"] as? JsonObject ?: continue
                        val name = function["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                        if (name.isEmpty()) continue

                        val arguments = function["arguments"]
                        val encodedArguments = when (arguments) {
                            null -> "{}"
                            is JsonPrimitive -> if (arguments.isString) arguments.content else arguments.toString()
                            else -> json.encodeToString(JsonElement.serializer(), arguments)
                        }

                        accumulatedToolCalls += AccumulatedToolCall(
                            id = toolCallObject["id"]?.jsonPrimitive?.contentOrNull,
                            name = name,
                            arguments = encodedArguments.ifBlank { "{}" },
                        )
                    }
                }

                val done = chunk["done"]?.jsonPrimitive?.booleanOrNull == true
                if (done) {
                    stopReason = chunk["done_reason"]?.jsonPrimitive?.contentOrNull
                    usageInputTokens = chunk["prompt_eval_count"]?.jsonPrimitive?.intOrNull ?: 0
                    usageOutputTokens = chunk["eval_count"]?.jsonPrimitive?.intOrNull ?: 0
                    break
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Skip malformed NDJSON lines
            }
        }

        if (usageInputTokens > 0 || usageOutputTokens > 0) {
            emit(
                LlmStreamEvent.Usage(
                    inputTokens = usageInputTokens,
                    outputTokens = usageOutputTokens,
                ),
            )
        }

        accumulatedToolCalls.forEach { toolCall ->
            emit(
                LlmStreamEvent.ToolUse(
                    id = toolCall.id?.takeIf { it.isNotBlank() } ?: "ollama_call_${UUID.randomUUID()}",
                    name = toolCall.name,
                    input = toolCall.arguments,
                ),
            )
        }

        val finalStopReason = stopReason ?: if (accumulatedToolCalls.isNotEmpty()) "tool_calls" else "stop"
        emit(LlmStreamEvent.Done(stopReason = finalStopReason))
    }

    private data class AccumulatedToolCall(
        val id: String?,
        val name: String,
        val arguments: String,
    )

    companion object {
        private val DATA_URL_PATTERN = Regex("^data:([^;,]+)?(;base64),(.+)$", RegexOption.IGNORE_CASE)

        private fun resolveChatUrl(baseUrl: String): String {
            val trimmed = baseUrl.trim().removeSuffix("/")
            val normalizedBase = trimmed.replace(Regex("/v1$", RegexOption.IGNORE_CASE), "")
            val root = if (normalizedBase.isBlank()) "http://localhost:11434" else normalizedBase
            return "$root/api/chat"
        }

        private fun parseInlineImageData(url: String): String? {
            val trimmed = url.trim()
            if (!trimmed.startsWith("data:", ignoreCase = true)) return null
            val match = DATA_URL_PATTERN.matchEntire(trimmed) ?: return null
            if (match.groupValues[2].lowercase() != ";base64") return null
            return match.groupValues[3].trim().ifEmpty { null }
        }
    }
}
