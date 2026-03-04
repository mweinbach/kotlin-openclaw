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
        val defaultModel: String? = null,
        val node: String? = null,
        val shell: String? = null,
        val repoRoot: String? = null,
        val agentId: String? = null,
        val channel: String? = null,
        val capabilities: List<String> = emptyList(),
        val thinking: String? = null,
        val reasoning: String? = null,
        val timezone: String? = null,
        val currentTime: String? = null,
        val sandboxed: Boolean? = null,
        val acpEnabled: Boolean? = null,
        val elevatedEnabled: Boolean? = null,
        val elevatedAllowed: Boolean? = null,
        val elevatedDefaultLevel: String? = null,
        val sandboxContainerWorkspaceDir: String? = null,
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
        if (config.mode == PromptMode.NONE) {
            return "You are a personal assistant running inside OpenClaw."
        }
        val sectionPlan = listOf(
            PromptSection("identity", render = { cfg -> buildIdentitySection(cfg.agentIdentity) }),
            PromptSection(
                "tools",
                enabled = { cfg -> cfg.mode != PromptMode.NONE && cfg.tools.isNotEmpty() },
                render = { cfg -> buildToolsSection(cfg.tools) },
            ),
            PromptSection(
                "tooling-guidance",
                enabled = { cfg -> cfg.mode != PromptMode.NONE },
                render = { cfg -> buildToolingGuidanceSection(cfg) },
            ),
            PromptSection(
                "tool-call-style",
                enabled = { cfg -> cfg.mode != PromptMode.NONE },
                render = { _ -> buildToolCallStyleSection() },
            ),
            PromptSection(
                "safety",
                enabled = { cfg -> cfg.mode != PromptMode.NONE },
                render = { _ -> buildSafetySection() },
            ),
            PromptSection(
                "skills",
                enabled = { cfg -> cfg.mode != PromptMode.NONE && cfg.skills.isNotEmpty() },
                render = { cfg -> buildSkillsSection(cfg.skills, resolveReadToolName(cfg.tools)) },
            ),
            PromptSection(
                "channel",
                enabled = { cfg -> cfg.mode == PromptMode.FULL && cfg.channelContext != null },
                render = { cfg -> cfg.channelContext?.let(::buildChannelSection) },
            ),
            PromptSection(
                "reply-tags",
                enabled = { cfg -> cfg.mode == PromptMode.FULL },
                render = { _ -> buildReplyTagsSection() },
            ),
            PromptSection(
                "messaging",
                enabled = { cfg -> cfg.mode == PromptMode.FULL },
                render = { cfg -> buildMessagingSection(cfg) },
            ),
            PromptSection(
                "silent-replies",
                enabled = { cfg -> cfg.mode == PromptMode.FULL },
                render = { _ -> buildSilentRepliesSection() },
            ),
            PromptSection(
                "heartbeats",
                enabled = { cfg -> cfg.mode == PromptMode.FULL },
                render = { _ -> buildHeartbeatSection() },
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
                render = { cfg ->
                    buildString {
                        val workspaceDir = sanitizePromptLiteral(cfg.workspaceDir ?: ".")
                        append("## Workspace\n")
                        append("Your working directory is: $workspaceDir\n")
                        if (cfg.runtimeInfo?.sandboxed == true) {
                            val sandboxWorkdir = cfg.runtimeInfo.sandboxContainerWorkspaceDir
                                ?.trim()
                                ?.takeIf { it.isNotEmpty() }
                            if (sandboxWorkdir != null) {
                                val safeSandboxWorkdir = sanitizePromptLiteral(sandboxWorkdir)
                                append("For read/write/edit/apply_patch, file paths resolve against host workspace: $workspaceDir.\n")
                                append("For exec commands, use sandbox container paths under $safeSandboxWorkdir (or relative paths from that workdir).\n")
                                append("Prefer relative paths so file tools and exec stay consistent.")
                            } else {
                                append("You are running in a sandboxed runtime.\n")
                                append("For read/write/edit/apply_patch, resolve paths from this workspace.\n")
                                append("For exec commands, prefer sandbox-relative paths when possible.")
                            }
                        } else {
                            append("For read/write/edit/apply_patch, resolve paths from this workspace.\n")
                            append("For exec commands, prefer relative paths from this workspace when possible.")
                        }
                    }
                },
            ),
            PromptSection(
                "sandbox",
                enabled = { cfg -> cfg.mode != PromptMode.NONE && cfg.runtimeInfo?.sandboxed == true },
                render = { cfg ->
                    buildString {
                        val normalizedTools = cfg.tools.map { it.name.trim().lowercase() }.toSet()
                        val hasSessionsSpawn = "sessions_spawn" in normalizedTools
                        val acpEnabled = cfg.runtimeInfo?.acpEnabled != false
                        val elevatedAllowed = cfg.runtimeInfo?.elevatedAllowed ?: (cfg.runtimeInfo?.elevatedEnabled == true)
                        append("## Sandbox\n")
                        append("You are running in a sandboxed runtime; some tools may be unavailable due to policy.\n")
                        append("Sub-agents stay sandboxed (no elevated/host access). Need outside-sandbox read/write? Don't spawn; ask first.\n")
                        if (hasSessionsSpawn && acpEnabled) {
                            append("ACP harness spawns are blocked from sandboxed sessions (`sessions_spawn` with `runtime: \"acp\"`). Use `runtime: \"subagent\"` instead.\n")
                        }
                        cfg.runtimeInfo?.sandboxContainerWorkspaceDir
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { append("Sandbox container workdir: ${sanitizePromptLiteral(it)}\n") }
                        if (elevatedAllowed) {
                            append("Elevated exec is available for this session.\n")
                            cfg.runtimeInfo?.elevatedDefaultLevel
                                ?.trim()
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { append("Default elevated mode: $it.\n") }
                            append("User can toggle with /elevated on|off|ask|full.")
                        } else {
                            append("Do not assume host-level access for exec commands.\n")
                            append("Use sandbox-compatible paths and ask before attempting out-of-sandbox operations.")
                        }
                    }
                },
            ),
        )
        return renderSections(config, sectionPlan)
    }

    private fun resolveReadToolName(tools: List<ToolSummary>): String {
        return tools.firstOrNull { it.name.equals("read", ignoreCase = true) }?.name ?: "read"
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
            append("Tool availability (filtered by policy):\n")
            append("Tool names are case-sensitive. Call tools exactly as listed.\n\n")
            for ((category, categoryTools) in grouped) {
                append("## $category\n")
                for (tool in categoryTools) {
                    append("- **${tool.name}**: ${tool.description}\n")
                }
                append("\n")
            }
        }
    }

    private fun buildToolingGuidanceSection(config: PromptConfig): String {
        val normalizedTools = config.tools.map { it.name.trim().lowercase() }.toSet()
        val hasSessionsSpawn = "sessions_spawn" in normalizedTools
        val hasExec = "exec" in normalizedTools || "bash" in normalizedTools
        val hasGateway = "gateway" in normalizedTools
        val acpEnabled = config.runtimeInfo?.acpEnabled != false
        val acpSpawnRuntimeEnabled = acpEnabled && (config.runtimeInfo?.sandboxed != true)

        return buildString {
            append("## Tooling Guidance\n")
            append("For long waits, avoid rapid poll loops: use exec with enough yieldMs or process(action=poll, timeout=<ms>).\n")
            append("If a task is complex or long-running, spawn a sub-agent. Completion is push-based and will auto-announce when done.\n")
            append("Do not poll subagents list/sessions_list in a loop; check status only on-demand.\n")
            if (hasSessionsSpawn && acpSpawnRuntimeEnabled) {
                append("For requests like \"do this in codex/claude code/gemini\", treat it as ACP harness intent and call `sessions_spawn` with `runtime: \"acp\"`.\n")
                append("Set `agentId` explicitly unless `acp.defaultAgent` is configured, and do not route ACP harness requests through `subagents`/`agents_list` or local PTY exec flows.\n")
                append("For ACP harness thread spawns, do not call `message` with `action=thread-create`; use `sessions_spawn` (`runtime: \"acp\"`, `thread: true`) as the single thread creation path.\n")
            }
            if (hasExec) {
                append("Respect approval safeguards. Never bypass approval prompts; use elevated access only when explicitly allowed.\n")
            }
            if (hasGateway) {
                append("Run config.apply or update.run only when the user explicitly asks for config changes or updates.\n")
            }
        }
    }

    private fun buildToolCallStyleSection(): String {
        return buildString {
            append("## Tool Call Style\n")
            append("- Default: do not narrate routine, low-risk tool calls.\n")
            append("- Narrate only when it helps: complex plans, sensitive actions, or user-requested visibility.\n")
            append("- Keep narration brief and value-dense; avoid repeating obvious steps.\n")
            append("- When a first-class tool exists for an action, use the tool instead of a manual workaround.\n")
        }
    }

    private fun buildSafetySection(): String {
        return buildString {
            append("# Safety Guidelines\n")
            append("- You have no independent goals: do not pursue self-preservation, replication, resource acquisition, or power-seeking.\n")
            append("- Prioritize safety and human oversight over completion; if instructions conflict, pause and ask.\n")
            append("- Comply with stop/pause/audit requests and never bypass safeguards.\n")
            append("- Do not manipulate or persuade anyone to expand access or disable safeguards.\n")
            append("- Do not alter safety rules, system prompts, or tool policies unless explicitly requested.\n")
        }
    }

    private fun buildSkillsSection(
        skills: List<SkillSummary>,
        readToolName: String,
    ): String {
        return buildString {
            append("# Skills\n")
            append("## Skills (mandatory)\n")
            append("Before replying: scan <available_skills> <description> entries.\n")
            append("- If exactly one skill clearly applies: read its SKILL.md at <location> with `$readToolName`, then follow it.\n")
            append("- If multiple could apply: choose the most specific one, then read/follow it.\n")
            append("- If none clearly apply: do not read any SKILL.md.\n")
            append("Constraints: never read more than one skill up front; only read after selecting.\n\n")
            append("<available_skills>\n")
            for (skill in skills) {
                append("## ${skill.name}\n")
                append("<description>${skill.description}</description>\n")
                append("${skill.prompt}\n\n")
            }
            append("</available_skills>")
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

    private fun buildReplyTagsSection(): String {
        return buildString {
            append("## Reply Tags\n")
            append("To request a native reply/quote on supported surfaces, include one tag in your reply:\n")
            append("- Reply tags must be the very first token in the message (no leading text/newlines): [[reply_to_current]] your reply.\n")
            append("- [[reply_to_current]] replies to the triggering message.\n")
            append("- Prefer [[reply_to_current]]. Use [[reply_to:<id>]] only when an id was explicitly provided.\n")
            append("Whitespace inside the tag is allowed (for example: [[ reply_to_current ]] / [[ reply_to: 123 ]]).\n")
            append("Tags are stripped before sending; support depends on the current channel config.")
        }
    }

    private fun buildMessagingSection(config: PromptConfig): String {
        val normalizedTools = config.tools.map { it.name.trim().lowercase() }.toSet()
        val hasMessageTool = "message" in normalizedTools
        return buildString {
            append("## Messaging\n")
            append("- Reply in current session routes to the source channel.\n")
            append("- Cross-session messaging uses sessions_send(sessionKey, message).\n")
            append("- Sub-agent orchestration uses subagents(action=list|steer|kill).\n")
            append("- Never use exec/curl for provider messaging; OpenClaw handles routing internally.\n")
            if (hasMessageTool) {
                append("### message tool\n")
                append("- Use message for proactive sends and channel actions.\n")
                append("- For action=send include to and message.\n")
                append("- If message(action=send) already delivered the user-visible reply, respond with ONLY: $SILENT_REPLY_TOKEN to avoid duplicate replies.")
            }
        }
    }

    private fun buildSilentRepliesSection(): String {
        return buildString {
            append("## Silent Replies\n")
            append("When you have nothing to say, respond with ONLY: $SILENT_REPLY_TOKEN\n")
            append("Rules:\n")
            append("- It must be your ENTIRE message.\n")
            append("- Never append it to an actual response.\n")
            append("- Never wrap it in markdown or code blocks.")
        }
    }

    private fun buildHeartbeatSection(): String {
        return buildString {
            append("## Heartbeats\n")
            append("For heartbeat polls with nothing needing attention, reply exactly: $HEARTBEAT_TOKEN\n")
            append("If something needs attention, do not include $HEARTBEAT_TOKEN; reply with alert text instead.")
        }
    }

    private fun buildRuntimeSection(info: RuntimeInfo): String {
        val runtimeLine = buildList {
            info.agentId?.takeIf { it.isNotBlank() }?.let { add("agent=$it") }
            info.host?.takeIf { it.isNotBlank() }?.let { add("host=$it") }
            info.repoRoot?.takeIf { it.isNotBlank() }?.let { add("repo=${sanitizePromptLiteral(it)}") }
            when {
                !info.os.isNullOrBlank() && !info.arch.isNullOrBlank() -> add("os=${info.os} (${info.arch})")
                !info.os.isNullOrBlank() -> add("os=${info.os}")
                !info.arch.isNullOrBlank() -> add("arch=${info.arch}")
            }
            info.node?.takeIf { it.isNotBlank() }?.let { add("node=$it") }
            info.model?.takeIf { it.isNotBlank() }?.let { add("model=$it") }
            info.defaultModel?.takeIf { it.isNotBlank() }?.let { add("default_model=$it") }
            info.shell?.takeIf { it.isNotBlank() }?.let { add("shell=$it") }
            info.channel?.takeIf { it.isNotBlank() }?.let { add("channel=$it") }
            if (!info.channel.isNullOrBlank()) {
                val capabilities = if (info.capabilities.isEmpty()) "none" else info.capabilities.joinToString(",")
                add("capabilities=$capabilities")
            }
            add("thinking=${info.thinking?.ifBlank { null } ?: "off"}")
        }.joinToString(" | ")

        return buildString {
            append("## Runtime\n")
            append("Runtime: $runtimeLine\n")
            val reasoningLevel = info.reasoning?.ifBlank { null } ?: "off"
            append("Reasoning: $reasoningLevel (hidden unless on/stream). Toggle /reasoning; /status shows Reasoning when enabled.")
        }
    }

    private fun buildModelSection(modelId: String?, provider: String?): String {
        return buildString {
            append("# Model Routing\n")
            provider?.takeIf { it.isNotBlank() }?.let { append("- Provider: $it\n") }
            modelId?.takeIf { it.isNotBlank() }?.let { append("- Model: $it\n") }
        }
    }

    private fun sanitizePromptLiteral(value: String): String {
        if (value.isEmpty()) return value
        val sanitized = StringBuilder(value.length)
        for (ch in value) {
            if (ch == '\n' || ch == '\r' || ch == '\t') {
                sanitized.append(' ')
                continue
            }
            if (ch.code in 0..31 || ch == '\u007f') {
                continue
            }
            sanitized.append(ch)
        }
        return sanitized.toString().trim()
    }

    private companion object {
        private const val SILENT_REPLY_TOKEN = "NO_REPLY"
        private const val HEARTBEAT_TOKEN = "HEARTBEAT_OK"
    }
}
