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
        assertTrue(prompt.contains("read its SKILL.md with `read`"))
        assertTrue(prompt.contains("<available_skills>"))
        assertTrue(prompt.contains("<description>Desktop automation</description>"))
    }
}
