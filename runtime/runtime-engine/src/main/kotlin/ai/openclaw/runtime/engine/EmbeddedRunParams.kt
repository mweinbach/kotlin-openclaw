package ai.openclaw.runtime.engine

import ai.openclaw.core.agent.LlmMessage
import ai.openclaw.core.model.DEFAULT_AGENT_ID
import ai.openclaw.core.model.GroupToolPolicyConfig
import ai.openclaw.core.model.IdentityConfig
import ai.openclaw.core.model.MemoryCitationsMode
import ai.openclaw.core.plugins.BeforeAgentStartResult

/**
 * Typed execution context for a single embedded turn.
 * Mirrors the reference runner's trigger/channel metadata flow.
 */
data class EmbeddedTurnContext(
    val trigger: String? = null,
    val messageProvider: String? = null,
    val channelId: String? = null,
    val sessionId: String? = null,
    val senderIsOwner: Boolean? = null,
    val sandboxed: Boolean? = null,
    val groupToolPolicy: GroupToolPolicyConfig? = null,
)

data class ClientToolDefinition(
    val name: String,
    val description: String = "Client-side hosted tool",
    val parameters: String = """{"type":"object","properties":{},"additionalProperties":true}""",
)

/**
 * Structured runner params used by AgentRunner.
 * Keeps turn wiring explicit and aligned with the reference runtime architecture.
 */
data class EmbeddedRunParams(
    val messages: List<LlmMessage>,
    val model: String,
    val systemPrompt: String? = null,
    val runId: String? = null,
    val sessionKey: String = "",
    val agentId: String = DEFAULT_AGENT_ID,
    val agentIdentity: IdentityConfig? = null,
    val channelContext: SystemPromptBuilder.ChannelContext? = null,
    val skills: List<SystemPromptBuilder.SkillSummary> = emptyList(),
    val runtimeInfo: SystemPromptBuilder.RuntimeInfo? = null,
    val workspaceDir: String? = null,
    val maxHistoryTurns: Int? = null,
    val timeoutMs: Long? = null,
    val maxRunAttempts: Int? = null,
    val hookSessionId: String? = null,
    val legacyBeforeAgentStartResult: BeforeAgentStartResult? = null,
    val modelAliasLines: List<String> = emptyList(),
    val workspaceNotes: List<String> = emptyList(),
    val docsPath: String? = null,
    val ownerNumbers: List<String> = emptyList(),
    val ownerDisplay: SystemPromptBuilder.OwnerDisplay = SystemPromptBuilder.OwnerDisplay.RAW,
    val ownerDisplaySecret: String? = null,
    val reasoningTagHint: Boolean = false,
    val reasoningEffort: String? = null,
    val extraSystemPrompt: String? = null,
    val contextFiles: List<SystemPromptBuilder.ContextFile> = emptyList(),
    val bootstrapTruncationWarningLines: List<String> = emptyList(),
    val memoryCitationsMode: MemoryCitationsMode? = null,
    val ttsHint: String? = null,
    val reactionGuidance: SystemPromptBuilder.ReactionGuidance? = null,
    val messageToolHints: List<String> = emptyList(),
    val heartbeatPrompt: String? = null,
    val turnContext: EmbeddedTurnContext = EmbeddedTurnContext(),
    val clientTools: List<ClientToolDefinition> = emptyList(),
)
