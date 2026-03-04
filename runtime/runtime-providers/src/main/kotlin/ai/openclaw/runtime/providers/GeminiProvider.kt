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
import java.util.concurrent.atomic.AtomicLong

/**
 * Google Gemini API provider.
 * Streams responses via SSE from the streamGenerateContent endpoint.
 */
class GeminiProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    private val client: OkHttpClient = OkHttpClient(),
) : LlmProvider {
    private data class InlineImage(
        val mimeType: String,
        val data: String,
    )

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
                    val blocks = msg.normalizedContentBlocks()
                    putJsonArray("parts") {
                        if (msg.toolCallId != null) {
                            // Function response — use the stored tool name, fall back to toolCallId
                            addJsonObject {
                                putJsonObject("functionResponse") {
                                    put("name", msg.name ?: msg.toolCallId ?: "tool_result")
                                    putJsonObject("response") {
                                        put("result", msg.plainTextContent())
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
                            if (blocks.isNotEmpty()) {
                                for (block in blocks) {
                                    when (block) {
                                        is LlmContentBlock.Text -> {
                                            addJsonObject { put("text", block.text) }
                                        }
                                        is LlmContentBlock.ImageUrl -> {
                                            val inlineImage = parseInlineImage(block)
                                            if (inlineImage != null) {
                                                addJsonObject {
                                                    putJsonObject("inlineData") {
                                                        put("mimeType", inlineImage.mimeType)
                                                        put("data", inlineImage.data)
                                                    }
                                                }
                                            } else {
                                                addJsonObject { put("text", "[image] ${block.url}") }
                                            }
                                        }
                                    }
                                }
                            } else if (msg.content.isNotEmpty()) {
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
                                    put("parameters", cleanSchemaForGemini(Json.parseToJsonElement(tool.parameters)))
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

        val response = client.newCall(httpRequest).executeCancellable()
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
        var emittedText = ""
        val collectedToolCalls = mutableListOf<ToolCallSnapshot>()

        for (data in reader.sseEvents()) {
            try {
                val chunk = json.parseToJsonElement(data).jsonObject
                val candidates = chunk["candidates"]?.jsonArray
                if (candidates != null && candidates.isNotEmpty()) {
                    val candidate = candidates[0].jsonObject
                    val content = candidate["content"]?.jsonObject
                    val parts = content?.get("parts")?.jsonArray

                    val chunkTextBuilder = StringBuilder()
                    val chunkToolCalls = mutableListOf<ToolCallSnapshot>()

                    if (parts != null) {
                        for (part in parts) {
                            val partObj = part.jsonObject
                            val text = partObj["text"]?.jsonPrimitive?.contentOrNull
                            if (text != null) {
                                chunkTextBuilder.append(text)
                            }
                            val functionCall = partObj["functionCall"]?.jsonObject
                            if (functionCall != null) {
                                val name = functionCall["name"]?.jsonPrimitive?.contentOrNull ?: ""
                                val args = functionCall["args"]?.toString() ?: "{}"
                                if (name.isNotBlank()) {
                                    chunkToolCalls += ToolCallSnapshot(name = name, args = args)
                                }
                            }
                        }
                    }

                    val chunkText = chunkTextBuilder.toString()
                    if (chunkText.isNotEmpty()) {
                        if (chunkText.startsWith(emittedText)) {
                            val delta = chunkText.substring(emittedText.length)
                            if (delta.isNotEmpty()) emit(LlmStreamEvent.TextDelta(delta))
                            emittedText = chunkText
                        } else {
                            emit(LlmStreamEvent.TextDelta(chunkText))
                            emittedText += chunkText
                        }
                    }

                    if (chunkToolCalls.isNotEmpty()) {
                        if (
                            chunkToolCalls.size >= collectedToolCalls.size &&
                            chunkToolCalls.take(collectedToolCalls.size) == collectedToolCalls
                        ) {
                            collectedToolCalls += chunkToolCalls.drop(collectedToolCalls.size)
                        } else if (collectedToolCalls.isEmpty()) {
                            collectedToolCalls += chunkToolCalls
                        } else {
                            for (call in chunkToolCalls) {
                                if (call !in collectedToolCalls) {
                                    collectedToolCalls += call
                                }
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
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Skip malformed events
            }
        }

        for (toolCall in collectedToolCalls) {
            emit(
                LlmStreamEvent.ToolUse(
                    id = "gemini_${toolCall.name}_${toolCallCounter.incrementAndGet()}",
                    name = toolCall.name,
                    input = toolCall.args,
                ),
            )
        }

        emit(LlmStreamEvent.Done(stopReason = stopReason))
    }

    private data class ToolCallSnapshot(
        val name: String,
        val args: String,
    )

    companion object {
        /** Atomic counter for generating unique tool call IDs across all Gemini requests. */
        private val toolCallCounter = AtomicLong(0)
        private val dataUrlPattern = Regex("^data:([^;,]+)?(;base64),(.+)$", RegexOption.IGNORE_CASE)
        private val geminiUnsupportedSchemaKeywords = setOf(
            "patternProperties",
            "additionalProperties",
            "\$schema",
            "\$id",
            "\$ref",
            "\$defs",
            "definitions",
            "examples",
            "minLength",
            "maxLength",
            "minimum",
            "maximum",
            "multipleOf",
            "pattern",
            "format",
            "minItems",
            "maxItems",
            "uniqueItems",
            "minProperties",
            "maxProperties",
        )
    }

    private fun parseInlineImage(block: LlmContentBlock.ImageUrl): InlineImage? {
        val url = block.url.trim()
        if (!url.startsWith("data:", ignoreCase = true)) return null
        val match = dataUrlPattern.matchEntire(url) ?: return null
        if (match.groupValues[2].lowercase() != ";base64") return null
        val mimeType = block.mimeType?.takeIf { it.isNotBlank() }
            ?: match.groupValues[1].ifBlank { "application/octet-stream" }
        val data = match.groupValues[3].trim()
        if (data.isEmpty()) return null
        return InlineImage(mimeType = mimeType, data = data)
    }

    private fun cleanSchemaForGemini(schema: JsonElement): JsonElement {
        return when (schema) {
            is JsonObject -> cleanSchemaObjectForGemini(schema)
            is JsonArray -> JsonArray(schema.map { cleanSchemaForGemini(it) })
            else -> schema
        }
    }

    private fun cleanSchemaObjectForGemini(schema: JsonObject): JsonElement {
        if (schema["\$ref"] != null) {
            return buildJsonObject {
                copySchemaMeta(schema)
            }
        }

        val cleanedEntries = mutableMapOf<String, JsonElement>()
        for ((key, value) in schema) {
            if (geminiUnsupportedSchemaKeywords.contains(key)) continue

            when {
                key == "const" -> {
                    cleanedEntries["enum"] = JsonArray(listOf(value))
                }
                key == "type" && value is JsonArray -> {
                    val nonNullTypes = value.mapNotNull { it.jsonPrimitive.contentOrNull }.filter { it != "null" }
                    when {
                        nonNullTypes.isEmpty() -> Unit
                        nonNullTypes.size == 1 -> cleanedEntries[key] = JsonPrimitive(nonNullTypes.first())
                        else -> cleanedEntries[key] = JsonArray(nonNullTypes.map { JsonPrimitive(it) })
                    }
                }
                key == "properties" -> {
                    cleanedEntries[key] = if (value is JsonObject) {
                        buildJsonObject {
                            for ((propKey, propValue) in value) {
                                put(propKey, cleanSchemaForGemini(propValue))
                            }
                        }
                    } else {
                        JsonObject(emptyMap())
                    }
                }
                key == "items" -> {
                    cleanedEntries[key] = when (value) {
                        is JsonObject, is JsonArray -> cleanSchemaForGemini(value)
                        else -> value
                    }
                }
                key == "anyOf" || key == "oneOf" || key == "allOf" -> {
                    cleanedEntries[key] = if (value is JsonArray) {
                        JsonArray(value.map { cleanSchemaForGemini(it) })
                    } else {
                        value
                    }
                }
                else -> {
                    cleanedEntries[key] = cleanSchemaForGemini(value)
                }
            }
        }

        var cleaned = JsonObject(cleanedEntries)
        cleaned = simplifyUnionForGemini(cleaned, "anyOf")
        cleaned = simplifyUnionForGemini(cleaned, "oneOf")
        return cleaned
    }

    private fun simplifyUnionForGemini(schema: JsonObject, key: String): JsonObject {
        val variantsRaw = schema[key] as? JsonArray ?: return schema
        val variants = variantsRaw.filterNot { isNullSchemaVariant(it) }
        if (variants.isEmpty()) {
            return JsonObject(schema.filterKeys { it != key })
        }

        val flattenedLiteral = tryFlattenLiteralUnion(variants)
        if (flattenedLiteral != null) {
            return mergeSchemaMeta(schema, flattenedLiteral)
        }

        if (variants.size == 1 && variants.first() is JsonObject) {
            return mergeSchemaMeta(schema, variants.first() as JsonObject)
        }

        val firstObject = variants.firstOrNull { it is JsonObject } as? JsonObject
        if (firstObject != null) {
            val fallback = buildJsonObject {
                firstObject["type"]?.let { put("type", it) }
            }
            return mergeSchemaMeta(schema, fallback)
        }

        return JsonObject(schema.filterKeys { it != key })
    }

    private fun tryFlattenLiteralUnion(variants: List<JsonElement>): JsonObject? {
        if (variants.isEmpty()) return null
        val values = mutableListOf<JsonElement>()
        var commonType: String? = null

        for (variant in variants) {
            val obj = variant as? JsonObject ?: return null
            val value = when {
                obj["const"] != null -> obj["const"]!!
                obj["enum"] is JsonArray && obj["enum"]!!.jsonArray.size == 1 -> obj["enum"]!!.jsonArray.first()
                else -> return null
            }
            val variantType = obj["type"]?.jsonPrimitive?.contentOrNull ?: return null
            if (commonType == null) {
                commonType = variantType
            } else if (commonType != variantType) {
                return null
            }
            values += value
        }

        return buildJsonObject {
            put("type", JsonPrimitive(commonType!!))
            putJsonArray("enum") {
                values.forEach { add(it) }
            }
        }
    }

    private fun isNullSchemaVariant(variant: JsonElement): Boolean {
        val obj = variant as? JsonObject ?: return false
        if (obj["const"] == JsonNull) return true
        val enumArray = obj["enum"] as? JsonArray
        if (enumArray != null && enumArray.size == 1 && enumArray.first() == JsonNull) return true
        val typeElement = obj["type"]
        if (typeElement?.jsonPrimitive?.contentOrNull == "null") return true
        if (typeElement is JsonArray) {
            val values = typeElement.mapNotNull { it.jsonPrimitive.contentOrNull }
            if (values.size == 1 && values.first() == "null") return true
        }
        return false
    }

    private fun mergeSchemaMeta(base: JsonObject, target: JsonObject): JsonObject {
        return buildJsonObject {
            for ((key, value) in target) {
                put(key, value)
            }
            copySchemaMeta(base)
        }
    }

    private fun JsonObjectBuilder.copySchemaMeta(schema: JsonObject) {
        schema["description"]?.let { put("description", it) }
        schema["title"]?.let { put("title", it) }
        schema["default"]?.let { put("default", it) }
    }
}
