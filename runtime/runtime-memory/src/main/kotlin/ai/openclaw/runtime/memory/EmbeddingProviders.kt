package ai.openclaw.runtime.memory

import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OpenAI-compatible embedding provider.
 * Works with OpenAI, Azure OpenAI, and compatible APIs.
 */
class OpenAiEmbeddingProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "text-embedding-3-small",
    private val client: OkHttpClient = OkHttpClient(),
) : EmbeddingProvider {

    override val id = "openai"

    override suspend fun embed(texts: List<String>): List<FloatArray> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()

        val body = buildJsonObject {
            put("model", model)
            putJsonArray("input") {
                for (text in texts) add(text)
            }
        }

        val request = Request.Builder()
            .url("$baseUrl/embeddings")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
                ?: throw RuntimeException("Empty embedding response")

            if (!response.isSuccessful) {
                throw RuntimeException("Embedding API error ${response.code}: $responseBody")
            }

            val json = Json { ignoreUnknownKeys = true }
            val result = json.parseToJsonElement(responseBody).jsonObject
            val data = result["data"]?.jsonArray
                ?: throw RuntimeException("No data in embedding response")

            data.map { item ->
                val embedding = item.jsonObject["embedding"]?.jsonArray
                    ?: throw RuntimeException("No embedding in data item")
                FloatArray(embedding.size) { i ->
                    embedding[i].jsonPrimitive.float
                }
            }
        }
    }
}

/**
 * Google Gemini embedding provider.
 */
class GeminiEmbeddingProvider(
    private val apiKey: String,
    private val model: String = "text-embedding-004",
    private val client: OkHttpClient = OkHttpClient(),
) : EmbeddingProvider {

    override val id = "gemini"

    override suspend fun embed(texts: List<String>): List<FloatArray> = withContext(Dispatchers.IO) {
        // Gemini batches require individual requests per text
        texts.map { text -> embedSingle(text) }
    }

    private fun embedSingle(text: String): FloatArray {
        val body = buildJsonObject {
            putJsonObject("content") {
                putJsonArray("parts") {
                    addJsonObject { put("text", text) }
                }
            }
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:embedContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
                ?: throw RuntimeException("Empty embedding response")

            if (!response.isSuccessful) {
                throw RuntimeException("Gemini embedding error ${response.code}: $responseBody")
            }

            val json = Json { ignoreUnknownKeys = true }
            val result = json.parseToJsonElement(responseBody).jsonObject
            val embedding = result["embedding"]?.jsonObject
                ?.get("values")?.jsonArray
                ?: throw RuntimeException("No embedding values in response")

            FloatArray(embedding.size) { i -> embedding[i].jsonPrimitive.float }
        }
    }
}
