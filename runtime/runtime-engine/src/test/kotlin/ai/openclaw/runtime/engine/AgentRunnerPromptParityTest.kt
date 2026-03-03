package ai.openclaw.runtime.engine

import ai.openclaw.core.agent.LlmMessage
import ai.openclaw.core.agent.LlmProvider
import ai.openclaw.core.agent.LlmRequest
import ai.openclaw.core.agent.LlmStreamEvent
import ai.openclaw.core.plugins.BeforeAgentStartEvent
import ai.openclaw.core.plugins.BeforeAgentStartResult
import ai.openclaw.core.plugins.BeforePromptBuildEvent
import ai.openclaw.core.plugins.BeforePromptBuildResult
import ai.openclaw.core.plugins.HookRunner
import ai.openclaw.core.plugins.PluginHookAgentContext
import ai.openclaw.core.plugins.PluginHookName
import ai.openclaw.core.plugins.PluginHookRegistration
import ai.openclaw.core.plugins.PluginRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentRunnerPromptParityTest {

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
    fun `subagent session uses minimal prompt mode while keeping runtime workspace and skills sections`() = runTest {
        val provider = CapturingProvider()
        val runner = AgentRunner(provider = provider)

        runner.runTurn(
            messages = listOf(LlmMessage(role = LlmMessage.Role.USER, content = "hello")),
            model = "test-model",
            sessionKey = "agent:main:subagent:child",
            runtimeInfo = SystemPromptBuilder.RuntimeInfo(host = "android", model = "test-model"),
            workspaceDir = "/tmp/workspace",
            skills = listOf(
                SystemPromptBuilder.SkillSummary(
                    name = "skill-a",
                    description = "desc",
                    prompt = "do x",
                ),
            ),
        ).toList()

        val prompt = provider.lastRequest?.systemPrompt
        assertNotNull(prompt)
        assertTrue(prompt.contains("# Skills"))
        assertTrue(prompt.contains("# Runtime Information"))
        assertTrue(prompt.contains("## Workspace"))
    }

    @Test
    fun `non-subagent session keeps full prompt sections`() = runTest {
        val provider = CapturingProvider()
        val runner = AgentRunner(provider = provider)

        runner.runTurn(
            messages = listOf(LlmMessage(role = LlmMessage.Role.USER, content = "hello")),
            model = "test-model",
            sessionKey = "agent:main:direct:user-1",
            runtimeInfo = SystemPromptBuilder.RuntimeInfo(host = "android", model = "test-model"),
            workspaceDir = "/tmp/workspace",
            skills = listOf(
                SystemPromptBuilder.SkillSummary(
                    name = "skill-a",
                    description = "desc",
                    prompt = "do x",
                ),
            ),
        ).toList()

        val prompt = provider.lastRequest?.systemPrompt
        assertNotNull(prompt)
        assertTrue(prompt.contains("# Skills"))
    }

    @Test
    fun `prompt hooks prepend context and system prompt without changing resolved model`() = runTest {
        var promptHookMessageCount: Int? = null
        var legacyHookMessageCount: Int? = null
        val registry = PluginRegistry().apply {
            registerHook(
                PluginHookRegistration<suspend (BeforePromptBuildEvent, PluginHookAgentContext) -> BeforePromptBuildResult?>(
                    pluginId = "test-before-prompt",
                    hookName = PluginHookName.BEFORE_PROMPT_BUILD,
                    handler = { event, _ ->
                        promptHookMessageCount = event.messages.size
                        BeforePromptBuildResult(prependContext = "context-from-hook")
                    },
                ),
            )
            registerHook(
                PluginHookRegistration<suspend (BeforeAgentStartEvent, PluginHookAgentContext) -> BeforeAgentStartResult?>(
                    pluginId = "test-before-agent-start",
                    hookName = PluginHookName.BEFORE_AGENT_START,
                    handler = { event, _ ->
                        legacyHookMessageCount = event.messages?.size
                        BeforeAgentStartResult(
                            systemPrompt = "OVERRIDE SYSTEM PROMPT",
                            modelOverride = "hook-model",
                        )
                    },
                ),
            )
        }

        val provider = CapturingProvider()
        val runner = AgentRunner(
            provider = provider,
            hookRunner = HookRunner(registry),
        )

        runner.runTurn(
            messages = listOf(LlmMessage(role = LlmMessage.Role.USER, content = "run")),
            model = "base-model",
            sessionKey = "agent:main:direct:user-2",
        ).toList()

        val request = provider.lastRequest
        assertNotNull(request)
        assertEquals("base-model", request.model)
        assertEquals("OVERRIDE SYSTEM PROMPT", request.systemPrompt)
        val finalUser = request.messages.lastOrNull { it.role == LlmMessage.Role.USER }
        assertNotNull(finalUser)
        assertTrue(finalUser.content.startsWith("context-from-hook\n\nrun"))
        assertEquals(0, promptHookMessageCount)
        assertEquals(0, legacyHookMessageCount)
    }
}
