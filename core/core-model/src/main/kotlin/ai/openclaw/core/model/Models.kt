package ai.openclaw.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

// --- Model Provider Types (ported from src/config/types.models.ts) ---

@Serializable
enum class ModelApi {
    @SerialName("openai-completions") OPENAI_COMPLETIONS,
    @SerialName("openai-responses") OPENAI_RESPONSES,
    @SerialName("openai-codex-responses") OPENAI_CODEX_RESPONSES,
    @SerialName("anthropic-messages") ANTHROPIC_MESSAGES,
    @SerialName("google-generative-ai") GOOGLE_GENERATIVE_AI,
    @SerialName("github-copilot") GITHUB_COPILOT,
    @SerialName("bedrock-converse-stream") BEDROCK_CONVERSE_STREAM,
    @SerialName("ollama") OLLAMA,
}

@Serializable
enum class ModelProviderAuthMode {
    @SerialName("api-key") API_KEY,
    @SerialName("aws-sdk") AWS_SDK,
    @SerialName("oauth") OAUTH,
    @SerialName("token") TOKEN,
}

@Serializable
data class ModelCompatConfig(
    val supportsStore: Boolean? = null,
    val supportsDeveloperRole: Boolean? = null,
    val supportsReasoningEffort: Boolean? = null,
    val supportsUsageInStreaming: Boolean? = null,
    val supportsStrictMode: Boolean? = null,
    val maxTokensField: String? = null,
    val thinkingFormat: String? = null,
    val requiresToolResultName: Boolean? = null,
    val requiresAssistantAfterToolResult: Boolean? = null,
    val requiresThinkingAsText: Boolean? = null,
    val requiresMistralToolIds: Boolean? = null,
)

@Serializable
data class ModelCost(
    val input: Double,
    val output: Double,
    val cacheRead: Double,
    val cacheWrite: Double,
)

@Serializable
data class ModelDefinitionConfig(
    val id: String,
    val name: String,
    val api: ModelApi? = null,
    val reasoning: Boolean,
    val input: List<String>,
    val cost: ModelCost,
    val contextWindow: Int,
    val maxTokens: Int,
    val headers: Map<String, String>? = null,
    val compat: ModelCompatConfig? = null,
)

/**
 * Secret input that can be either a plain string (env var reference like "$MY_KEY")
 * or a structured object with env/value/source fields.
 * In TypeScript: `type SecretInput = string | SecretRef`
 */
@Serializable(with = SecretInputSerializer::class)
data class SecretInput(
    val env: String? = null,
    val value: String? = null,
    val source: String? = null,
    val provider: String? = null,
    val id: String? = null,
) {
    companion object {
        fun ofString(s: String): SecretInput = SecretInput(value = s)
        fun ofEnv(envVar: String): SecretInput = SecretInput(env = envVar)
    }
}

object SecretInputSerializer : KSerializer<SecretInput> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SecretInput")

    override fun serialize(encoder: Encoder, value: SecretInput) {
        val jsonEncoder = encoder as JsonEncoder
        // If it's a simple value with no structured fields, serialize as string
        if (value.env == null && value.source == null && value.provider == null && value.id == null && value.value != null) {
            jsonEncoder.encodeJsonElement(JsonPrimitive(value.value))
        } else {
            jsonEncoder.encodeJsonElement(buildJsonObject {
                value.env?.let { put("env", it) }
                value.value?.let { put("value", it) }
                value.source?.let { put("source", it) }
                value.provider?.let { put("provider", it) }
                value.id?.let { put("id", it) }
            })
        }
    }

    override fun deserialize(decoder: Decoder): SecretInput {
        val jsonDecoder = decoder as JsonDecoder
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> {
                val s = element.contentOrNull ?: ""
                if (s.startsWith("\$")) {
                    SecretInput(env = s.removePrefix("\$"))
                } else {
                    SecretInput(value = s)
                }
            }
            is JsonObject -> SecretInput(
                env = element["env"]?.jsonPrimitive?.contentOrNull,
                value = element["value"]?.jsonPrimitive?.contentOrNull,
                source = element["source"]?.jsonPrimitive?.contentOrNull,
                provider = element["provider"]?.jsonPrimitive?.contentOrNull,
                id = element["id"]?.jsonPrimitive?.contentOrNull,
            )
            else -> SecretInput()
        }
    }
}

@Serializable
data class ModelProviderConfig(
    val baseUrl: String,
    val apiKey: SecretInput? = null,
    val auth: ModelProviderAuthMode? = null,
    val api: ModelApi? = null,
    val injectNumCtxForOpenAICompat: Boolean? = null,
    val headers: Map<String, String>? = null,
    val authHeader: Boolean? = null,
    val models: List<ModelDefinitionConfig>,
)

@Serializable
data class BedrockDiscoveryConfig(
    val enabled: Boolean? = null,
    val region: String? = null,
    val providerFilter: List<String>? = null,
    val refreshInterval: Int? = null,
    val defaultContextWindow: Int? = null,
    val defaultMaxTokens: Int? = null,
)

@Serializable
data class ModelsConfig(
    val mode: String? = null,
    val providers: Map<String, ModelProviderConfig>? = null,
    val bedrockDiscovery: BedrockDiscoveryConfig? = null,
)
