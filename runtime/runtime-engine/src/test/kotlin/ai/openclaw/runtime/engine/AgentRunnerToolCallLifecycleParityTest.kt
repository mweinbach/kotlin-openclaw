package ai.openclaw.runtime.engine

import ai.openclaw.core.agent.AgentTool
import ai.openclaw.core.agent.LlmMessage
import ai.openclaw.core.agent.LlmProvider
import ai.openclaw.core.agent.LlmRequest
import ai.openclaw.core.agent.LlmStreamEvent
import ai.openclaw.core.agent.ToolContext
import ai.openclaw.core.model.AcpRuntimeEvent
import ai.openclaw.core.model.ExpandedToolsConfig
import ai.openclaw.core.model.OpenClawConfig
import ai.openclaw.core.security.ToolPolicyEnforcer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentRunnerToolCallLifecycleParityTest {
    private class TwoRoundToolProvider(
        private val toolName: String,
    ) : LlmProvider {
        override val id: String = "two-round-tool-provider"
        private var round = 0

        override fun streamCompletion(request: LlmRequest): Flow<LlmStreamEvent> = flow {
            round += 1
            if (round == 1) {
                emit(
                    LlmStreamEvent.ToolUse(
                        id = "call_1",
                        name = toolName,
                        input = """{"path":"README.md"}""",
                    ),
                )
                emit(LlmStreamEvent.Done("tool_calls"))
            } else {
                emit(LlmStreamEvent.TextDelta("done"))
                emit(LlmStreamEvent.Done("stop"))
            }
        }

        override fun supportsModel(modelId: String): Boolean = true
    }

    private class EchoTool : AgentTool {
        override val name: String = "echo"
        override val description: String = "echo"
        override val parametersSchema: String = """{"type":"object"}"""

        override suspend fun execute(input: String, context: ToolContext): String = "ok"
    }

    @Test
    fun `runner emits tool_call start and completed update`() = runTest {
        val runner = AgentRunner(
            provider = TwoRoundToolProvider(toolName = "echo"),
            toolRegistry = ToolRegistry().apply { register(EchoTool()) },
        )

        val events = runner.runTurn(
            messages = listOf(LlmMessage(role = LlmMessage.Role.USER, content = "run tool")),
            model = "test-model",
            sessionKey = "agent:main:direct:tool-lifecycle-success",
        ).toList()

        val toolEvents = events.filterIsInstance<AcpRuntimeEvent.ToolCall>()
        assertTrue(toolEvents.size >= 2)
        val start = toolEvents.firstOrNull { it.tag == "tool_call" }
        val update = toolEvents.lastOrNull { it.tag == "tool_call_update" }
        assertNotNull(start)
        assertNotNull(update)
        assertEquals("call_1", start.toolCallId)
        assertEquals("in_progress", start.status)
        assertEquals("""{"path":"README.md"}""", start.rawInput)
        assertEquals("other", start.kind)
        assertEquals("call_1", update.toolCallId)
        assertEquals("completed", update.status)
        assertEquals("ok", update.rawOutput)
    }

    @Test
    fun `runner emits tool_call_update failed for unknown tools`() = runTest {
        val runner = AgentRunner(
            provider = TwoRoundToolProvider(toolName = "missing_tool"),
            toolRegistry = ToolRegistry(),
        )

        val events = runner.runTurn(
            messages = listOf(LlmMessage(role = LlmMessage.Role.USER, content = "run tool")),
            model = "test-model",
            sessionKey = "agent:main:direct:tool-lifecycle-error",
        ).toList()

        val update = events
            .filterIsInstance<AcpRuntimeEvent.ToolCall>()
            .lastOrNull { it.tag == "tool_call_update" }
        assertNotNull(update)
        assertEquals("failed", update.status)
        assertEquals("call_1", update.toolCallId)
    }

    @Test
    fun `runner emits tool_call_update failed when policy denies tool`() = runTest {
        val runner = AgentRunner(
            provider = TwoRoundToolProvider(toolName = "echo"),
            toolRegistry = ToolRegistry().apply { register(EchoTool()) },
            toolPolicyEnforcer = ToolPolicyEnforcer(
                config = OpenClawConfig(
                    tools = ExpandedToolsConfig(
                        deny = listOf("echo"),
                    ),
                ),
            ),
        )

        val events = runner.runTurn(
            messages = listOf(LlmMessage(role = LlmMessage.Role.USER, content = "run tool")),
            model = "test-model",
            sessionKey = "agent:main:direct:tool-lifecycle-blocked",
        ).toList()

        val update = events
            .filterIsInstance<AcpRuntimeEvent.ToolCall>()
            .lastOrNull { it.tag == "tool_call_update" }
        assertNotNull(update)
        assertEquals("failed", update.status)
        assertEquals("call_1", update.toolCallId)
    }
}
