package ai.openclaw.runtime.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SystemPromptBuilderParityTest {

    @Test
    fun `none mode returns identity line only`() {
        val builder = SystemPromptBuilder()

        val prompt = builder.build(
            SystemPromptBuilder.PromptConfig(
                mode = SystemPromptBuilder.PromptMode.NONE,
                tools = listOf(
                    SystemPromptBuilder.ToolSummary(
                        name = "read",
                        description = "Read file contents",
                    ),
                ),
                skills = listOf(
                    SystemPromptBuilder.SkillSummary(
                        name = "sample",
                        description = "sample skill",
                        prompt = "follow sample instructions",
                    ),
                ),
            ),
        )

        assertEquals("You are a personal assistant running inside OpenClaw.", prompt.trim())
    }

    @Test
    fun `skills section includes mandatory selection workflow`() {
        val builder = SystemPromptBuilder()

        val prompt = builder.build(
            SystemPromptBuilder.PromptConfig(
                tools = listOf(
                    SystemPromptBuilder.ToolSummary(
                        name = "read",
                        description = "Read file contents",
                    ),
                ),
                skills = listOf(
                    SystemPromptBuilder.SkillSummary(
                        name = "atlas",
                        description = "Desktop automation",
                        prompt = "Use when the user asks to automate Atlas.",
                    ),
                ),
            ),
        )

        assertTrue(prompt.contains("## Skills (mandatory)"))
        assertTrue(prompt.contains("read its SKILL.md at <location> with `read`"))
        assertTrue(prompt.contains("<available_skills>"))
        assertTrue(prompt.contains("<description>Desktop automation</description>"))
        assertTrue(prompt.contains("avoid rapid poll loops"))
        assertTrue(prompt.contains("Completion is push-based"))
    }

    @Test
    fun `sessions spawn ACP guidance is gated by ACP enablement and sandbox mode`() {
        val builder = SystemPromptBuilder()
        val tools = listOf(
            SystemPromptBuilder.ToolSummary(
                name = "sessions_spawn",
                description = "Spawn sessions",
            ),
        )

        val enabledPrompt = builder.build(
            SystemPromptBuilder.PromptConfig(
                tools = tools,
                runtimeInfo = SystemPromptBuilder.RuntimeInfo(
                    acpEnabled = true,
                    sandboxed = false,
                ),
            ),
        )
        val sandboxedPrompt = builder.build(
            SystemPromptBuilder.PromptConfig(
                tools = tools,
                runtimeInfo = SystemPromptBuilder.RuntimeInfo(
                    acpEnabled = true,
                    sandboxed = true,
                ),
            ),
        )
        val disabledPrompt = builder.build(
            SystemPromptBuilder.PromptConfig(
                tools = tools,
                runtimeInfo = SystemPromptBuilder.RuntimeInfo(
                    acpEnabled = false,
                ),
            ),
        )

        assertTrue(enabledPrompt.contains("runtime: \"acp\""))
        assertTrue(!sandboxedPrompt.contains("treat it as ACP harness intent"))
        assertTrue(sandboxedPrompt.contains("ACP harness spawns are blocked"))
        assertTrue(disabledPrompt.contains("sessions_spawn"))
        assertTrue(!disabledPrompt.contains("runtime: \"acp\""))
    }

    @Test
    fun `elevated command guidance appears in sandbox section when enabled`() {
        val builder = SystemPromptBuilder()
        val tools = listOf(
            SystemPromptBuilder.ToolSummary(
                name = "exec",
                description = "Run shell",
            ),
        )

        val enabledPrompt = builder.build(
            SystemPromptBuilder.PromptConfig(
                tools = tools,
                runtimeInfo = SystemPromptBuilder.RuntimeInfo(
                    sandboxed = true,
                    elevatedEnabled = true,
                ),
            ),
        )
        val disabledPrompt = builder.build(
            SystemPromptBuilder.PromptConfig(
                tools = tools,
                runtimeInfo = SystemPromptBuilder.RuntimeInfo(
                    sandboxed = true,
                    elevatedEnabled = false,
                ),
            ),
        )

        assertTrue(enabledPrompt.contains("/elevated on|off|ask|full"))
        assertTrue(!disabledPrompt.contains("/elevated on|off|ask|full"))
    }

    @Test
    fun `full mode includes reply messaging silent and heartbeat guidance`() {
        val builder = SystemPromptBuilder()
        val fullPrompt = builder.build(
            SystemPromptBuilder.PromptConfig(
                mode = SystemPromptBuilder.PromptMode.FULL,
                tools = listOf(
                    SystemPromptBuilder.ToolSummary(
                        name = "message",
                        description = "Messaging actions",
                    ),
                ),
            ),
        )
        val minimalPrompt = builder.build(
            SystemPromptBuilder.PromptConfig(
                mode = SystemPromptBuilder.PromptMode.MINIMAL,
            ),
        )

        assertTrue(fullPrompt.contains("## Reply Tags"))
        assertTrue(fullPrompt.contains("## Messaging"))
        assertTrue(fullPrompt.contains("NO_REPLY"))
        assertTrue(fullPrompt.contains("HEARTBEAT_OK"))
        assertTrue(!minimalPrompt.contains("## Reply Tags"))
        assertTrue(!minimalPrompt.contains("NO_REPLY"))
        assertTrue(!minimalPrompt.contains("HEARTBEAT_OK"))
    }
}
