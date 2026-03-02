package ai.openclaw.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

@Serializable
data class SecretInput(
    val env: String? = null,
    val value: String? = null,
)

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
