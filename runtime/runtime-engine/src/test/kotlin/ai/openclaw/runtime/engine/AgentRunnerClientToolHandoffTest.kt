package ai.openclaw.runtime.engine

import ai.openclaw.core.agent.LlmMessage
import ai.openclaw.core.agent.LlmProvider
import ai.openclaw.core.agent.LlmRequest
import ai.openclaw.core.agent.LlmStreamEvent
import ai.openclaw.core.model.AcpRuntimeEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentRunnerClientToolHandoffTest {
    private class ClientToolCallProvider : LlmProvider {
        override val id: String = "client-tool-provider"
        var lastRequest: LlmRequest? = null

        override fun streamCompletion(request: LlmRequest): Flow<LlmStreamEvent> = flow {
            lastRequest = request
            emit(
                LlmStreamEvent.ToolUse(
                    id = "call_1",
                    name = "browser_click",
                    input = """{"selector":"#submit"}""",
                ),
            )
            emit(LlmStreamEvent.Done("tool_calls"))
        }

        override fun supportsModel(modelId: String): Boolean = true
    }

    @Test
    fun `client tool call yields pending handoff and tool_calls done reason`() = runTest {
        val provider = ClientToolCallProvider()
        val runner = AgentRunner(provider = provider)

        val events = runner.runTurn(
            messages = listOf(
                LlmMessage(role = LlmMessage.Role.USER, content = "Click the submit button"),
            ),
            model = "test-model",
            sessionKey = "agent:main:direct:client-tool-handoff",
            clientTools = listOf(
                ClientToolDefinition(
                    name = "browser_click",
                    description = "Click a DOM element",
                ),
            ),
        ).toList()

        val done = events.lastOrNull { it is AcpRuntimeEvent.Done } as? AcpRuntimeEvent.Done
        assertNotNull(done)
        assertEquals("tool_calls", done.stopReason)
        val pending = done.pendingToolCalls.orEmpty()
        assertEquals(1, pending.size)
        assertEquals("call_1", pending.first().id)
        assertEquals("browser_click", pending.first().name)

        val requestTools = provider.lastRequest?.tools.orEmpty().map { it.name }
        assertTrue("browser_click" in requestTools)
    }
}
