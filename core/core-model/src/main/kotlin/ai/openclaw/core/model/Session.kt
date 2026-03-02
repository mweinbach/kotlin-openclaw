package ai.openclaw.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Session Types (ported from src/config/sessions/types.ts) ---

@Serializable
enum class SessionScope {
    @SerialName("per-sender") PER_SENDER,
    @SerialName("global") GLOBAL,
}

@Serializable
enum class DmScope {
    @SerialName("main") MAIN,
    @SerialName("per-peer") PER_PEER,
    @SerialName("per-channel-peer") PER_CHANNEL_PEER,
    @SerialName("per-account-channel-peer") PER_ACCOUNT_CHANNEL_PEER,
}

@Serializable
enum class ChatType {
    @SerialName("direct") DIRECT,
    @SerialName("group") GROUP,
    @SerialName("channel") CHANNEL,
}

@Serializable
data class SessionOrigin(
    val label: String? = null,
    val provider: String? = null,
    val surface: String? = null,
    val chatType: ChatType? = null,
    val from: String? = null,
    val to: String? = null,
    val accountId: String? = null,
    val threadId: String? = null,
)

@Serializable
enum class SessionAcpIdentitySource {
    @SerialName("ensure") ENSURE,
    @SerialName("status") STATUS,
    @SerialName("event") EVENT,
}

@Serializable
enum class SessionAcpIdentityState {
    @SerialName("pending") PENDING,
    @SerialName("resolved") RESOLVED,
}

@Serializable
data class SessionAcpIdentity(
    val state: SessionAcpIdentityState,
    val acpxRecordId: String? = null,
    val acpxSessionId: String? = null,
    val agentSessionId: String? = null,
    val source: SessionAcpIdentitySource,
    val lastUpdatedAt: Long,
)

@Serializable
enum class AcpSessionState {
    @SerialName("idle") IDLE,
    @SerialName("running") RUNNING,
    @SerialName("error") ERROR,
}

@Serializable
data class AcpSessionRuntimeOptions(
    val runtimeMode: String? = null,
    val model: String? = null,
    val cwd: String? = null,
    val permissionProfile: String? = null,
    val timeoutSeconds: Int? = null,
    val backendExtras: Map<String, String>? = null,
)

@Serializable
data class SessionAcpMeta(
    val backend: String,
    val agent: String,
    val runtimeSessionName: String,
    val identity: SessionAcpIdentity? = null,
    val mode: AcpRuntimeSessionMode,
    val runtimeOptions: AcpSessionRuntimeOptions? = null,
    val cwd: String? = null,
    val state: AcpSessionState,
    val lastActivityAt: Long,
    val lastError: String? = null,
)

@Serializable
enum class QueueMode {
    @SerialName("steer") STEER,
    @SerialName("followup") FOLLOWUP,
    @SerialName("collect") COLLECT,
    @SerialName("steer-backlog") STEER_BACKLOG,
    @SerialName("steer+backlog") STEER_PLUS_BACKLOG,
    @SerialName("queue") QUEUE,
    @SerialName("interrupt") INTERRUPT,
}

@Serializable
enum class QueueDrop {
    @SerialName("old") OLD,
    @SerialName("new") NEW,
    @SerialName("summarize") SUMMARIZE,
}

@Serializable
enum class ResponseUsage {
    @SerialName("on") ON,
    @SerialName("off") OFF,
    @SerialName("tokens") TOKENS,
    @SerialName("full") FULL,
}

@Serializable
enum class GroupActivation {
    @SerialName("mention") MENTION,
    @SerialName("always") ALWAYS,
}

@Serializable
enum class SendPolicyDecision {
    @SerialName("allow") ALLOW,
    @SerialName("deny") DENY,
}

@Serializable
data class DeliveryContext(
    val channel: String? = null,
    val accountId: String? = null,
    val to: String? = null,
    val threadId: String? = null,
)

@Serializable
enum class TtsAutoMode {
    @SerialName("off") OFF,
    @SerialName("dm") DM,
    @SerialName("all") ALL,
}

@Serializable
data class SessionSkillSnapshot(
    val prompt: String,
    val skills: List<SkillSnapshotEntry>,
    val skillFilter: List<String>? = null,
    val version: Int? = null,
)

@Serializable
data class SkillSnapshotEntry(
    val name: String,
    val primaryEnv: String? = null,
    val requiredEnv: List<String>? = null,
)

@Serializable
data class SessionSystemPromptReport(
    val source: String,
    val generatedAt: Long,
    val sessionId: String? = null,
    val sessionKey: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val workspaceDir: String? = null,
    val bootstrapMaxChars: Int? = null,
    val bootstrapTotalMaxChars: Int? = null,
    val sandbox: SandboxInfo? = null,
    val systemPrompt: SystemPromptInfo? = null,
    val injectedWorkspaceFiles: List<InjectedWorkspaceFile>? = null,
    val skills: SkillsPromptInfo? = null,
    val tools: ToolsPromptInfo? = null,
)

@Serializable
data class SandboxInfo(
    val mode: String? = null,
    val sandboxed: Boolean? = null,
)

@Serializable
data class SystemPromptInfo(
    val chars: Int,
    val projectContextChars: Int,
    val nonProjectContextChars: Int,
)

@Serializable
data class InjectedWorkspaceFile(
    val name: String,
    val path: String,
    val missing: Boolean,
    val rawChars: Int,
    val injectedChars: Int,
    val truncated: Boolean,
)

@Serializable
data class SkillsPromptInfo(
    val promptChars: Int,
    val entries: List<SkillPromptEntry>,
)

@Serializable
data class SkillPromptEntry(
    val name: String,
    val blockChars: Int,
)

@Serializable
data class ToolsPromptInfo(
    val listChars: Int,
    val schemaChars: Int,
    val entries: List<ToolPromptEntry>,
)

@Serializable
data class ToolPromptEntry(
    val name: String,
    val summaryChars: Int,
    val schemaChars: Int,
    val propertiesCount: Int? = null,
)

@Serializable
data class SessionEntry(
    val sessionId: String,
    val updatedAt: Long,
    val sessionFile: String? = null,
    val spawnedBy: String? = null,
    val forkedFromParent: Boolean? = null,
    val spawnDepth: Int? = null,
    val systemSent: Boolean? = null,
    val abortedLastRun: Boolean? = null,
    val abortCutoffMessageSid: String? = null,
    val abortCutoffTimestamp: Long? = null,
    val chatType: ChatType? = null,
    val thinkingLevel: String? = null,
    val verboseLevel: String? = null,
    val reasoningLevel: String? = null,
    val elevatedLevel: String? = null,
    val ttsAuto: TtsAutoMode? = null,
    val execHost: String? = null,
    val execSecurity: String? = null,
    val execAsk: String? = null,
    val execNode: String? = null,
    val responseUsage: ResponseUsage? = null,
    val providerOverride: String? = null,
    val modelOverride: String? = null,
    val authProfileOverride: String? = null,
    val authProfileOverrideSource: String? = null,
    val authProfileOverrideCompactionCount: Int? = null,
    val groupActivation: GroupActivation? = null,
    val groupActivationNeedsSystemIntro: Boolean? = null,
    val sendPolicy: SendPolicyDecision? = null,
    val queueMode: QueueMode? = null,
    val queueDebounceMs: Long? = null,
    val queueCap: Int? = null,
    val queueDrop: QueueDrop? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val totalTokensFresh: Boolean? = null,
    val cacheRead: Int? = null,
    val cacheWrite: Int? = null,
    val modelProvider: String? = null,
    val model: String? = null,
    val fallbackNoticeSelectedModel: String? = null,
    val fallbackNoticeActiveModel: String? = null,
    val fallbackNoticeReason: String? = null,
    val contextTokens: Int? = null,
    val compactionCount: Int? = null,
    val memoryFlushAt: Long? = null,
    val memoryFlushCompactionCount: Int? = null,
    val cliSessionIds: Map<String, String>? = null,
    val claudeCliSessionId: String? = null,
    val label: String? = null,
    val displayName: String? = null,
    val channel: String? = null,
    val groupId: String? = null,
    val subject: String? = null,
    val groupChannel: String? = null,
    val space: String? = null,
    val origin: SessionOrigin? = null,
    val deliveryContext: DeliveryContext? = null,
    val lastChannel: String? = null,
    val lastTo: String? = null,
    val lastAccountId: String? = null,
    val lastThreadId: String? = null,
    val lastHeartbeatText: String? = null,
    val lastHeartbeatSentAt: Long? = null,
    val skillsSnapshot: SessionSkillSnapshot? = null,
    val systemPromptReport: SessionSystemPromptReport? = null,
    val acp: SessionAcpMeta? = null,
) {
    companion object {
        val DEFAULT_RESET_TRIGGER = "/new"
        val DEFAULT_RESET_TRIGGERS = listOf("/new", "/reset")
        val DEFAULT_IDLE_MINUTES = 60
    }
}
