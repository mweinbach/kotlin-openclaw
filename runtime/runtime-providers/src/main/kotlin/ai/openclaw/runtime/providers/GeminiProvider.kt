package ai.openclaw.runtime.providers

import ai.openclaw.core.agent.*
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
 * Google Gemini API provider.
 * Streams responses via SSE from the streamGenerateContent endpoint.
 */
class GeminiProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    private val client: OkHttpClient = OkHttpClient(),
) : LlmProvider {

    override val id = "gemini"

    override fun supportsModel(modelId: String): Boolean {
        return modelId.startsWith("gemini/") || modelId.startsWith("gemini-")
    }

    override fun streamCompletion(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        val modelId = request.model.removePrefix("gemini/")

        val contents = buildJsonArray {
            for (msg in request.messages) {
                if (msg.role == LlmMessage.Role.SYSTEM) continue
                addJsonObject {
                    put("role", when (msg.role) {
                        LlmMessage.Role.USER, LlmMessage.Role.TOOL -> "user"
                        LlmMessage.Role.ASSISTANT -> "model"
                        else -> "user"
                    })
                    putJsonArray("parts") {
                        if (msg.toolCallId != null) {
                            // Function response
                            addJsonObject {
                                putJsonObject("functionResponse") {
                                    put("name", msg.name ?: "tool_result")
                                    putJsonObject("response") {
                                        put("result", msg.content)
                                    }
                                }
                            }
                        } else {
                            val toolCalls = msg.toolCalls
                            if (!toolCalls.isNullOrEmpty()) {
                                for (tc in toolCalls) {
                                    addJsonObject {
                                        putJsonObject("functionCall") {
                                            put("name", tc.name)
                                            put("args", Json.parseToJsonElement(tc.arguments))
                                        }
                                    }
                                }
                            }
                            if (msg.content.isNotEmpty()) {
                                addJsonObject { put("text", msg.content) }
                            }
                        }
                    }
                }
            }
        }

        val body = buildJsonObject {
            put("contents", contents)
            if (request.systemPrompt != null) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        addJsonObject { put("text", request.systemPrompt) }
                    }
                }
            }
            putJsonObject("generationConfig") {
                if (request.maxTokens > 0) {
                    put("maxOutputTokens", request.maxTokens)
                }
                if (request.temperature != null) {
                    put("temperature", request.temperature)
                }
            }
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    addJsonObject {
                        putJsonArray("functionDeclarations") {
                            for (tool in request.tools) {
                                addJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parameters", Json.parseToJsonElement(tool.parameters))
                                }
                            }
                        }
                    }
                }
            }
        }

        val url = "$baseUrl/models/$modelId:streamGenerateContent?alt=sse&key=$apiKey"
        val httpRequest = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(httpRequest).execute()
        try {
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                emit(LlmStreamEvent.Error(
                    message = "Gemini API error ${response.code}: $errorBody",
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
        var stopReason = "stop"

        var line = reader.readLine()
        while (line != null) {
            if (!line.startsWith("data: ")) {
                line = reader.readLine()
                continue
            }
            val data = line.removePrefix("data: ").trim()

            try {
                val chunk = json.parseToJsonElement(data).jsonObject
                val candidates = chunk["candidates"]?.jsonArray
                if (candidates != null && candidates.isNotEmpty()) {
                    val candidate = candidates[0].jsonObject
                    val content = candidate["content"]?.jsonObject
                    val parts = content?.get("parts")?.jsonArray

                    if (parts != null) {
                        for (part in parts) {
                            val partObj = part.jsonObject
                            val text = partObj["text"]?.jsonPrimitive?.contentOrNull
                            if (text != null) {
                                emit(LlmStreamEvent.TextDelta(text))
                            }
                            val functionCall = partObj["functionCall"]?.jsonObject
                            if (functionCall != null) {
                                val name = functionCall["name"]?.jsonPrimitive?.contentOrNull ?: ""
                                val args = functionCall["args"]?.toString() ?: "{}"
                                emit(LlmStreamEvent.ToolUse(
                                    id = "gemini_${name}_${System.currentTimeMillis()}",
                                    name = name,
                                    input = args,
                                ))
                            }
                        }
                    }

                    val finishReason = candidate["finishReason"]?.jsonPrimitive?.contentOrNull
                    if (finishReason != null) {
                        stopReason = finishReason
                    }
                }

                val usageMeta = chunk["usageMetadata"]?.jsonObject
                if (usageMeta != null) {
                    emit(LlmStreamEvent.Usage(
                        inputTokens = usageMeta["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
                        outputTokens = usageMeta["candidatesTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
                    ))
                }
            } catch (_: Exception) {
                // Skip malformed events
            }

            line = reader.readLine()
        }

        emit(LlmStreamEvent.Done(stopReason = stopReason))
    }
}
