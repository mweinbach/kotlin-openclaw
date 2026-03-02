package ai.openclaw.runtime.providers

import ai.openclaw.core.agent.LlmProvider
import ai.openclaw.core.agent.LlmRequest
import ai.openclaw.core.agent.LlmStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Registry for LLM providers with model routing and failover.
 */
class ProviderRegistry {
    private val providers = mutableMapOf<String, LlmProvider>()

    fun register(provider: LlmProvider) {
        providers[provider.id] = provider
    }

    fun clear() {
        providers.clear()
    }

    fun ids(): Set<String> = providers.keys

    fun get(providerId: String): LlmProvider? = providers[providerId]

    /**
     * Route a model ID to its provider.
     * Model IDs can be "provider/model" or just "model".
     */
    fun resolveProvider(modelId: String): LlmProvider? {
        // Try explicit provider prefix
        val slashIdx = modelId.indexOf('/')
        if (slashIdx > 0) {
            val providerId = modelId.substring(0, slashIdx)
            return providers[providerId]
        }

        // Try each provider
        return providers.values.firstOrNull { it.supportsModel(modelId) }
    }

    /**
     * Stream completion with failover through a model chain.
     */
    fun streamWithFailover(
        request: LlmRequest,
        fallbacks: List<String> = emptyList(),
    ): Flow<LlmStreamEvent> = flow {
        val models = listOf(request.model) + fallbacks
        var lastError: LlmStreamEvent.Error? = null

        for (model in models) {
            val provider = resolveProvider(model)
            if (provider == null) {
                lastError = LlmStreamEvent.Error("No provider found for model: $model")
                continue
            }

            try {
                var retryableError: LlmStreamEvent.Error? = null
                var completed = false
                provider.streamCompletion(request.copy(model = model)).collect { event ->
                    if (event is LlmStreamEvent.Error && event.retryable && model != models.last()) {
                        retryableError = event
                        return@collect
                    }
                    emit(event)
                    if (event is LlmStreamEvent.Done) {
                        completed = true
                    }
                }

                if (retryableError != null && model != models.last()) {
                    lastError = retryableError
                    continue
                }

                if (completed || retryableError == null) {
                    return@flow
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = LlmStreamEvent.Error(
                    message = "Provider ${provider.id} failed: ${e.message}",
                    retryable = true,
                )
            }
        }

        emit(lastError ?: LlmStreamEvent.Error("All providers failed"))
    }
}
