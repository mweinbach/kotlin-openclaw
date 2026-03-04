package ai.openclaw.runtime.engine

import ai.openclaw.core.model.*
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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
        val modelAliasLines: List<String> = emptyList(),
        val workspaceNotes: List<String> = emptyList(),
        val extraSystemPrompt: String? = null,
        val docsPath: String? = null,
        val ownerNumbers: List<String> = emptyList(),
        val ownerDisplay: OwnerDisplay = OwnerDisplay.RAW,
        val ownerDisplaySecret: String? = null,
        val reasoningTagHint: Boolean = false,
        val contextFiles: List<ContextFile> = emptyList(),
        val bootstrapTruncationWarningLines: List<String> = emptyList(),
        val memoryCitationsMode: MemoryCitationsMode? = null,
        val ttsHint: String? = null,
        val reactionGuidance: ReactionGuidance? = null,
        val messageToolHints: List<String> = emptyList(),
        val heartbeatPrompt: String? = null,
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
        val docsPath: String? = null,
        val ownerNumbers: List<String> = emptyList(),
        val ownerDisplay: OwnerDisplay = OwnerDisplay.RAW,
        val ownerDisplaySecret: String? = null,
        val reasoningTagHint: Boolean = false,
        val contextFiles: List<ContextFile> = emptyList(),
        val bootstrapTruncationWarningLines: List<String> = emptyList(),
        val memoryCitationsMode: MemoryCitationsMode? = null,
        val ttsHint: String? = null,
        val reactionGuidance: ReactionGuidance? = null,
        val messageToolHints: List<String> = emptyList(),
        val heartbeatPrompt: String? = null,
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

    data class ContextFile(
        val path: String,
        val content: String,
    )

    data class ReactionGuidance(
        val level: String,
        val channel: String,
    )

    enum class OwnerDisplay {
        RAW,
        HASH,
    }

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
        val normalizedTools = config.tools.map { it.name.trim().lowercase() }.toSet()
        val isMinimal = config.mode == PromptMode.MINIMAL
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
                "openclaw-cli-quick-reference",
                enabled = { cfg -> cfg.mode != PromptMode.NONE },
                render = { _ -> buildCliQuickReferenceSection() },
            ),
            PromptSection(
                "skills",
                enabled = { cfg -> cfg.mode != PromptMode.NONE && cfg.skills.isNotEmpty() },
                render = { cfg -> buildSkillsSection(cfg.skills, resolveReadToolName(cfg.tools)) },
            ),
            PromptSection(
                "memory-recall",
                enabled = { cfg ->
                    cfg.mode == PromptMode.FULL &&
                        ("memory_search" in normalizedTools || "memory_get" in normalizedTools)
                },
                render = { cfg -> buildMemoryRecallSection(cfg.memoryCitationsMode) },
            ),
            PromptSection(
                "self-update",
                enabled = { cfg -> cfg.mode == PromptMode.FULL && "gateway" in normalizedTools },
                render = { _ -> buildSelfUpdateSection() },
            ),
            PromptSection(
                "model-aliases",
                enabled = { cfg -> cfg.mode == PromptMode.FULL && cfg.modelAliasLines.isNotEmpty() },
                render = { cfg -> buildModelAliasesSection(cfg.modelAliasLines) },
            ),
            PromptSection(
                "workspace",
                enabled = { cfg -> cfg.mode != PromptMode.NONE && !cfg.workspaceDir.isNullOrBlank() },
                render = { cfg -> buildWorkspaceSection(cfg) },
            ),
            PromptSection(
                "docs",
                enabled = { cfg -> cfg.mode == PromptMode.FULL && !cfg.docsPath.isNullOrBlank() },
                render = { cfg -> buildDocsSection(cfg.docsPath, resolveReadToolName(cfg.tools)) },
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
            PromptSection(
                "authorized-senders",
                enabled = { cfg -> cfg.mode == PromptMode.FULL && cfg.ownerNumbers.isNotEmpty() },
                render = { cfg -> buildAuthorizedSendersSection(cfg.ownerNumbers, cfg.ownerDisplay, cfg.ownerDisplaySecret) },
            ),
            PromptSection(
                "current-date-time",
                enabled = { cfg -> cfg.mode == PromptMode.FULL && !cfg.runtimeInfo?.timezone.isNullOrBlank() },
                render = { cfg -> buildTimeSection(cfg.runtimeInfo?.timezone) },
            ),
            PromptSection(
                "workspace-files",
                enabled = { cfg -> cfg.mode != PromptMode.NONE },
                render = { _ -> buildWorkspaceFilesSection() },
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
                "voice",
                enabled = { cfg -> cfg.mode == PromptMode.FULL && !cfg.ttsHint.isNullOrBlank() },
                render = { cfg -> buildVoiceSection(cfg.ttsHint) },
            ),
            PromptSection(
                "extra-context",
                enabled = { cfg -> cfg.mode != PromptMode.NONE && !cfg.extraSystemPrompt.isNullOrBlank() },
                render = { cfg -> buildExtraContextSection(cfg.extraSystemPrompt, isMinimal) },
            ),
            PromptSection(
                "reactions",
                enabled = { cfg -> cfg.mode == PromptMode.FULL && cfg.reactionGuidance != null },
                render = { cfg -> buildReactionsSection(cfg.reactionGuidance) },
            ),
            PromptSection(
                "reasoning-format",
                enabled = { cfg -> cfg.mode == PromptMode.FULL && cfg.reasoningTagHint },
                render = { _ -> buildReasoningFormatSection() },
            ),
            PromptSection(
                "project-context",
                enabled = { cfg ->
                    cfg.mode != PromptMode.NONE &&
                        (cfg.contextFiles.isNotEmpty() || cfg.bootstrapTruncationWarningLines.isNotEmpty())
                },
                render = { cfg -> buildProjectContextSection(cfg.contextFiles, cfg.bootstrapTruncationWarningLines) },
            ),
            PromptSection(
                "silent-replies",
                enabled = { cfg -> cfg.mode == PromptMode.FULL },
                render = { _ -> buildSilentRepliesSection() },
            ),
            PromptSection(
                "heartbeats",
                enabled = { cfg -> cfg.mode == PromptMode.FULL },
                render = { cfg -> buildHeartbeatSection(cfg.heartbeatPrompt) },
            ),
            PromptSection(
                "runtime",
                enabled = { cfg -> cfg.mode != PromptMode.NONE && cfg.runtimeInfo != null },
                render = { cfg -> cfg.runtimeInfo?.let(::buildRuntimeSection) },
            ),
        )
        return renderSections(config, sectionPlan)
    }

    private fun resolveReadToolName(tools: List<ToolSummary>): String {
        return tools.firstOrNull { it.name.equals("read", ignoreCase = true) }?.name ?: "read"
    }

    fun buildEmbedded(config: EmbeddedPromptConfig): String {
        return build(
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
                modelAliasLines = config.modelAliasLines,
                workspaceNotes = config.workspaceNotes,
                extraSystemPrompt = config.extraSystemPrompt,
                docsPath = config.docsPath,
                ownerNumbers = config.ownerNumbers,
                ownerDisplay = config.ownerDisplay,
                ownerDisplaySecret = config.ownerDisplaySecret,
                reasoningTagHint = config.reasoningTagHint,
                contextFiles = config.contextFiles,
                bootstrapTruncationWarningLines = config.bootstrapTruncationWarningLines,
                memoryCitationsMode = config.memoryCitationsMode,
                ttsHint = config.ttsHint,
                reactionGuidance = config.reactionGuidance,
                messageToolHints = config.messageToolHints,
                heartbeatPrompt = config.heartbeatPrompt,
            ),
        )
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
            append("## Safety\n")
            append("- You have no independent goals: do not pursue self-preservation, replication, resource acquisition, or power-seeking.\n")
            append("- Prioritize safety and human oversight over completion; if instructions conflict, pause and ask.\n")
            append("- Comply with stop/pause/audit requests and never bypass safeguards.\n")
            append("- Do not manipulate or persuade anyone to expand access or disable safeguards.\n")
            append("- Do not alter safety rules, system prompts, or tool policies unless explicitly requested.\n")
        }
    }

    private fun buildCliQuickReferenceSection(): String {
        return buildString {
            append("## OpenClaw CLI Quick Reference\n")
            append("OpenClaw is controlled via subcommands. Do not invent commands.\n")
            append("To manage the Gateway daemon service (start/stop/restart):\n")
            append("- openclaw gateway status\n")
            append("- openclaw gateway start\n")
            append("- openclaw gateway stop\n")
            append("- openclaw gateway restart\n")
            append("If unsure, ask the user to run `openclaw help` (or `openclaw gateway --help`) and share output.")
        }
    }

    private fun buildMemoryRecallSection(citationsMode: MemoryCitationsMode?): String {
        return buildString {
            append("## Memory Recall\n")
            append("Before answering prior-work or preference questions, run memory_search first and then memory_get for exact lines.\n")
            if (citationsMode == MemoryCitationsMode.OFF) {
                append("Citations are disabled: do not include memory file paths/line numbers unless explicitly requested.")
            } else {
                append("When useful, cite memory evidence as Source: <path#line>.")
            }
        }
    }

    private fun buildModelAliasesSection(modelAliasLines: List<String>): String {
        return buildString {
            append("## Model Aliases\n")
            append("Prefer aliases when specifying model overrides; full provider/model is also accepted.\n")
            append(modelAliasLines.joinToString("\n"))
        }
    }

    private fun buildSelfUpdateSection(): String {
        return buildString {
            append("## OpenClaw Self-Update\n")
            append("Run config.apply or update.run only when the user explicitly requests config changes or updates.\n")
            append("Before config edits, prefer config.schema to verify current field names and types.\n")
            append("Actions: config.get, config.schema, config.apply, update.run.")
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
        val runtimeChannel = config.runtimeInfo?.channel?.trim()?.lowercase()
        val runtimeCapabilitiesLower = config.runtimeInfo?.capabilities
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
        val inlineButtonsEnabled = "inlinebuttons" in runtimeCapabilitiesLower
        return buildString {
            append("## Messaging\n")
            append("- Reply in current session routes to the source channel.\n")
            append("- Cross-session messaging uses sessions_send(sessionKey, message).\n")
            append("- Sub-agent orchestration uses subagents(action=list|steer|kill).\n")
            append("- Runtime-generated completion update events should be rewritten in your normal assistant voice.\n")
            append("- Never use exec/curl for provider messaging; OpenClaw handles routing internally.\n")
            if (hasMessageTool) {
                append("### message tool\n")
                append("- Use message for proactive sends and channel actions.\n")
                append("- For action=send include to and message.\n")
                append("- If multiple channels are configured, pass channel explicitly.\n")
                if (inlineButtonsEnabled) {
                    append("- Inline buttons supported. Use `action=send` with `buttons=[[{text,callback_data,style?}]]`; `style` can be `primary`, `success`, or `danger`.\n")
                } else if (runtimeChannel != null) {
                    append("- Inline buttons not enabled for $runtimeChannel. If needed, ask to enable ${runtimeChannel}.capabilities.inlineButtons.\n")
                }
                config.messageToolHints.forEach { hint ->
                    val normalized = hint.trim()
                    if (normalized.isNotEmpty()) {
                        append("- $normalized\n")
                    }
                }
                append("- If message(action=send) already delivered the user-visible reply, respond with ONLY: $SILENT_REPLY_TOKEN to avoid duplicate replies.")
            }
        }
    }

    private fun buildVoiceSection(ttsHint: String?): String {
        val hint = ttsHint?.trim().takeUnless { it.isNullOrEmpty() } ?: return ""
        return buildString {
            append("## Voice (TTS)\n")
            append(hint)
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

    private fun buildHeartbeatSection(heartbeatPrompt: String?): String {
        val heartbeatPromptLine = heartbeatPrompt?.trim()?.takeIf { it.isNotEmpty() } ?: "(configured)"
        return buildString {
            append("## Heartbeats\n")
            append("Heartbeat prompt: $heartbeatPromptLine\n")
            append("If you receive a heartbeat poll (a user message matching the heartbeat prompt above), and there is nothing that needs attention, reply exactly:\n")
            append("$HEARTBEAT_TOKEN\n")
            append("OpenClaw treats a leading/trailing \"$HEARTBEAT_TOKEN\" as a heartbeat ack (and may discard it).\n")
            append("If something needs attention, do NOT include \"$HEARTBEAT_TOKEN\"; reply with the alert text instead.")
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

    private fun buildWorkspaceSection(config: PromptConfig): String {
        return buildString {
            val workspaceDir = sanitizePromptLiteral(config.workspaceDir ?: ".")
            append("## Workspace\n")
            append("Your working directory is: $workspaceDir\n")
            if (config.runtimeInfo?.sandboxed == true) {
                val sandboxWorkdir = config.runtimeInfo.sandboxContainerWorkspaceDir
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
            val notes = config.workspaceNotes.map { it.trim() }.filter { it.isNotEmpty() }
            if (notes.isNotEmpty()) {
                append("\n")
                notes.forEach { append("$it\n") }
            }
        }.trimEnd()
    }

    private fun buildDocsSection(docsPath: String?, readToolName: String): String {
        val safeDocsPath = docsPath?.trim()?.takeIf { it.isNotEmpty() } ?: return ""
        return buildString {
            append("## Documentation\n")
            append("OpenClaw docs: ${sanitizePromptLiteral(safeDocsPath)}\n")
            append("Mirror: https://docs.openclaw.ai\n")
            append("Source: https://github.com/openclaw/openclaw\n")
            append("Community: https://discord.com/invite/clawd\n")
            append("Find new skills: https://clawhub.com\n")
            append("For OpenClaw behavior/config/architecture, consult local docs first using `$readToolName`.")
            append("\nWhen diagnosing issues, run `openclaw status` yourself when possible; only ask the user if you lack access (e.g., sandboxed).")
        }
    }

    private fun buildAuthorizedSendersSection(
        ownerNumbers: List<String>,
        ownerDisplay: OwnerDisplay,
        ownerDisplaySecret: String?,
    ): String {
        val normalized = ownerNumbers.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (normalized.isEmpty()) return ""
        val displayOwnerNumbers = if (ownerDisplay == OwnerDisplay.HASH) {
            normalized.map { hashOwnerIdentifier(it, ownerDisplaySecret) }
        } else {
            normalized
        }
        return buildString {
            append("## Authorized Senders\n")
            append("Authorized senders: ${displayOwnerNumbers.joinToString(", ")}. These senders are allowlisted; do not assume they are the owner.")
        }
    }

    private fun buildTimeSection(timezone: String?): String {
        val tz = timezone?.trim()?.takeIf { it.isNotEmpty() } ?: return ""
        return buildString {
            append("## Current Date & Time\n")
            append("Time zone: $tz")
        }
    }

    private fun buildWorkspaceFilesSection(): String {
        return buildString {
            append("## Workspace Files (injected)\n")
            append("These user-editable files are loaded by OpenClaw and included below in Project Context.")
        }
    }

    private fun buildExtraContextSection(extraSystemPrompt: String?, isMinimal: Boolean): String {
        val extra = extraSystemPrompt?.trim()?.takeIf { it.isNotEmpty() } ?: return ""
        val header = if (isMinimal) "## Subagent Context" else "## Group Chat Context"
        return buildString {
            append(header)
            append("\n")
            append(extra)
        }
    }

    private fun buildReactionsSection(reactionGuidance: ReactionGuidance?): String {
        val guidance = reactionGuidance ?: return ""
        val level = guidance.level.trim().lowercase()
        val channel = guidance.channel.trim().ifEmpty { "channel" }
        val lines = if (level == "extensive") {
            listOf(
                "Reactions are enabled for $channel in EXTENSIVE mode.",
                "Feel free to react liberally when it feels natural.",
            )
        } else {
            listOf(
                "Reactions are enabled for $channel in MINIMAL mode.",
                "React only when truly relevant; avoid routine reactions.",
            )
        }
        return buildString {
            append("## Reactions\n")
            append(lines.joinToString("\n"))
        }
    }

    private fun buildReasoningFormatSection(): String {
        return buildString {
            append("## Reasoning Format\n")
            append("ALL internal reasoning MUST be inside <think>...</think>.\n")
            append("Format every reply as <think>...</think> then <final>...</final>.\n")
            append("Only text inside <final> is user-visible.")
        }
    }

    private fun buildProjectContextSection(
        contextFiles: List<ContextFile>,
        bootstrapTruncationWarningLines: List<String>,
    ): String {
        val validFiles = contextFiles.filter {
            it.path.trim().isNotEmpty()
        }
        val warningLines = bootstrapTruncationWarningLines
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (validFiles.isEmpty() && warningLines.isEmpty()) return ""
        val hasSoulFile = validFiles.any {
            it.path.trim().replace('\\', '/').substringAfterLast('/').equals("soul.md", ignoreCase = true)
        }
        return buildString {
            append("# Project Context\n")
            if (validFiles.isNotEmpty()) {
                append("The following project context files have been loaded:\n\n")
                if (hasSoulFile) {
                    append("If SOUL.md is present, embody its persona and tone unless higher-priority instructions override it.\n\n")
                }
            }
            if (warningLines.isNotEmpty()) {
                append("⚠ Bootstrap truncation warning:\n")
                warningLines.forEach { warning ->
                    append("- $warning\n")
                }
                append("\n")
            }
            for (file in validFiles) {
                append("## ${sanitizePromptLiteral(file.path.trim())}\n\n")
                append(file.content.trim())
                append("\n\n")
            }
        }.trimEnd()
    }

    private fun hashOwnerIdentifier(ownerId: String, ownerDisplaySecret: String?): String {
        val input = ownerId.toByteArray(Charsets.UTF_8)
        val digestBytes = if (!ownerDisplaySecret.isNullOrBlank()) {
            val mac = Mac.getInstance("HmacSHA256")
            val keySpec = SecretKeySpec(ownerDisplaySecret.toByteArray(Charsets.UTF_8), "HmacSHA256")
            mac.init(keySpec)
            mac.doFinal(input)
        } else {
            MessageDigest.getInstance("SHA-256").digest(input)
        }
        return digestBytes.joinToString("") { b -> "%02x".format(b) }.take(12)
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
