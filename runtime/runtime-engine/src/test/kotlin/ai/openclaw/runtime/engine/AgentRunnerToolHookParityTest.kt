package ai.openclaw.runtime.engine

import ai.openclaw.core.agent.AgentTool
import ai.openclaw.core.agent.LlmMessage
import ai.openclaw.core.agent.LlmProvider
import ai.openclaw.core.agent.LlmRequest
import ai.openclaw.core.agent.LlmStreamEvent
import ai.openclaw.core.agent.ToolContext
import ai.openclaw.core.plugins.BeforeToolCallEvent
import ai.openclaw.core.plugins.BeforeToolCallResult
import ai.openclaw.core.plugins.HookRunner
import ai.openclaw.core.plugins.PluginHookName
import ai.openclaw.core.plugins.PluginHookRegistration
import ai.openclaw.core.plugins.PluginHookToolContext
import ai.openclaw.core.plugins.PluginRegistry
import ai.openclaw.core.plugins.ToolResultPersistEvent
import ai.openclaw.core.plugins.ToolResultPersistResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentRunnerToolHookParityTest {
    private class TwoRoundProvider : LlmProvider {
        override val id: String = "two-round"
        private var round = 0

        override fun streamCompletion(request: LlmRequest): Flow<LlmStreamEvent> = flow {
            round++
            if (round == 1) {
                emit(
                    LlmStreamEvent.ToolUse(
                        id = "call_1",
                        name = "echo",
                        input = """{"base":"yes"}""",
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

    private class CapturingEchoTool : AgentTool {
        override val name: String = "echo"
        override val description: String = "echo"
        override val parametersSchema: String = """{"type":"object"}"""
        var lastInput: String? = null

        override suspend fun execute(input: String, context: ToolContext): String {
            lastInput = input
            return "ok"
        }
    }

    @Test
    fun `before tool call hook can mutate params before execution`() = runTest {
        var capturedRunId: String? = null
        var capturedToolCallId: String? = null
        var capturedCtxRunId: String? = null
        val registry = PluginRegistry().apply {
            registerHook(
                PluginHookRegistration<suspend (BeforeToolCallEvent, PluginHookToolContext) -> BeforeToolCallResult?>(
                    pluginId = "hook-before-tool",
                    hookName = PluginHookName.BEFORE_TOOL_CALL,
                    handler = { event, ctx ->
                        capturedRunId = event.runId
                        capturedToolCallId = event.toolCallId
                        capturedCtxRunId = ctx.runId
                        BeforeToolCallResult(
                            params = mapOf("addedByHook" to "true"),
                        )
                    },
                ),
            )
        }

        val tool = CapturingEchoTool()
        val tools = ToolRegistry().apply { register(tool) }
        val runner = AgentRunner(
            provider = TwoRoundProvider(),
            toolRegistry = tools,
            hookRunner = HookRunner(registry),
        )

        runner.runTurn(
            messages = listOf(LlmMessage(role = LlmMessage.Role.USER, content = "go")),
            model = "test-model",
            sessionKey = "agent:main:direct:user-1",
        ).toList()

        val input = tool.lastInput
        assertNotNull(input)
        assertTrue(input.contains("\"base\":\"yes\""))
        assertTrue(input.contains("\"addedByHook\":\"true\""))
        assertNotNull(capturedRunId)
        assertEquals("call_1", capturedToolCallId)
        assertEquals(capturedRunId, capturedCtxRunId)
    }

    @Test
    fun `before tool call hook can block execution`() = runTest {
        val registry = PluginRegistry().apply {
            registerHook(
                PluginHookRegistration<suspend (BeforeToolCallEvent, PluginHookToolContext) -> BeforeToolCallResult?>(
                    pluginId = "hook-before-tool-block",
                    hookName = PluginHookName.BEFORE_TOOL_CALL,
                    handler = { _, _ ->
                        BeforeToolCallResult(
                            block = true,
                            blockReason = "blocked-by-test-hook",
                        )
                    },
                ),
            )
        }

        val tool = CapturingEchoTool()
        val tools = ToolRegistry().apply { register(tool) }
        val runner = AgentRunner(
            provider = TwoRoundProvider(),
            toolRegistry = tools,
            hookRunner = HookRunner(registry),
        )

        val events = runner.runTurn(
            messages = listOf(LlmMessage(role = LlmMessage.Role.USER, content = "go")),
            model = "test-model",
            sessionKey = "agent:main:direct:user-1",
        ).toList()

        assertEquals(null, tool.lastInput)
        val errorText = events.filterIsInstance<ai.openclaw.core.model.AcpRuntimeEvent.Error>()
            .joinToString("\n") { it.message }
        assertTrue(errorText.isEmpty())
    }

    @Test
    fun `tool result persist hook can transform persisted tool message`() = runTest {
        val tempDir = Files.createTempDirectory("tool-result-persist-test")
        val registry = PluginRegistry().apply {
            registerHook(
                PluginHookRegistration<suspend (ToolResultPersistEvent, PluginHookToolContext) -> ToolResultPersistResult?>(
                    pluginId = "hook-tool-result-persist",
                    hookName = PluginHookName.TOOL_RESULT_PERSIST,
                    handler = { event, _ ->
                        ToolResultPersistResult(
                            message = event.message.copy(content = "persisted-from-hook"),
                        )
                    },
                ),
            )
        }

        val tool = CapturingEchoTool()
        val tools = ToolRegistry().apply { register(tool) }
        val persistence = SessionPersistence(tempDir.toString())
        val runner = AgentRunner(
            provider = TwoRoundProvider(),
            toolRegistry = tools,
            hookRunner = HookRunner(registry),
            sessionPersistence = persistence,
        )

        val sessionKey = "agent:main:direct:tool-result-persist"
        runner.runTurn(
            messages = listOf(LlmMessage(role = LlmMessage.Role.USER, content = "go")),
            model = "test-model",
            sessionKey = sessionKey,
        ).toList()

        val (_, persistedMessages) = persistence.load(sessionKey)
        val persistedTool = persistedMessages.firstOrNull { it.role == LlmMessage.Role.TOOL }
        assertNotNull(persistedTool)
        assertEquals("persisted-from-hook", persistedTool.content)
    }

    @Test
    fun `tool result persist hooks chain message transformations sequentially`() = runTest {
        val tempDir = Files.createTempDirectory("tool-result-persist-chain-test")
        var secondHookSawContent: String? = null
        val registry = PluginRegistry().apply {
            registerHook(
                PluginHookRegistration<suspend (ToolResultPersistEvent, PluginHookToolContext) -> ToolResultPersistResult?>(
                    pluginId = "hook-tool-result-persist-first",
                    hookName = PluginHookName.TOOL_RESULT_PERSIST,
                    priority = 10,
                    handler = { event, _ ->
                        ToolResultPersistResult(
                            message = event.message.copy(content = "first"),
                        )
                    },
                ),
            )
            registerHook(
                PluginHookRegistration<suspend (ToolResultPersistEvent, PluginHookToolContext) -> ToolResultPersistResult?>(
                    pluginId = "hook-tool-result-persist-second",
                    hookName = PluginHookName.TOOL_RESULT_PERSIST,
                    priority = 5,
                    handler = { event, _ ->
                        secondHookSawContent = event.message.content
                        ToolResultPersistResult(
                            message = event.message.copy(content = "${event.message.content}+second"),
                        )
                    },
                ),
            )
        }

        val tool = CapturingEchoTool()
        val tools = ToolRegistry().apply { register(tool) }
        val persistence = SessionPersistence(tempDir.toString())
        val runner = AgentRunner(
            provider = TwoRoundProvider(),
            toolRegistry = tools,
            hookRunner = HookRunner(registry),
            sessionPersistence = persistence,
        )

        val sessionKey = "agent:main:direct:tool-result-persist-chain"
        runner.runTurn(
            messages = listOf(LlmMessage(role = LlmMessage.Role.USER, content = "go")),
            model = "test-model",
            sessionKey = sessionKey,
        ).toList()

        assertEquals("first", secondHookSawContent)
        val (_, persistedMessages) = persistence.load(sessionKey)
        val persistedTool = persistedMessages.firstOrNull { it.role == LlmMessage.Role.TOOL }
        assertNotNull(persistedTool)
        assertEquals("first+second", persistedTool.content)
    }
}
