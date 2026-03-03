package ai.openclaw.runtime.engine

import ai.openclaw.core.agent.LlmMessage
import ai.openclaw.core.model.DEFAULT_AGENT_ID
import ai.openclaw.core.model.IdentityConfig
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
    val hookSessionId: String? = null,
    val legacyBeforeAgentStartResult: BeforeAgentStartResult? = null,
    val turnContext: EmbeddedTurnContext = EmbeddedTurnContext(),
)
