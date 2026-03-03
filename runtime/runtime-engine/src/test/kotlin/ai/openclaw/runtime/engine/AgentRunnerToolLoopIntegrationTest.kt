package ai.openclaw.runtime.engine

import ai.openclaw.core.agent.AgentTool
import ai.openclaw.core.agent.LlmMessage
import ai.openclaw.core.agent.LlmProvider
import ai.openclaw.core.agent.LlmRequest
import ai.openclaw.core.agent.LlmStreamEvent
import ai.openclaw.core.agent.ToolContext
import ai.openclaw.core.model.AcpRuntimeEvent
import ai.openclaw.core.model.ToolLoopDetectionConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class AgentRunnerToolLoopIntegrationTest {

    private class LoopingProvider : LlmProvider {
        override val id: String = "looping"
        private var callCounter = 0

        override fun streamCompletion(request: LlmRequest): Flow<LlmStreamEvent> = flow {
            val sawCriticalToolLoopResult = request.messages.any {
                it.role == LlmMessage.Role.TOOL && it.content.contains("CRITICAL:")
            }
            if (sawCriticalToolLoopResult) {
                emit(LlmStreamEvent.TextDelta("loop blocked, finishing"))
                emit(LlmStreamEvent.Done("stop"))
                return@flow
            }
            callCounter++
            emit(
                LlmStreamEvent.ToolUse(
                    id = "call_$callCounter",
                    name = "echo",
                    input = """{"value":"same"}""",
                ),
            )
            emit(LlmStreamEvent.Done("tool_calls"))
        }

        override fun supportsModel(modelId: String): Boolean = true
    }

    private class EchoTool : AgentTool {
        override val name: String = "echo"
        override val description: String = "Echo input"
        override val parametersSchema: String = """{"type":"object"}"""

        override suspend fun execute(input: String, context: ToolContext): String = "ok"
    }

    @Test
    fun `runner emits error when tool loop detector reaches critical threshold`() = runTest {
        val toolRegistry = ToolRegistry().apply { register(EchoTool()) }
        val runner = AgentRunner(
            provider = LoopingProvider(),
            toolRegistry = toolRegistry,
            maxToolRounds = 30,
            toolLoopDetector = ToolLoopDetector(
                ToolLoopDetectionConfig(
                    enabled = true,
                    historySize = 10,
                    warningThreshold = 2,
                    criticalThreshold = 3,
                    globalCircuitBreakerThreshold = 4,
                ),
            ),
        )

        val events = runner.runTurn(
            messages = listOf(LlmMessage(role = LlmMessage.Role.USER, content = "run")),
            model = "test-model",
        ).toList()

        val blocked = events.filterIsInstance<AcpRuntimeEvent.Status>()
        assertTrue(blocked.any { it.tag == "tool_loop_blocked" && it.text.contains("CRITICAL:") })
        val done = events.filterIsInstance<AcpRuntimeEvent.Done>()
        assertTrue(done.isNotEmpty())
    }
}
