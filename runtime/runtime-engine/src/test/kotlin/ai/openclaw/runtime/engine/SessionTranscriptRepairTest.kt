package ai.openclaw.runtime.engine

import ai.openclaw.core.agent.LlmMessage
import ai.openclaw.core.agent.LlmToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionTranscriptRepairTest {
    private val repair = SessionTranscriptRepair()

    @Test
    fun `drops malformed and disallowed tool calls`() {
        val messages = listOf(
            LlmMessage(
                role = LlmMessage.Role.ASSISTANT,
                content = "tool call",
                toolCalls = listOf(
                    LlmToolCall(id = "", name = "read", arguments = """{"path":"a"}"""),
                    LlmToolCall(id = "call_ok", name = "read", arguments = """{"path":"b"}"""),
                    LlmToolCall(id = "call_bad", name = "exec", arguments = """{"cmd":"ls"}"""),
                ),
            ),
        )

        val report = repair.repairToolCallInputs(messages, allowedToolNames = setOf("read"))
        assertEquals(2, report.droppedToolCalls)
        val repairedCalls = report.messages.single().toolCalls.orEmpty()
        assertEquals(1, repairedCalls.size)
        assertEquals("call_ok", repairedCalls.single().id)
    }

    @Test
    fun `moves matching tool result and inserts synthetic for missing`() {
        val messages = listOf(
            LlmMessage(
                role = LlmMessage.Role.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    LlmToolCall(id = "call_1", name = "read", arguments = "{}"),
                    LlmToolCall(id = "call_2", name = "read", arguments = "{}"),
                ),
            ),
            LlmMessage(role = LlmMessage.Role.USER, content = "interleaving"),
            LlmMessage(role = LlmMessage.Role.TOOL, content = "result-1", toolCallId = "call_1", name = "read"),
        )

        val report = repair.repairToolUseResultPairing(messages, allowSyntheticToolResults = true)
        assertTrue(report.moved)
        assertEquals(1, report.addedSyntheticCount)
        val toolMessages = report.messages.filter { it.role == LlmMessage.Role.TOOL }
        assertEquals(2, toolMessages.size)
        assertEquals("call_1", toolMessages[0].toolCallId)
        assertEquals("call_2", toolMessages[1].toolCallId)
    }
}
