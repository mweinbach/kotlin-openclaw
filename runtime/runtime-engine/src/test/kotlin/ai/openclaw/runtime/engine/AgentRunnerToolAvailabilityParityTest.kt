package ai.openclaw.runtime.engine

import ai.openclaw.core.agent.AgentTool
import ai.openclaw.core.agent.LlmMessage
import ai.openclaw.core.agent.LlmProvider
import ai.openclaw.core.agent.LlmRequest
import ai.openclaw.core.agent.LlmStreamEvent
import ai.openclaw.core.agent.ToolContext
import ai.openclaw.core.model.ExpandedToolsConfig
import ai.openclaw.core.model.OpenClawConfig
import ai.openclaw.core.security.ToolPolicyEnforcer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentRunnerToolAvailabilityParityTest {

    private class CapturingProvider : LlmProvider {
        override val id: String = "capture"
        var lastRequest: LlmRequest? = null

        override fun streamCompletion(request: LlmRequest): Flow<LlmStreamEvent> = flow {
            lastRequest = request
            emit(LlmStreamEvent.Done("stop"))
        }

        override fun supportsModel(modelId: String): Boolean = true
    }

    private class StaticTool(
        override val name: String,
    ) : AgentTool {
        override val description: String = "tool-$name"
        override val parametersSchema: String = """{"type":"object","properties":{}}"""
        override suspend fun execute(input: String, context: ToolContext): String = "{}"
    }

    @Test
    fun `runner only advertises policy-allowed tools`() = runTest {
        val provider = CapturingProvider()
        val registry = ToolRegistry().apply {
            register(StaticTool("read"))
            register(StaticTool("exec"))
        }
        val enforcer = ToolPolicyEnforcer(
            OpenClawConfig(tools = ExpandedToolsConfig(allow = listOf("read"))),
        )
        val runner = AgentRunner(
            provider = provider,
            toolRegistry = registry,
            toolPolicyEnforcer = enforcer,
        )

        runner.runTurn(
            messages = listOf(LlmMessage(role = LlmMessage.Role.USER, content = "hi")),
            model = "test",
            agentId = "main",
            sessionKey = "s",
        ).toList()

        val toolNames = provider.lastRequest?.tools?.map { it.name }.orEmpty()
        assertEquals(listOf("read"), toolNames)
        assertTrue("exec" !in toolNames)
    }
}
