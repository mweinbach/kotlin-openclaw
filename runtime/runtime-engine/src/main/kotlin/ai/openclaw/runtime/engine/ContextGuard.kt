package ai.openclaw.runtime.engine

import ai.openclaw.core.agent.LlmMessage

/**
 * Context window guard — prevents overflow by estimating token usage
 * and truncating/compacting when needed.
 *
 * Ported from src/agents/pi-embedded-runner/tool-result-context-guard.ts
 */
class ContextGuard(
    private val maxContextTokens: Int = 128_000,
) {
    /**
     * Estimate token count for a list of messages.
     * Uses conservative 4 chars ≈ 1 token ratio for general text,
     * 2 chars ≈ 1 token for tool results (more compressed).
     */
    fun estimateTokens(messages: List<LlmMessage>): Int {
        var total = 0
        for (msg in messages) {
            val charsPerToken = if (msg.role == LlmMessage.Role.TOOL) TOOL_CHARS_PER_TOKEN else GENERAL_CHARS_PER_TOKEN
            total += (msg.plainTextContent().length / charsPerToken).toInt()
            // Tool calls add schema overhead
            msg.toolCalls?.forEach { tc ->
                total += (tc.arguments.length / TOOL_CHARS_PER_TOKEN).toInt() + 20
            }
        }
        return total
    }

    /**
     * Check if messages fit within context budget.
     */
    fun fitsInContext(messages: List<LlmMessage>, headroomRatio: Double = INPUT_HEADROOM_RATIO): Boolean {
        val budget = (maxContextTokens * headroomRatio).toInt()
        return estimateTokens(messages) <= budget
    }

    /**
     * Guard a single tool result: truncate if it would consume too much context.
     */
    fun guardToolResult(
        result: String,
        currentMessages: List<LlmMessage>,
    ): String {
        val currentTokens = estimateTokens(currentMessages)
        val remainingBudget = (maxContextTokens * INPUT_HEADROOM_RATIO).toInt() - currentTokens
        val maxToolTokens = (remainingBudget * SINGLE_TOOL_RESULT_SHARE).toInt()
        val maxToolChars = (maxToolTokens * TOOL_CHARS_PER_TOKEN).toInt()

        if (result.length <= maxToolChars) return result

        val truncated = result.take(maxToolChars)
        return "$truncated\n\n[truncated: output exceeded context limit, showing first $maxToolChars chars of ${result.length}]"
    }

    /**
     * Trim conversation history to fit in context window.
     * Keeps system prompts and the most recent messages.
     * Groups assistant messages with their following tool result messages so that
     * tool-call / tool-result pairs are never split (splitting would cause API errors).
     */
    fun trimToFit(messages: List<LlmMessage>): List<LlmMessage> {
        if (fitsInContext(messages)) return messages

        val result = mutableListOf<LlmMessage>()
        val systemMessages = messages.filter { it.role == LlmMessage.Role.SYSTEM }
        val nonSystem = messages.filter { it.role != LlmMessage.Role.SYSTEM }

        result.addAll(systemMessages)

        // Group non-system messages into logical units so assistant+tool pairs stay together.
        val groups = groupMessagePairs(nonSystem)

        val budget = (maxContextTokens * INPUT_HEADROOM_RATIO).toInt()
        var tokensUsed = estimateTokens(systemMessages)

        // Add groups from the end until we exceed budget
        val toAdd = mutableListOf<List<LlmMessage>>()
        for (group in groups.reversed()) {
            val groupTokens = estimateTokens(group)
            if (tokensUsed + groupTokens > budget) break
            toAdd.add(0, group)
            tokensUsed += groupTokens
        }

        val keptMessages = toAdd.flatten()
        if (keptMessages.size < nonSystem.size) {
            val dropped = nonSystem.size - keptMessages.size
            result.add(LlmMessage(
                role = LlmMessage.Role.SYSTEM,
                content = "[context compacted: $dropped earlier messages removed to fit context window]",
            ))
        }

        result.addAll(keptMessages)
        return result
    }

    /**
     * Group messages into logical units: an assistant message that has tool calls is
     * grouped with all immediately following TOOL result messages.
     * All other messages form single-element groups.
     */
    private fun groupMessagePairs(messages: List<LlmMessage>): List<List<LlmMessage>> {
        val groups = mutableListOf<List<LlmMessage>>()
        var i = 0
        while (i < messages.size) {
            val msg = messages[i]
            if (msg.role == LlmMessage.Role.ASSISTANT && !msg.toolCalls.isNullOrEmpty()) {
                val group = mutableListOf(msg)
                var j = i + 1
                while (j < messages.size && messages[j].role == LlmMessage.Role.TOOL) {
                    group.add(messages[j])
                    j++
                }
                groups.add(group)
                i = j
            } else {
                groups.add(listOf(msg))
                i++
            }
        }
        return groups
    }

    /**
     * Limit history to N most recent user turns (a turn = user message + assistant response).
     */
    fun limitHistoryTurns(messages: List<LlmMessage>, maxTurns: Int?): List<LlmMessage> {
        if (maxTurns == null || maxTurns <= 0) return messages

        val systemMessages = messages.filter { it.role == LlmMessage.Role.SYSTEM }
        val nonSystem = messages.filter { it.role != LlmMessage.Role.SYSTEM }

        // Count user messages from the end
        var userCount = 0
        var cutoffIndex = nonSystem.size
        for (i in nonSystem.indices.reversed()) {
            if (nonSystem[i].role == LlmMessage.Role.USER) {
                userCount++
                if (userCount > maxTurns) {
                    cutoffIndex = i + 1
                    break
                }
            }
        }

        return systemMessages + nonSystem.subList(cutoffIndex, nonSystem.size)
    }

    companion object {
        private const val INPUT_HEADROOM_RATIO = 0.75
        private const val SINGLE_TOOL_RESULT_SHARE = 0.5
        private const val GENERAL_CHARS_PER_TOKEN = 4.0
        private const val TOOL_CHARS_PER_TOKEN = 2.0
    }
}
