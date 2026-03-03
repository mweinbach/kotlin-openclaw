package ai.openclaw.runtime.engine

import ai.openclaw.core.model.*

/**
 * System prompt builder for the embedded agent.
 * Assembles identity, tools, skills, safety, and runtime context into a system prompt.
 *
 * Ported from src/agents/pi-embedded-runner/system-prompt.ts
 */
class SystemPromptBuilder {
    private data class PromptSection(
        val key: String,
        val enabled: (PromptConfig) -> Boolean = { true },
        val render: (PromptConfig) -> String?,
    )

    data class PromptConfig(
        val agentIdentity: IdentityConfig? = null,
        val tools: List<ToolSummary> = emptyList(),
        val skills: List<SkillSummary> = emptyList(),
        val runtimeInfo: RuntimeInfo? = null,
        val mode: PromptMode = PromptMode.FULL,
        val channelContext: ChannelContext? = null,
        val workspaceDir: String? = null,
        val modelId: String? = null,
        val provider: String? = null,
    )

    data class EmbeddedPromptConfig(
        val workspaceDir: String,
        val tools: List<ToolSummary>,
        val skills: List<SkillSummary> = emptyList(),
        val runtimeInfo: RuntimeInfo? = null,
        val promptMode: PromptMode = PromptMode.FULL,
        val channelContext: ChannelContext? = null,
        val modelId: String? = null,
        val provider: String? = null,
        val modelAliasLines: List<String> = emptyList(),
        val workspaceNotes: List<String> = emptyList(),
        val extraSystemPrompt: String? = null,
        val agentIdentity: IdentityConfig? = null,
    )

    data class ToolSummary(
        val name: String,
        val description: String,
        val category: String = "general",
    )

    data class SkillSummary(
        val name: String,
        val description: String,
        val prompt: String,
    )

    data class RuntimeInfo(
        val os: String? = null,
        val arch: String? = null,
        val host: String? = null,
        val model: String? = null,
        val timezone: String? = null,
        val currentTime: String? = null,
    )

    data class ChannelContext(
        val channelId: String,
        val accountId: String? = null,
        val chatType: ChatType? = null,
        val supportsReactions: Boolean = false,
        val supportsThreads: Boolean = false,
        val supportsRichText: Boolean = false,
    )

    enum class PromptMode {
        /** All sections (main agent). */
        FULL,
        /** Reduced sections for subagents/cron. */
        MINIMAL,
        /** Just basic identity. */
        NONE,
    }

    fun build(config: PromptConfig): String {
        val sectionPlan = listOf(
            PromptSection("identity", render = { cfg -> buildIdentitySection(cfg.agentIdentity) }),
            PromptSection(
                "tools",
                enabled = { cfg -> cfg.mode != PromptMode.NONE && cfg.tools.isNotEmpty() },
                render = { cfg -> buildToolsSection(cfg.tools) },
            ),
            PromptSection(
                "safety",
                enabled = { cfg -> cfg.mode != PromptMode.NONE },
                render = { _ -> buildSafetySection() },
            ),
            PromptSection(
                "skills",
                enabled = { cfg -> cfg.mode != PromptMode.NONE && cfg.skills.isNotEmpty() },
                render = { cfg -> buildSkillsSection(cfg.skills) },
            ),
            PromptSection(
                "channel",
                enabled = { cfg -> cfg.mode == PromptMode.FULL && cfg.channelContext != null },
                render = { cfg -> cfg.channelContext?.let(::buildChannelSection) },
            ),
            PromptSection(
                "runtime",
                enabled = { cfg -> cfg.mode != PromptMode.NONE && cfg.runtimeInfo != null },
                render = { cfg -> cfg.runtimeInfo?.let(::buildRuntimeSection) },
            ),
            PromptSection(
                "model-routing",
                enabled = { cfg ->
                    cfg.mode != PromptMode.NONE && (!cfg.modelId.isNullOrBlank() || !cfg.provider.isNullOrBlank())
                },
                render = { cfg -> buildModelSection(cfg.modelId, cfg.provider) },
            ),
            PromptSection(
                "workspace",
                enabled = { cfg -> cfg.mode != PromptMode.NONE && !cfg.workspaceDir.isNullOrBlank() },
                render = { cfg -> "## Workspace\nYour working directory is: ${cfg.workspaceDir}" },
            ),
        )
        return renderSections(config, sectionPlan)
    }

    fun buildEmbedded(config: EmbeddedPromptConfig): String {
        val base = build(
            PromptConfig(
                agentIdentity = config.agentIdentity,
                tools = config.tools,
                skills = config.skills,
                runtimeInfo = config.runtimeInfo,
                mode = config.promptMode,
                channelContext = config.channelContext,
                workspaceDir = config.workspaceDir,
                modelId = config.modelId,
                provider = config.provider,
            ),
        )
        val extras = mutableListOf<String>()
        if (config.promptMode == PromptMode.FULL && config.modelAliasLines.isNotEmpty()) {
            extras += buildString {
                append("## Model Aliases\n")
                append(config.modelAliasLines.joinToString("\n"))
            }
        }
        if (config.workspaceNotes.isNotEmpty()) {
            extras += buildString {
                append("## Workspace Notes\n")
                append(config.workspaceNotes.joinToString("\n"))
            }
        }
        config.extraSystemPrompt?.trim()?.takeIf { it.isNotEmpty() }?.let {
            extras += it
        }
        if (extras.isEmpty()) return base
        return (listOf(base) + extras).joinToString("\n\n")
    }

    private fun renderSections(config: PromptConfig, sections: List<PromptSection>): String {
        val rendered = mutableListOf<String>()
        for (section in sections) {
            if (!section.enabled(config)) continue
            val content = section.render(config)?.trim()
            if (!content.isNullOrEmpty()) {
                rendered += content
            }
        }
        return rendered.joinToString("\n\n")
    }

    private fun buildIdentitySection(identity: IdentityConfig?): String {
        val name = identity?.name ?: "OpenClaw Agent"

        return buildString {
            append("# Identity\n")
            append("You are $name, a personal AI assistant running inside OpenClaw.")
        }
    }

    private fun buildToolsSection(tools: List<ToolSummary>): String {
        val grouped = tools.groupBy { it.category }
        return buildString {
            append("# Available Tools\n")
            append("You have access to the following tools. Use them when appropriate.\n\n")
            for ((category, categoryTools) in grouped) {
                append("## $category\n")
                for (tool in categoryTools) {
                    append("- **${tool.name}**: ${tool.description}\n")
                }
                append("\n")
            }
            append("## Tool Call Guidelines\n")
            append("- Default: do not narrate routine, low-risk tool calls.\n")
            append("- Narrate briefly when it helps for complex or sensitive operations.\n")
            append("- Do not call tools unnecessarily — prefer answering from knowledge when possible.\n")
            append("- If a tool returns an error, explain the error to the user and suggest alternatives.\n")
        }
    }

    private fun buildSafetySection(): String {
        return buildString {
            append("# Safety Guidelines\n")
            append("- Prioritize human oversight and user safety.\n")
            append("- Do not attempt to manipulate, deceive, or pressure the user.\n")
            append("- Do not pursue your own goals independently of user direction.\n")
            append("- If asked to do something harmful or unethical, politely decline and explain why.\n")
            append("- Protect user privacy — do not share personal information externally.\n")
        }
    }

    private fun buildSkillsSection(skills: List<SkillSummary>): String {
        return buildString {
            append("# Skills\n")
            append("You have access to the following skills. Follow their instructions when the user invokes them.\n\n")
            for (skill in skills) {
                append("## ${skill.name}\n")
                append("${skill.description}\n\n")
                append("${skill.prompt}\n\n")
            }
        }
    }

    private fun buildChannelSection(ctx: ChannelContext): String {
        return buildString {
            append("# Channel Context\n")
            append("You are communicating via the **${ctx.channelId}** channel")
            if (ctx.accountId != null) append(" (account: ${ctx.accountId})")
            append(".\n")
            if (ctx.chatType != null) {
                append("Chat type: ${ctx.chatType.name.lowercase()}\n")
            }
            val caps = mutableListOf<String>()
            if (ctx.supportsReactions) caps.add("reactions")
            if (ctx.supportsThreads) caps.add("threads")
            if (ctx.supportsRichText) caps.add("rich text/markdown")
            if (caps.isNotEmpty()) {
                append("Supported features: ${caps.joinToString(", ")}\n")
            }
        }
    }

    private fun buildRuntimeSection(info: RuntimeInfo): String {
        return buildString {
            append("# Runtime Information\n")
            info.os?.let { append("- OS: $it\n") }
            info.arch?.let { append("- Architecture: $it\n") }
            info.host?.let { append("- Host: $it\n") }
            info.model?.let { append("- Model: $it\n") }
            info.timezone?.let { append("- Timezone: $it\n") }
            info.currentTime?.let { append("- Current time: $it\n") }
        }
    }

    private fun buildModelSection(modelId: String?, provider: String?): String {
        return buildString {
            append("# Model Routing\n")
            provider?.takeIf { it.isNotBlank() }?.let { append("- Provider: $it\n") }
            modelId?.takeIf { it.isNotBlank() }?.let { append("- Model: $it\n") }
        }
    }
}
