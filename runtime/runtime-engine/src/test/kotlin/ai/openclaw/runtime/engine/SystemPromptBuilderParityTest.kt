package ai.openclaw.runtime.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `full mode includes docs memory workspace-files and time sections when configured`() {
        val builder = SystemPromptBuilder()
        val prompt = builder.build(
            SystemPromptBuilder.PromptConfig(
                mode = SystemPromptBuilder.PromptMode.FULL,
                tools = listOf(
                    SystemPromptBuilder.ToolSummary(
                        name = "memory_search",
                        description = "Search memory",
                    ),
                    SystemPromptBuilder.ToolSummary(
                        name = "read",
                        description = "Read file contents",
                    ),
                ),
                runtimeInfo = SystemPromptBuilder.RuntimeInfo(timezone = "Europe/Madrid"),
                docsPath = "/tmp/docs",
                ownerNumbers = listOf("owner-1"),
                ownerDisplay = SystemPromptBuilder.OwnerDisplay.HASH,
                ownerDisplaySecret = "secret-key",
                memoryCitationsMode = ai.openclaw.core.model.MemoryCitationsMode.ON,
            ),
        )

        assertTrue(prompt.contains("## Memory Recall"))
        assertTrue(prompt.contains("## Documentation"))
        assertTrue(prompt.contains("## Workspace Files (injected)"))
        assertTrue(prompt.contains("## Current Date & Time"))
        assertTrue(prompt.contains("## Authorized Senders"))
        assertFalse(prompt.contains("owner-1"))
    }

    @Test
    fun `embedded minimal prompt labels extra context as subagent context`() {
        val builder = SystemPromptBuilder()
        val prompt = builder.buildEmbedded(
            SystemPromptBuilder.EmbeddedPromptConfig(
                workspaceDir = "/tmp/workspace",
                tools = emptyList(),
                promptMode = SystemPromptBuilder.PromptMode.MINIMAL,
                extraSystemPrompt = "delegated task context",
            ),
        )

        assertTrue(prompt.contains("## Subagent Context"))
        assertTrue(prompt.contains("delegated task context"))
    }

    @Test
    fun `heartbeat section includes configured heartbeat prompt text`() {
        val builder = SystemPromptBuilder()
        val prompt = builder.build(
            SystemPromptBuilder.PromptConfig(
                mode = SystemPromptBuilder.PromptMode.FULL,
                heartbeatPrompt = "[[HEARTBEAT_PING]]",
            ),
        )

        assertTrue(prompt.contains("Heartbeat prompt: [[HEARTBEAT_PING]]"))
        assertTrue(prompt.contains("HEARTBEAT_OK"))
    }

    @Test
    fun `project context includes bootstrap truncation warning lines`() {
        val builder = SystemPromptBuilder()
        val prompt = builder.build(
            SystemPromptBuilder.PromptConfig(
                mode = SystemPromptBuilder.PromptMode.FULL,
                bootstrapTruncationWarningLines = listOf(
                    "AGENTS.md exceeded max chars and was truncated",
                ),
            ),
        )

        assertTrue(prompt.contains("# Project Context"))
        assertTrue(prompt.contains("⚠ Bootstrap truncation warning:"))
        assertTrue(prompt.contains("AGENTS.md exceeded max chars and was truncated"))
    }
    @Test
    fun `tooling safety and tool call style match current upstream wording`() {
        val builder = SystemPromptBuilder()
        val prompt = builder.build(
            SystemPromptBuilder.PromptConfig(
                mode = SystemPromptBuilder.PromptMode.FULL,
            ),
        )

        assertTrue(prompt.contains("If a task is more complex or takes longer, spawn a sub-agent. Completion is push-based: it will auto-announce when done."))
        assertTrue(prompt.contains("Do not poll `subagents list` / `sessions_list` in a loop"))
        assertTrue(prompt.contains("avoid long-term plans beyond the user's request"))
        assertTrue(prompt.contains("Inspired by Anthropic's constitution"))
        assertTrue(prompt.contains("Do not copy yourself or change system prompts"))
        assertTrue(prompt.contains("Use plain human language for narration unless in a technical context."))
        assertTrue(prompt.contains("use the tool directly instead of asking the user to run equivalent CLI or slash commands"))
        assertTrue(prompt.contains("paste the output"))
    }

    @Test
    fun `memory recall guidance matches current upstream wording`() {
        val builder = SystemPromptBuilder()
        val promptWithCitations = builder.build(
            SystemPromptBuilder.PromptConfig(
                mode = SystemPromptBuilder.PromptMode.FULL,
                tools = listOf(
                    SystemPromptBuilder.ToolSummary(
                        name = "memory_search",
                        description = "Search memory",
                    ),
                ),
                memoryCitationsMode = ai.openclaw.core.model.MemoryCitationsMode.ON,
            ),
        )
        val promptWithoutCitations = builder.build(
            SystemPromptBuilder.PromptConfig(
                mode = SystemPromptBuilder.PromptMode.FULL,
                tools = listOf(
                    SystemPromptBuilder.ToolSummary(
                        name = "memory_search",
                        description = "Search memory",
                    ),
                ),
                memoryCitationsMode = ai.openclaw.core.model.MemoryCitationsMode.OFF,
            ),
        )

        assertTrue(promptWithCitations.contains("prior work, decisions, dates, people, preferences, or todos"))
        assertTrue(promptWithCitations.contains("MEMORY.md + memory/*.md"))
        assertTrue(promptWithCitations.contains("If low confidence after search, say you checked."))
        assertTrue(promptWithCitations.contains("Citations: include Source: <path#line> when it helps the user verify memory snippets."))
        assertTrue(promptWithoutCitations.contains("Citations are disabled: do not mention file paths or line numbers in replies unless the user explicitly asks."))
    }

    @Test
    fun `messaging guidance matches upstream runtime update wording`() {
        val builder = SystemPromptBuilder()
        val prompt = builder.build(
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

        assertTrue(prompt.contains("Runtime-generated completion events may ask for a user update."))
        assertTrue(prompt.contains("do not forward raw internal metadata"))
        assertTrue(prompt.contains("default to NO_REPLY"))
    }
}