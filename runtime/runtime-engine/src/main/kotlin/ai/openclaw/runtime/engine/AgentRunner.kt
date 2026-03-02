package ai.openclaw.runtime.engine

import ai.openclaw.core.agent.*
import ai.openclaw.core.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Tool registry for managing available tools.
 */
class ToolRegistry {
    private val tools = mutableMapOf<String, AgentTool>()

    fun register(tool: AgentTool) {
        tools[tool.name] = tool
    }

    fun get(name: String): AgentTool? = tools[name]

    fun all(): List<AgentTool> = tools.values.toList()

    fun toDefinitions(): List<LlmToolDefinition> = tools.values.map {
        LlmToolDefinition(
            name = it.name,
            description = it.description,
            parameters = it.parametersSchema,
        )
    }
}

/**
 * The embedded agent runner - the core LLM orchestration loop.
 * Receives messages, builds prompts, calls LLM, executes tools, loops until done.
 *
 * Ported from src/agents/pi-embedded-runner/
 */
class AgentRunner(
    private val provider: LlmProvider,
    private val toolRegistry: ToolRegistry = ToolRegistry(),
    private val maxToolRounds: Int = 25,
) {
    /**
     * Run a single turn: message → LLM → tool calls → loop → done.
     * Returns a Flow of AcpRuntimeEvents for streaming.
     */
    fun runTurn(
        messages: List<LlmMessage>,
        model: String,
        systemPrompt: String? = null,
        sessionKey: String = "",
        agentId: String = DEFAULT_AGENT_ID,
    ): Flow<AcpRuntimeEvent> = flow {
        var currentMessages = messages.toMutableList()
        var round = 0

        while (round < maxToolRounds) {
            round++

            val request = LlmRequest(
                model = model,
                messages = currentMessages,
                tools = toolRegistry.toDefinitions(),
                systemPrompt = systemPrompt,
            )

            var hasToolUse = false
            val textBuffer = StringBuilder()

            provider.streamCompletion(request).collect { event ->
                when (event) {
                    is LlmStreamEvent.TextDelta -> {
                        textBuffer.append(event.text)
                        emit(AcpRuntimeEvent.TextDelta(text = event.text))
                    }
                    is LlmStreamEvent.ThinkingDelta -> {
                        emit(AcpRuntimeEvent.TextDelta(
                            text = event.text,
                            stream = AcpRuntimeEvent.StreamType.THOUGHT,
                        ))
                    }
                    is LlmStreamEvent.ToolUse -> {
                        hasToolUse = true
                        emit(AcpRuntimeEvent.ToolCall(
                            text = "Calling ${event.name}",
                            toolCallId = event.id,
                            title = event.name,
                        ))

                        // Execute tool
                        val tool = toolRegistry.get(event.name)
                        val result = if (tool != null) {
                            try {
                                tool.execute(event.input, ToolContext(sessionKey, agentId))
                            } catch (e: Exception) {
                                "Error: ${e.message}"
                            }
                        } else {
                            "Error: Unknown tool '${event.name}'"
                        }

                        // Add assistant message with tool call and tool result
                        currentMessages.add(LlmMessage(
                            role = LlmMessage.Role.ASSISTANT,
                            content = textBuffer.toString(),
                            toolCalls = listOf(LlmToolCall(event.id, event.name, event.input)),
                        ))
                        currentMessages.add(LlmMessage(
                            role = LlmMessage.Role.TOOL,
                            content = result,
                            toolCallId = event.id,
                        ))
                        textBuffer.clear()
                    }
                    is LlmStreamEvent.Usage -> {
                        emit(AcpRuntimeEvent.Status(
                            text = "Tokens: ${event.inputTokens}+${event.outputTokens}",
                            tag = "usage_update",
                        ))
                    }
                    is LlmStreamEvent.Done -> {
                        if (!hasToolUse) {
                            emit(AcpRuntimeEvent.Done(stopReason = event.stopReason))
                        }
                    }
                    is LlmStreamEvent.Error -> {
                        emit(AcpRuntimeEvent.Error(
                            message = event.message,
                            code = event.code,
                            retryable = event.retryable,
                        ))
                        return@flow
                    }
                }
            }

            // If no tool use, we're done
            if (!hasToolUse) return@flow
        }

        // Exceeded max tool rounds
        emit(AcpRuntimeEvent.Error(message = "Exceeded maximum tool call rounds ($maxToolRounds)"))
    }
}
