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
 * Ollama local model provider.
 * Uses OpenAI-compatible chat completions endpoint at localhost:11434.
 */
class OllamaProvider(
    private val baseUrl: String = "http://localhost:11434",
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
                    put("role", when (msg.role) {
                        LlmMessage.Role.USER -> "user"
                        LlmMessage.Role.ASSISTANT -> "assistant"
                        LlmMessage.Role.TOOL -> "tool"
                        else -> "user"
                    })
                    put("content", msg.content)
                    if (msg.toolCallId != null) {
                        put("tool_call_id", msg.toolCallId)
                    }
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
                if (request.temperature != null) {
                    put("temperature", request.temperature)
                }
                if (request.maxTokens > 0) {
                    put("num_predict", request.maxTokens)
                }
            }
        }

        val httpRequest = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(httpRequest).execute()
        try {
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                emit(LlmStreamEvent.Error(
                    message = "Ollama API error ${response.code}: $errorBody",
                    code = response.code.toString(),
                    retryable = response.code in listOf(500, 502, 503),
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
        // Ollama's /v1/chat/completions endpoint is OpenAI-compatible
        val json = Json { ignoreUnknownKeys = true }
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
                        val content = delta["content"]?.jsonPrimitive?.contentOrNull
                        if (content != null) {
                            emit(LlmStreamEvent.TextDelta(content))
                        }
                    }

                    if (finishReason != null) {
                        stopReason = finishReason
                    }
                }

                val usage = chunk["usage"]?.jsonObject
                if (usage != null) {
                    emit(LlmStreamEvent.Usage(
                        inputTokens = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                        outputTokens = usage["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                    ))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Skip malformed events
            }
        }

        emit(LlmStreamEvent.Done(stopReason = stopReason ?: "stop"))
    }
}
