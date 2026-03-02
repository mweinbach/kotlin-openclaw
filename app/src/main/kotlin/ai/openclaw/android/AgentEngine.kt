package ai.openclaw.android

import android.content.Context
import ai.openclaw.core.agent.*
import ai.openclaw.core.config.ConfigLoader
import ai.openclaw.core.model.*
import ai.openclaw.core.plugins.PluginRegistry
import ai.openclaw.runtime.engine.AgentRunner
import ai.openclaw.runtime.engine.ToolRegistry
import ai.openclaw.runtime.providers.AnthropicProvider
import ai.openclaw.runtime.providers.GeminiProvider
import ai.openclaw.runtime.providers.OllamaProvider
import ai.openclaw.runtime.providers.OpenAiProvider
import ai.openclaw.runtime.providers.ProviderRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow

/**
 * Central engine wiring all OpenClaw subsystems together.
 */
class AgentEngine(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val configLoader = ConfigLoader()
    val providerRegistry = ProviderRegistry()
    val pluginRegistry = PluginRegistry()
    val toolRegistry = ToolRegistry()

    private var agentRunner: AgentRunner? = null

    var config: OpenClawConfig = OpenClawConfig()
        private set

    /**
     * Load configuration from the app's files directory.
     */
    fun loadConfig(): OpenClawConfig {
        val configDir = context.filesDir.resolve("config")
        val configFile = configDir.resolve("openclaw.json")
        config = if (configFile.exists()) {
            configLoader.parse(configFile.readText())
        } else {
            OpenClawConfig()
        }
        return config
    }

    /**
     * Initialize the engine with loaded config.
     */
    suspend fun initialize() {
        loadConfig()
        registerProviders()
        pluginRegistry.startAll()
    }

    /**
     * Register LLM providers from config.
     */
    private fun registerProviders() {
        val profiles = config.auth?.profiles.orEmpty()

        // Register Anthropic if configured
        val anthropicKey = profiles.values
            .firstOrNull { it.provider == "anthropic" }
            ?.let { resolveApiKey(it) }
            ?: System.getenv("ANTHROPIC_API_KEY")
        if (anthropicKey != null) {
            providerRegistry.register(AnthropicProvider(apiKey = anthropicKey))
        }

        // Register OpenAI if configured
        val openaiKey = profiles.values
            .firstOrNull { it.provider == "openai" }
            ?.let { resolveApiKey(it) }
            ?: System.getenv("OPENAI_API_KEY")
        if (openaiKey != null) {
            providerRegistry.register(OpenAiProvider(apiKey = openaiKey))
        }

        // Register Gemini if configured
        val geminiKey = profiles.values
            .firstOrNull { it.provider == "gemini" || it.provider == "google" }
            ?.let { resolveApiKey(it) }
            ?: System.getenv("GEMINI_API_KEY")
        if (geminiKey != null) {
            providerRegistry.register(GeminiProvider(apiKey = geminiKey))
        }

        // Always register Ollama (no key needed for local)
        providerRegistry.register(OllamaProvider())
    }

    private fun resolveApiKey(profile: AuthProfileConfig): String? {
        // In a full implementation, this would decrypt from EncryptedSharedPreferences
        return null
    }

    /**
     * Send a message and get streamed response events.
     */
    fun sendMessage(
        userMessage: String,
        conversationHistory: List<LlmMessage> = emptyList(),
        model: String? = null,
        systemPrompt: String? = null,
    ): Flow<AcpRuntimeEvent> {
        val effectiveModel = model
            ?: resolveAgentEffectiveModelPrimary(config, DEFAULT_AGENT_ID)
            ?: "claude-sonnet-4-5-20250514"

        val provider = providerRegistry.resolveProvider(effectiveModel)
            ?: providerRegistry.resolveProvider("anthropic/$effectiveModel")
            ?: throw IllegalStateException("No provider found for model: $effectiveModel")

        val runner = AgentRunner(
            provider = provider,
            toolRegistry = toolRegistry,
        )

        val messages = conversationHistory + LlmMessage(
            role = LlmMessage.Role.USER,
            content = userMessage,
        )

        val effectiveSystemPrompt = systemPrompt
            ?: config.agents?.list
                ?.firstOrNull { it.default == true }
                ?.identity?.name?.let { "You are $it." }
            ?: "You are a helpful assistant."

        return runner.runTurn(
            messages = messages,
            model = effectiveModel,
            systemPrompt = effectiveSystemPrompt,
        )
    }

    /**
     * Resolve effective primary model using agent scope logic.
     */
    private fun resolveAgentEffectiveModelPrimary(config: OpenClawConfig, agentId: String): String? {
        val agentConfig = config.agents?.list?.firstOrNull { it.id == agentId }
        return agentConfig?.model?.primary
            ?: config.agents?.defaults?.model?.primary
    }

    /**
     * Shut down the engine gracefully.
     */
    suspend fun shutdown() {
        pluginRegistry.stopAll()
        scope.cancel()
    }
}
