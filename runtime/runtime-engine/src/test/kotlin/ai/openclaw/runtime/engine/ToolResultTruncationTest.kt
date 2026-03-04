package ai.openclaw.runtime.engine

import ai.openclaw.core.agent.LlmMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolResultTruncationTest {
    @Test
    fun `detects oversized tool result in history`() {
        val guard = ContextGuard(maxContextTokens = 2_000)
        val oversized = "x".repeat(10_000)
        val messages = listOf(
            LlmMessage(role = LlmMessage.Role.USER, content = "hello"),
            LlmMessage(role = LlmMessage.Role.TOOL, content = oversized, toolCallId = "call_1", name = "read"),
        )
        assertTrue(guard.sessionLikelyHasOversizedToolResults(messages))
    }

    @Test
    fun `truncates oversized tool results and preserves metadata`() {
        val guard = ContextGuard(maxContextTokens = 2_000)
        val oversized = "line\n".repeat(20_000)
        val messages = listOf(
            LlmMessage(
                role = LlmMessage.Role.TOOL,
                content = oversized,
                toolCallId = "call_1",
                name = "read",
            ),
        )

        val result = guard.truncateOversizedToolResults(messages)
        assertTrue(result.truncated)
        assertEquals(1, result.truncatedCount)
        val repaired = result.messages.single()
        assertEquals("call_1", repaired.toolCallId)
        assertEquals("read", repaired.name)
        assertTrue(repaired.content.contains("truncated"))
        assertTrue(repaired.content.length < oversized.length)
    }
}
