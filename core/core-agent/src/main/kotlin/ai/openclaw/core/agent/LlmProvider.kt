package ai.openclaw.core.agent

import ai.openclaw.core.model.AcpRuntimeEvent
import kotlinx.coroutines.flow.Flow

/**
 * Sealed class for events streamed from an LLM provider.
 */
sealed class LlmStreamEvent {
    data class TextDelta(val text: String) : LlmStreamEvent()
    data class ThinkingDelta(val text: String) : LlmStreamEvent()
    data class ToolUse(
        val id: String,
        val name: String,
        val input: String,
    ) : LlmStreamEvent()
    data class Usage(
        val inputTokens: Int,
        val outputTokens: Int,
        val cacheRead: Int = 0,
        val cacheWrite: Int = 0,
    ) : LlmStreamEvent()
    data class Done(val stopReason: String) : LlmStreamEvent()
    data class Error(val message: String, val code: String? = null, val retryable: Boolean = false) : LlmStreamEvent()
}

/**
 * A message in the conversation history sent to the LLM.
 */
data class LlmMessage(
    val role: Role,
    val content: String,
    val name: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<LlmToolCall>? = null,
) {
    enum class Role { SYSTEM, USER, ASSISTANT, TOOL }
}

data class LlmToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

/**
 * Tool definition sent to the LLM.
 */
data class LlmToolDefinition(
    val name: String,
    val description: String,
    val parameters: String, // JSON Schema as string
)

/**
 * Request to an LLM provider.
 */
data class LlmRequest(
    val model: String,
    val messages: List<LlmMessage>,
    val tools: List<LlmToolDefinition> = emptyList(),
    val maxTokens: Int = 4096,
    val temperature: Double? = null,
    val systemPrompt: String? = null,
    /** When set, enables extended thinking with this token budget (Anthropic only). */
    val thinkingBudget: Int? = null,
)

/**
 * Interface for LLM provider implementations.
 */
interface LlmProvider {
    /** Provider identifier (e.g., "anthropic", "openai"). */
    val id: String

    /** Stream a completion from the LLM. */
    fun streamCompletion(request: LlmRequest): Flow<LlmStreamEvent>

    /** Check if a model ID belongs to this provider. */
    fun supportsModel(modelId: String): Boolean
}

/**
 * Interface for agent tools that can be invoked during a conversation.
 */
interface AgentTool {
    val name: String
    val description: String
    val parametersSchema: String // JSON Schema

    suspend fun execute(input: String, context: ToolContext): String
}

/**
 * Context provided to tool execution.
 */
data class ToolContext(
    val sessionKey: String,
    val agentId: String,
    val workspaceDir: String? = null,
)
