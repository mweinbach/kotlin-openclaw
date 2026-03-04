package ai.openclaw.runtime.engine

import ai.openclaw.core.agent.AgentTool
import ai.openclaw.core.agent.LlmContentBlock
import ai.openclaw.core.agent.LlmMessage
import ai.openclaw.core.agent.LlmProvider
import ai.openclaw.core.agent.LlmRequest
import ai.openclaw.core.agent.LlmStreamEvent
import ai.openclaw.core.agent.ToolContext
import ai.openclaw.core.plugins.AgentEndEvent
import ai.openclaw.core.plugins.HookRunner
import ai.openclaw.core.plugins.LlmInputEvent
import ai.openclaw.core.plugins.LlmOutputEvent
import ai.openclaw.core.plugins.PluginHookAgentContext
import ai.openclaw.core.plugins.PluginHookName
import ai.openclaw.core.plugins.PluginHookRegistration
import ai.openclaw.core.plugins.PluginRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentRunnerLifecycleHookParityTest {
    private class OneShotProvider : LlmProvider {
        override val id: String = "one-shot"
        override fun streamCompletion(request: LlmRequest): Flow<LlmStreamEvent> = flow {
            emit(LlmStreamEvent.TextDelta("done"))
            emit(LlmStreamEvent.Usage(inputTokens = 10, outputTokens = 20, cacheRead = 1, cacheWrite = 2))
            emit(LlmStreamEvent.Done("stop"))
        }

        override fun supportsModel(modelId: String): Boolean = true
    }

    private class TwoRoundProvider : LlmProvider {
        override val id: String = "two-round"
        private var round = 0

        override fun streamCompletion(request: LlmRequest): Flow<LlmStreamEvent> = flow {
            round += 1
            if (round == 1) {
                emit(
                    LlmStreamEvent.ToolUse(
                        id = "call_1",
                        name = "echo",
                        input = """{"value":"1"}""",
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
    fun `runner emits llm input output and agent end hooks`() = runTest {
        var llmInputCalls = 0
        var llmOutputCalls = 0
        var agentEndCalls = 0
        var seenRunId: String? = null
        var seenSessionId: String? = null
        var outputRunId: String? = null
        var outputSessionId: String? = null
        var endSuccess: Boolean? = null
        var endMessagesSize: Int? = null

        val registry = PluginRegistry().apply {
            registerHook(
                PluginHookRegistration<suspend (LlmInputEvent, PluginHookAgentContext) -> Unit>(
                    pluginId = "hook-llm-input",
                    hookName = PluginHookName.LLM_INPUT,
                    handler = { event, _ ->
                        llmInputCalls++
                        seenRunId = event.runId
                        seenSessionId = event.sessionId
                        assertEquals(0, event.historyMessages.size)
                    },
                ),
            )
            registerHook(
                PluginHookRegistration<suspend (LlmOutputEvent, PluginHookAgentContext) -> Unit>(
                    pluginId = "hook-llm-output",
                    hookName = PluginHookName.LLM_OUTPUT,
                    handler = { event, _ ->
                        llmOutputCalls++
                        outputRunId = event.runId
                        outputSessionId = event.sessionId
                        assertEquals(listOf("done"), event.assistantTexts)
                        val usage = event.usage
                        assertNotNull(usage)
                        assertEquals(33, usage.total)
                    },
                ),
            )
            registerHook(
                PluginHookRegistration<suspend (AgentEndEvent, PluginHookAgentContext) -> Unit>(
                    pluginId = "hook-agent-end",
                    hookName = PluginHookName.AGENT_END,
                    handler = { event, _ ->
                        agentEndCalls++
                        endSuccess = event.success
                        endMessagesSize = event.messages.size
                    },
                ),
            )
        }

        val runner = AgentRunner(
            provider = OneShotProvider(),
            hookRunner = HookRunner(registry),
        )

        runner.runTurn(
            messages = listOf(LlmMessage(role = LlmMessage.Role.USER, content = "hello")),
            model = "test-model",
            sessionKey = "agent:main:direct:user-hooks",
        ).toList()

        repeat(50) {
            if (llmInputCalls >= 1 && llmOutputCalls >= 1 && agentEndCalls >= 1) {
                return@repeat
            }
            delay(20)
        }

        assertEquals(1, llmInputCalls)
        assertEquals(1, llmOutputCalls)
        assertEquals(1, agentEndCalls)
        assertEquals(seenRunId, outputRunId)
        assertEquals(seenSessionId, outputSessionId)
        assertEquals(true, endSuccess)
        assertEquals(1, endMessagesSize)
        assertNotNull(seenRunId)
        assertTrue(seenRunId!!.isNotBlank())
    }

    @Test
    fun `runner emits llm_input once per turn across tool rounds`() = runTest {
        var llmInputCalls = 0
        val registry = PluginRegistry().apply {
            registerHook(
                PluginHookRegistration<suspend (LlmInputEvent, PluginHookAgentContext) -> Unit>(
                    pluginId = "hook-llm-input-once",
                    hookName = PluginHookName.LLM_INPUT,
                    handler = { _, _ ->
                        llmInputCalls += 1
                    },
                ),
            )
        }

        val runner = AgentRunner(
            provider = TwoRoundProvider(),
            toolRegistry = ToolRegistry().apply { register(EchoTool()) },
            hookRunner = HookRunner(registry),
        )

        runner.runTurn(
            messages = listOf(LlmMessage(role = LlmMessage.Role.USER, content = "hello")),
            model = "test-model",
            sessionKey = "agent:main:direct:user-hooks",
        ).toList()

        repeat(50) {
            if (llmInputCalls >= 1) {
                return@repeat
            }
            delay(20)
        }

        assertEquals(1, llmInputCalls)
    }

    @Test
    fun `llm input hook receives prompt image count from latest user blocks`() = runTest {
        var capturedImagesCount: Int? = null
        val registry = PluginRegistry().apply {
            registerHook(
                PluginHookRegistration<suspend (LlmInputEvent, PluginHookAgentContext) -> Unit>(
                    pluginId = "hook-llm-input-images",
                    hookName = PluginHookName.LLM_INPUT,
                    handler = { event, _ ->
                        capturedImagesCount = event.imagesCount
                    },
                ),
            )
        }

        val runner = AgentRunner(
            provider = OneShotProvider(),
            hookRunner = HookRunner(registry),
        )

        runner.runTurn(
            messages = listOf(
                LlmMessage(
                    role = LlmMessage.Role.USER,
                    contentBlocks = listOf(
                        LlmContentBlock.Text("describe this image"),
                        LlmContentBlock.ImageUrl("https://example.com/demo.png"),
                    ),
                ),
            ),
            model = "test-model",
            sessionKey = "agent:main:direct:user-hooks",
        ).toList()

        repeat(50) {
            if (capturedImagesCount != null) return@repeat
            delay(20)
        }
        assertEquals(1, capturedImagesCount)
    }
}
