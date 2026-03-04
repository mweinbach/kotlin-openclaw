package ai.openclaw.runtime.engine

import ai.openclaw.core.agent.LlmMessage
import ai.openclaw.core.agent.LlmToolCall

/**
 * Transcript repair pipeline for strict tool-call/tool-result providers.
 */
class SessionTranscriptRepair {
    data class ToolCallInputRepairReport(
        val messages: List<LlmMessage>,
        val droppedToolCalls: Int,
        val droppedAssistantMessages: Int,
    )

    data class ToolUseRepairReport(
        val messages: List<LlmMessage>,
        val addedSyntheticCount: Int,
        val droppedDuplicateCount: Int,
        val droppedOrphanCount: Int,
        val moved: Boolean,
    )

    fun repairToolCallInputs(
        messages: List<LlmMessage>,
        allowedToolNames: Set<String>? = null,
    ): ToolCallInputRepairReport {
        if (messages.isEmpty()) {
            return ToolCallInputRepairReport(messages = messages, droppedToolCalls = 0, droppedAssistantMessages = 0)
        }
        val normalizedAllowed = allowedToolNames
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }

        var droppedToolCalls = 0
        var droppedAssistantMessages = 0
        var changed = false
        val repaired = mutableListOf<LlmMessage>()

        for (message in messages) {
            if (message.role != LlmMessage.Role.ASSISTANT || message.toolCalls.isNullOrEmpty()) {
                repaired += message
                continue
            }

            val sanitizedCalls = mutableListOf<LlmToolCall>()
            for (call in message.toolCalls) {
                val id = call.id.trim()
                val normalizedName = call.name.trim()
                val hasInput = call.arguments.isNotBlank()
                val validName = normalizedName.isNotEmpty() &&
                    normalizedName.length <= TOOL_CALL_NAME_MAX_CHARS &&
                    TOOL_CALL_NAME_RE.matches(normalizedName)
                val allowed = normalizedAllowed?.contains(normalizedName.lowercase()) ?: true
                if (id.isEmpty() || !hasInput || !validName || !allowed) {
                    droppedToolCalls += 1
                    changed = true
                    continue
                }
                if (normalizedName != call.name || id != call.id) {
                    changed = true
                }
                sanitizedCalls += call.copy(id = id, name = normalizedName)
            }

            if (sanitizedCalls.isEmpty()) {
                if (message.plainTextContent().isBlank()) {
                    droppedAssistantMessages += 1
                    changed = true
                    continue
                }
                repaired += message.copy(toolCalls = null)
                changed = true
                continue
            }

            repaired += if (sanitizedCalls != message.toolCalls) {
                message.copy(toolCalls = sanitizedCalls)
            } else {
                message
            }
        }

        return ToolCallInputRepairReport(
            messages = if (changed) repaired else messages,
            droppedToolCalls = droppedToolCalls,
            droppedAssistantMessages = droppedAssistantMessages,
        )
    }

    fun repairToolUseResultPairing(
        messages: List<LlmMessage>,
        allowSyntheticToolResults: Boolean = true,
    ): ToolUseRepairReport {
        if (messages.isEmpty()) {
            return ToolUseRepairReport(
                messages = messages,
                addedSyntheticCount = 0,
                droppedDuplicateCount = 0,
                droppedOrphanCount = 0,
                moved = false,
            )
        }

        val output = mutableListOf<LlmMessage>()
        val seenToolResultIds = mutableSetOf<String>()
        var droppedDuplicateCount = 0
        var droppedOrphanCount = 0
        var addedSyntheticCount = 0
        var changed = false
        var moved = false

        fun normalizeToolResultMessage(message: LlmMessage, fallbackName: String?): LlmMessage {
            val normalizedToolCallId = message.toolCallId?.trim()?.takeIf { it.isNotEmpty() }
            val normalizedToolName = message.name
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(::normalizeToolName)
                ?: fallbackName?.trim()?.takeIf { it.isNotEmpty() }?.let(::normalizeToolName)
                ?: "unknown"
            val normalized = message.copy(
                toolCallId = normalizedToolCallId,
                name = normalizedToolName,
            )
            return normalized
        }

        fun pushToolResult(message: LlmMessage) {
            val id = message.toolCallId?.trim()?.takeIf { it.isNotEmpty() }
            if (id != null && seenToolResultIds.contains(id)) {
                droppedDuplicateCount += 1
                changed = true
                return
            }
            if (id != null) {
                seenToolResultIds += id
            }
            output += message
        }

        var i = 0
        while (i < messages.size) {
            val message = messages[i]
            if (message.role != LlmMessage.Role.ASSISTANT) {
                if (message.role == LlmMessage.Role.TOOL) {
                    droppedOrphanCount += 1
                    changed = true
                } else {
                    output += message
                }
                i += 1
                continue
            }

            val normalizedStopReason = normalizeStopReason(message.stopReason)
            val assistantToolCalls = message.toolCalls.orEmpty()
                .mapNotNull { call ->
                    val id = call.id.trim()
                    if (id.isEmpty()) {
                        changed = true
                        null
                    } else {
                        val normalizedName = normalizeToolName(call.name)
                        if (normalizedName != call.name || id != call.id) {
                            changed = true
                        }
                        call.copy(id = id, name = normalizedName)
                    }
                }
            val normalizedAssistant = message.copy(
                stopReason = normalizedStopReason,
                toolCalls = assistantToolCalls.takeIf { it.isNotEmpty() },
            )
            if (normalizedAssistant != message) {
                changed = true
            }

            if (assistantToolCalls.isEmpty()) {
                output += normalizedAssistant
                i += 1
                continue
            }

            if (shouldSkipPairing(normalizedStopReason)) {
                output += normalizedAssistant
                i += 1
                continue
            }

            output += normalizedAssistant

            val callIds = assistantToolCalls.map { it.id }.toSet()
            val callNamesById = assistantToolCalls.associate { it.id to it.name }
            val spanResultsById = mutableMapOf<String, LlmMessage>()
            val remainder = mutableListOf<LlmMessage>()

            var j = i + 1
            while (j < messages.size) {
                val next = messages[j]
                if (next.role == LlmMessage.Role.ASSISTANT) {
                    break
                }
                if (next.role == LlmMessage.Role.TOOL) {
                    val id = next.toolCallId?.trim()?.takeIf { it.isNotEmpty() }
                    if (id != null && callIds.contains(id)) {
                        if (seenToolResultIds.contains(id) || spanResultsById.containsKey(id)) {
                            droppedDuplicateCount += 1
                            changed = true
                        } else {
                            val normalized = normalizeToolResultMessage(next, callNamesById[id])
                            if (normalized != next) {
                                changed = true
                            }
                            spanResultsById[id] = normalized
                        }
                    } else {
                        droppedOrphanCount += 1
                        changed = true
                    }
                } else {
                    remainder += next
                }
                j += 1
            }

            if (spanResultsById.isNotEmpty() && remainder.isNotEmpty()) {
                moved = true
                changed = true
            }

            for (call in assistantToolCalls) {
                val existing = spanResultsById[call.id]
                if (existing != null) {
                    pushToolResult(existing)
                    continue
                }
                if (!allowSyntheticToolResults) {
                    continue
                }
                addedSyntheticCount += 1
                changed = true
                pushToolResult(createSyntheticMissingToolResult(call.id, call.name))
            }
            output += remainder
            i = j
        }

        return ToolUseRepairReport(
            messages = if (changed || moved) output else messages,
            addedSyntheticCount = addedSyntheticCount,
            droppedDuplicateCount = droppedDuplicateCount,
            droppedOrphanCount = droppedOrphanCount,
            moved = moved || changed,
        )
    }

    private fun shouldSkipPairing(stopReason: String?): Boolean {
        return when (normalizeStopReason(stopReason)) {
            "error", "aborted", "cancelled", "canceled" -> true
            else -> false
        }
    }

    private fun normalizeStopReason(stopReason: String?): String? {
        return stopReason?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    }

    private fun normalizeToolName(name: String): String {
        val normalized = name.trim().lowercase()
        return when (normalized) {
            "bash" -> "exec"
            "apply-patch" -> "apply_patch"
            else -> normalized
        }
    }

    private fun createSyntheticMissingToolResult(toolCallId: String, toolName: String): LlmMessage {
        return LlmMessage(
            role = LlmMessage.Role.TOOL,
            content = """{"status":"error","error":"Missing tool result in transcript history; inserted synthetic error result."}""",
            toolCallId = toolCallId,
            name = normalizeToolName(toolName),
        )
    }

    private companion object {
        private const val TOOL_CALL_NAME_MAX_CHARS = 64
        private val TOOL_CALL_NAME_RE = Regex("^[A-Za-z0-9_-]+$")
    }
}
