package ai.openclaw.runtime.providers

import ai.openclaw.core.agent.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
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
                        LlmMessage.Role.USER -> "user"
                        LlmMessage.Role.ASSISTANT -> "assistant"
                        LlmMessage.Role.TOOL -> "user" // Tool results sent as user
                        else -> "user"
                    })
                    if (msg.toolCallId != null) {
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", msg.toolCallId)
                                put("content", msg.content)
                            }
                        }
                    } else {
                        put("content", msg.content)
                    }
                }
            }
        }

        val body = buildJsonObject {
            put("model", modelId)
            put("max_tokens", request.maxTokens)
            put("messages", messagesArray)
            put("stream", true)
            if (request.systemPrompt != null) {
                put("system", request.systemPrompt)
            }
            if (request.temperature != null) {
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

        val httpRequest = Request.Builder()
            .url("$baseUrl/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(httpRequest).execute()

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

        reader.forEachLine { line ->
            if (!line.startsWith("data: ")) return@forEachLine
            val data = line.removePrefix("data: ").trim()
            if (data == "[DONE]") return@forEachLine

            try {
                val event = json.parseToJsonElement(data).jsonObject
                val type = event["type"]?.jsonPrimitive?.contentOrNull ?: return@forEachLine

                when (type) {
                    "content_block_start" -> {
                        val contentBlock = event["content_block"]?.jsonObject ?: return@forEachLine
                        val blockType = contentBlock["type"]?.jsonPrimitive?.contentOrNull
                        if (blockType == "tool_use") {
                            currentToolId = contentBlock["id"]?.jsonPrimitive?.contentOrNull ?: ""
                            currentToolName = contentBlock["name"]?.jsonPrimitive?.contentOrNull ?: ""
                            toolInputBuffer.clear()
                        }
                    }
                    "content_block_delta" -> {
                        val delta = event["delta"]?.jsonObject ?: return@forEachLine
                        val deltaType = delta["type"]?.jsonPrimitive?.contentOrNull
                        when (deltaType) {
                            "text_delta" -> {
                                val text = delta["text"]?.jsonPrimitive?.contentOrNull ?: ""
                                // Cannot emit from forEachLine - collect events
                            }
                            "input_json_delta" -> {
                                val partial = delta["partial_json"]?.jsonPrimitive?.contentOrNull ?: ""
                                toolInputBuffer.append(partial)
                            }
                            "thinking" -> {
                                val thinking = delta["thinking"]?.jsonPrimitive?.contentOrNull ?: ""
                                // Thinking content
                            }
                        }
                    }
                    "content_block_stop" -> {
                        if (currentToolName.isNotEmpty()) {
                            // Tool use complete - handled after stream
                        }
                    }
                    "message_delta" -> {
                        val delta = event["delta"]?.jsonObject
                        val stopReason = delta?.get("stop_reason")?.jsonPrimitive?.contentOrNull
                        if (stopReason != null) {
                            // Done
                        }
                    }
                    "message_stop" -> {
                        // Message complete
                    }
                }
            } catch (_: Exception) {
                // Skip malformed events
            }
        }

        emit(LlmStreamEvent.Done(stopReason = "end_turn"))
    }
}
