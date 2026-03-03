package ai.openclaw.core.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Plugin Hook Names and Event Types (ported from src/plugins/types.ts) ---

@Serializable
enum class PluginHookName {
    @SerialName("before_model_resolve") BEFORE_MODEL_RESOLVE,
    @SerialName("before_prompt_build") BEFORE_PROMPT_BUILD,
    @SerialName("before_agent_start") BEFORE_AGENT_START,
    @SerialName("llm_input") LLM_INPUT,
    @SerialName("llm_output") LLM_OUTPUT,
    @SerialName("agent_end") AGENT_END,
    @SerialName("before_compaction") BEFORE_COMPACTION,
    @SerialName("after_compaction") AFTER_COMPACTION,
    @SerialName("before_reset") BEFORE_RESET,
    @SerialName("message_received") MESSAGE_RECEIVED,
    @SerialName("message_sending") MESSAGE_SENDING,
    @SerialName("message_sent") MESSAGE_SENT,
    @SerialName("before_tool_call") BEFORE_TOOL_CALL,
    @SerialName("after_tool_call") AFTER_TOOL_CALL,
    @SerialName("tool_result_persist") TOOL_RESULT_PERSIST,
    @SerialName("before_message_write") BEFORE_MESSAGE_WRITE,
    @SerialName("session_start") SESSION_START,
    @SerialName("session_end") SESSION_END,
    @SerialName("subagent_spawning") SUBAGENT_SPAWNING,
    @SerialName("subagent_delivery_target") SUBAGENT_DELIVERY_TARGET,
    @SerialName("subagent_spawned") SUBAGENT_SPAWNED,
    @SerialName("subagent_ended") SUBAGENT_ENDED,
    @SerialName("gateway_start") GATEWAY_START,
    @SerialName("gateway_stop") GATEWAY_STOP,
}

@Serializable
enum class PluginOrigin {
    @SerialName("bundled") BUNDLED,
    @SerialName("global") GLOBAL,
    @SerialName("workspace") WORKSPACE,
    @SerialName("config") CONFIG,
}

@Serializable
enum class PluginDiagnosticLevel {
    @SerialName("warn") WARN,
    @SerialName("error") ERROR,
}

@Serializable
data class PluginDiagnostic(
    val level: PluginDiagnosticLevel,
    val message: String,
    val pluginId: String? = null,
    val source: String? = null,
)

// --- Hook Context Types ---

data class PluginHookAgentContext(
    val agentId: String? = null,
    val sessionKey: String? = null,
    val sessionId: String? = null,
    val workspaceDir: String? = null,
    val messageProvider: String? = null,
    val trigger: String? = null,
    val channelId: String? = null,
)

data class PluginHookMessageContext(
    val channelId: String,
    val accountId: String? = null,
    val conversationId: String? = null,
)

data class PluginHookToolContext(
    val agentId: String? = null,
    val sessionKey: String? = null,
    val sessionId: String? = null,
    val runId: String? = null,
    val toolName: String,
    val toolCallId: String? = null,
)

data class PluginHookSessionContext(
    val agentId: String? = null,
    val sessionId: String,
)

data class PluginHookSubagentContext(
    val runId: String? = null,
    val childSessionKey: String? = null,
    val requesterSessionKey: String? = null,
)

data class PluginHookGatewayContext(
    val port: Int? = null,
)

// --- Hook Event Types ---

data class BeforeModelResolveEvent(
    val prompt: String,
)

data class BeforeModelResolveResult(
    val modelOverride: String? = null,
    val providerOverride: String? = null,
)

data class BeforePromptBuildEvent(
    val prompt: String,
    val messages: List<Any?> = emptyList(),
)

data class BeforePromptBuildResult(
    val systemPrompt: String? = null,
    val prependContext: String? = null,
)

data class BeforeAgentStartEvent(
    val prompt: String,
    val messages: List<Any?>? = null,
)

data class BeforeAgentStartResult(
    val systemPrompt: String? = null,
    val prependContext: String? = null,
    val modelOverride: String? = null,
    val providerOverride: String? = null,
)

data class LlmInputEvent(
    val runId: String,
    val sessionId: String,
    val provider: String,
    val model: String,
    val systemPrompt: String? = null,
    val prompt: String,
    val historyMessages: List<Any?> = emptyList(),
    val imagesCount: Int = 0,
)

data class LlmOutputEvent(
    val runId: String,
    val sessionId: String,
    val provider: String,
    val model: String,
    val assistantTexts: List<String>,
    val lastAssistant: Any? = null,
    val usage: LlmUsage? = null,
)

data class LlmUsage(
    val input: Int? = null,
    val output: Int? = null,
    val cacheRead: Int? = null,
    val cacheWrite: Int? = null,
    val total: Int? = null,
)

data class AgentEndEvent(
    val success: Boolean,
    val error: String? = null,
    val durationMs: Long? = null,
    val messages: List<Any?> = emptyList(),
)

data class BeforeCompactionEvent(
    val messageCount: Int,
    val compactingCount: Int? = null,
    val tokenCount: Int? = null,
    val sessionFile: String? = null,
)

data class AfterCompactionEvent(
    val messageCount: Int,
    val tokenCount: Int? = null,
    val compactedCount: Int,
    val sessionFile: String? = null,
)

data class BeforeResetEvent(
    val sessionFile: String? = null,
    val reason: String? = null,
)

data class MessageReceivedEvent(
    val from: String,
    val content: String,
    val timestamp: Long? = null,
    val metadata: Map<String, String>? = null,
)

data class MessageSendingEvent(
    val to: String,
    val content: String,
    val metadata: Map<String, String>? = null,
)

data class MessageSendingResult(
    val content: String? = null,
    val cancel: Boolean? = null,
)

data class MessageSentEvent(
    val to: String,
    val content: String,
    val success: Boolean,
    val error: String? = null,
)

data class BeforeToolCallEvent(
    val toolName: String,
    val params: Map<String, Any?>,
    val runId: String? = null,
    val toolCallId: String? = null,
)

data class BeforeToolCallResult(
    val params: Map<String, Any?>? = null,
    val block: Boolean? = null,
    val blockReason: String? = null,
)

data class AfterToolCallEvent(
    val toolName: String,
    val params: Map<String, Any?>,
    val runId: String? = null,
    val toolCallId: String? = null,
    val result: Any? = null,
    val error: String? = null,
    val durationMs: Long? = null,
)

data class SessionStartEvent(
    val sessionId: String,
    val resumedFrom: String? = null,
)

data class SessionEndEvent(
    val sessionId: String,
    val messageCount: Int,
    val durationMs: Long? = null,
)

data class SubagentSpawningEvent(
    val childSessionKey: String,
    val agentId: String,
    val label: String? = null,
    val mode: String,
    val threadRequested: Boolean,
)

@Serializable
sealed class SubagentSpawningResult {
    @Serializable
    data class Ok(val threadBindingReady: Boolean? = null) : SubagentSpawningResult()

    @Serializable
    data class Error(val error: String) : SubagentSpawningResult()
}

data class SubagentSpawnedEvent(
    val runId: String,
    val childSessionKey: String,
    val agentId: String,
    val label: String? = null,
    val mode: String,
    val threadRequested: Boolean,
)

data class SubagentEndedEvent(
    val targetSessionKey: String,
    val targetKind: String,
    val reason: String,
    val sendFarewell: Boolean? = null,
    val accountId: String? = null,
    val runId: String? = null,
    val endedAt: Long? = null,
    val outcome: String? = null,
    val error: String? = null,
)

data class GatewayStartEvent(
    val port: Int,
)

data class GatewayStopEvent(
    val reason: String? = null,
)
