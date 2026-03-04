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
    data class ToolResultTruncationResult(
        val messages: List<LlmMessage>,
        val truncatedCount: Int,
    ) {
        val truncated: Boolean get() = truncatedCount > 0
    }

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
        val maxToolTokens = (remainingBudget * MAX_TOOL_RESULT_CONTEXT_SHARE).toInt()
        val maxToolChars = minOf(
            (maxToolTokens * TOOL_CHARS_PER_TOKEN).toInt(),
            calculateMaxToolResultChars(),
        ).coerceAtLeast(MIN_KEEP_CHARS)

        if (result.length <= maxToolChars) return result

        return truncateToolResultText(result, maxToolChars)
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

    fun calculateMaxToolResultChars(): Int {
        val maxTokens = (maxContextTokens * MAX_TOOL_RESULT_CONTEXT_SHARE).toInt()
        val maxChars = (maxTokens * GENERAL_CHARS_PER_TOKEN).toInt()
        return minOf(maxChars, HARD_MAX_TOOL_RESULT_CHARS)
    }

    fun sessionLikelyHasOversizedToolResults(messages: List<LlmMessage>): Boolean {
        if (messages.isEmpty()) return false
        val maxChars = calculateMaxToolResultChars()
        return messages.any { message ->
            message.role == LlmMessage.Role.TOOL && message.plainTextContent().length > maxChars
        }
    }

    fun truncateOversizedToolResults(messages: List<LlmMessage>): ToolResultTruncationResult {
        if (messages.isEmpty()) {
            return ToolResultTruncationResult(messages = messages, truncatedCount = 0)
        }
        val maxChars = calculateMaxToolResultChars()
        var truncatedCount = 0
        val repaired = messages.map { message ->
            if (message.role != LlmMessage.Role.TOOL) {
                return@map message
            }
            val content = message.plainTextContent()
            if (content.length <= maxChars) {
                return@map message
            }
            truncatedCount += 1
            val truncated = truncateToolResultText(content, maxChars)
            message.copy(
                content = truncated,
                contentBlocks = null,
            )
        }
        return ToolResultTruncationResult(
            messages = if (truncatedCount > 0) repaired else messages,
            truncatedCount = truncatedCount,
        )
    }

    private fun truncateToolResultText(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val budget = (maxChars - TRUNCATION_SUFFIX.length).coerceAtLeast(MIN_KEEP_CHARS)

        if (hasImportantTail(text) && budget > MIN_KEEP_CHARS * 2) {
            val tailBudget = minOf((budget * 0.3).toInt(), 4_000)
            val markerChars = MIDDLE_OMISSION_MARKER.length
            val headBudget = (budget - tailBudget - markerChars).coerceAtLeast(MIN_KEEP_CHARS)
            if (headBudget > MIN_KEEP_CHARS) {
                var headCut = headBudget
                val headNewline = text.lastIndexOf('\n', headBudget)
                if (headNewline > (headBudget * 0.8).toInt()) {
                    headCut = headNewline
                }

                var tailStart = text.length - tailBudget
                val tailNewline = text.indexOf('\n', tailStart)
                if (tailNewline != -1 && tailNewline < tailStart + (tailBudget * 0.2).toInt()) {
                    tailStart = tailNewline + 1
                }
                return text.substring(0, headCut) + MIDDLE_OMISSION_MARKER + text.substring(tailStart) + TRUNCATION_SUFFIX
            }
        }

        var cutPoint = budget
        val lastNewline = text.lastIndexOf('\n', budget)
        if (lastNewline > (budget * 0.8).toInt()) {
            cutPoint = lastNewline
        }
        return text.substring(0, cutPoint) + TRUNCATION_SUFFIX
    }

    private fun hasImportantTail(text: String): Boolean {
        val tail = text.takeLast(2_000).lowercase()
        if (TAIL_ERROR_REGEX.containsMatchIn(tail)) return true
        if (tail.trim().endsWith("}")) return true
        return TAIL_SUMMARY_REGEX.containsMatchIn(tail)
    }

    companion object {
        private const val INPUT_HEADROOM_RATIO = 0.75
        private const val MAX_TOOL_RESULT_CONTEXT_SHARE = 0.3
        private const val GENERAL_CHARS_PER_TOKEN = 4.0
        private const val TOOL_CHARS_PER_TOKEN = 2.0
        private const val HARD_MAX_TOOL_RESULT_CHARS = 400_000
        private const val MIN_KEEP_CHARS = 2_000
        private const val TRUNCATION_SUFFIX =
            "\n\n[truncated: original output exceeded context limit; request specific sections or use offset/limit]"
        private const val MIDDLE_OMISSION_MARKER =
            "\n\n[... middle content omitted; showing head and tail ...]\n\n"
        private val TAIL_ERROR_REGEX =
            Regex("\\b(error|exception|failed|fatal|traceback|panic|stack trace|errno|exit code)\\b")
        private val TAIL_SUMMARY_REGEX =
            Regex("\\b(total|summary|result|complete|finished|done)\\b")
    }
}
