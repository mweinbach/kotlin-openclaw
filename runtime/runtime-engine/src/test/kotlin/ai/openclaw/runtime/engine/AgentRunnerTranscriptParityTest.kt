package ai.openclaw.runtime.engine

import ai.openclaw.core.agent.LlmMessage
import ai.openclaw.core.agent.LlmProvider
import ai.openclaw.core.agent.LlmRequest
import ai.openclaw.core.agent.LlmStreamEvent
import ai.openclaw.core.agent.LlmToolCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class AgentRunnerTranscriptParityTest {
    private class CapturingProvider : LlmProvider {
        override val id: String = "capturing"
        var lastRequest: LlmRequest? = null

        override fun streamCompletion(request: LlmRequest): Flow<LlmStreamEvent> = flow {
            lastRequest = request
            emit(LlmStreamEvent.Done(stopReason = "stop"))
        }

        override fun supportsModel(modelId: String): Boolean = true
    }

    @Test
    fun `runner drops orphan and duplicate tool results before provider request`() = runTest {
        val provider = CapturingProvider()
        val runner = AgentRunner(provider = provider)

        val history = listOf(
            LlmMessage(
                role = LlmMessage.Role.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    LlmToolCall(
                        id = "call_1",
                        name = "read",
                        arguments = """{"path":"README.md"}""",
                    ),
                ),
            ),
            LlmMessage(
                role = LlmMessage.Role.TOOL,
                content = "orphan-result",
                toolCallId = "call_orphan",
                name = "read",
            ),
            LlmMessage(
                role = LlmMessage.Role.TOOL,
                content = "first-result",
                toolCallId = "call_1",
                name = "read",
            ),
            LlmMessage(
                role = LlmMessage.Role.TOOL,
                content = "duplicate-result",
                toolCallId = "call_1",
                name = "read",
            ),
            LlmMessage(
                role = LlmMessage.Role.USER,
                content = "continue",
            ),
        )

        runner.runTurn(
            messages = history,
            model = "test-model",
            sessionKey = "agent:main:direct:transcript-sanitize",
        ).toList()

        val request = provider.lastRequest
        assertNotNull(request)
        val toolMessages = request.messages.filter { it.role == LlmMessage.Role.TOOL }
        assertEquals(1, toolMessages.size)
        assertEquals("call_1", toolMessages.single().toolCallId)
    }

    @Test
    fun `runner inserts synthetic tool result when missing`() = runTest {
        val provider = CapturingProvider()
        val runner = AgentRunner(provider = provider)

        val history = listOf(
            LlmMessage(
                role = LlmMessage.Role.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    LlmToolCall(
                        id = "call_missing",
                        name = "read",
                        arguments = """{"path":"README.md"}""",
                    ),
                ),
            ),
            LlmMessage(
                role = LlmMessage.Role.USER,
                content = "next",
            ),
        )

        runner.runTurn(
            messages = history,
            model = "test-model",
            sessionKey = "agent:main:direct:transcript-synthetic",
        ).toList()

        val request = provider.lastRequest
        assertNotNull(request)
        val toolMessages = request.messages.filter { it.role == LlmMessage.Role.TOOL }
        assertEquals(1, toolMessages.size)
        assertEquals("call_missing", toolMessages.single().toolCallId)
        assertTrue(toolMessages.single().plainTextContent().contains("Missing tool result"))
    }

    @Test
    fun `runner moves displaced tool result directly after assistant tool call`() = runTest {
        val provider = CapturingProvider()
        val runner = AgentRunner(provider = provider)

        val history = listOf(
            LlmMessage(
                role = LlmMessage.Role.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    LlmToolCall(
                        id = "call_move",
                        name = "read",
                        arguments = """{"path":"README.md"}""",
                    ),
                ),
            ),
            LlmMessage(
                role = LlmMessage.Role.USER,
                content = "interleaving user message",
            ),
            LlmMessage(
                role = LlmMessage.Role.TOOL,
                content = "tool-result",
                toolCallId = "call_move",
                name = "read",
            ),
            LlmMessage(
                role = LlmMessage.Role.USER,
                content = "continue",
            ),
        )

        runner.runTurn(
            messages = history,
            model = "test-model",
            sessionKey = "agent:main:direct:transcript-move",
        ).toList()

        val request = provider.lastRequest
        assertNotNull(request)
        val firstUserIndex = request.messages.indexOfFirst { it.role == LlmMessage.Role.USER }
        val toolIndex = request.messages.indexOfFirst {
            it.role == LlmMessage.Role.TOOL && it.toolCallId == "call_move"
        }
        assertTrue(toolIndex in 0 until firstUserIndex)
    }

    @Test
    fun `runner does not insert synthetic tool result for aborted assistant turn`() = runTest {
        val provider = CapturingProvider()
        val runner = AgentRunner(provider = provider)

        val history = listOf(
            LlmMessage(
                role = LlmMessage.Role.ASSISTANT,
                content = "",
                stopReason = "error",
                toolCalls = listOf(
                    LlmToolCall(
                        id = "call_missing",
                        name = "read",
                        arguments = """{"path":"README.md"}""",
                    ),
                ),
            ),
            LlmMessage(
                role = LlmMessage.Role.USER,
                content = "continue",
            ),
        )

        runner.runTurn(
            messages = history,
            model = "test-model",
            sessionKey = "agent:main:direct:transcript-aborted-no-synthetic",
        ).toList()

        val request = provider.lastRequest
        assertNotNull(request)
        val toolMessages = request.messages.filter { it.role == LlmMessage.Role.TOOL }
        assertEquals(0, toolMessages.size)
    }
}
