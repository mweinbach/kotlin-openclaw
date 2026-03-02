package ai.openclaw.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Base Config Types (ported from src/config/types.base.ts) ---

@Serializable
enum class ReplyMode {
    @SerialName("text") TEXT,
    @SerialName("command") COMMAND,
}

@Serializable
enum class TypingMode {
    @SerialName("never") NEVER,
    @SerialName("instant") INSTANT,
    @SerialName("thinking") THINKING,
    @SerialName("message") MESSAGE,
}

@Serializable
enum class ReplyToMode {
    @SerialName("off") OFF,
    @SerialName("first") FIRST,
    @SerialName("all") ALL,
}

@Serializable
enum class GroupPolicy {
    @SerialName("open") OPEN,
    @SerialName("disabled") DISABLED,
    @SerialName("allowlist") ALLOWLIST,
}

@Serializable
enum class DmPolicy {
    @SerialName("pairing") PAIRING,
    @SerialName("allowlist") ALLOWLIST,
    @SerialName("open") OPEN,
    @SerialName("disabled") DISABLED,
}

@Serializable
data class OutboundRetryConfig(
    val attempts: Int? = null,
    val minDelayMs: Long? = null,
    val maxDelayMs: Long? = null,
    val jitter: Double? = null,
)

@Serializable
data class BlockStreamingCoalesceConfig(
    val minChars: Int? = null,
    val idleMs: Long? = null,
)

@Serializable
data class BlockStreamingChunkConfig(
    val minChars: Int? = null,
    val maxChars: Int? = null,
    val breakPreference: String? = null,
)

@Serializable
enum class MarkdownTableMode {
    @SerialName("off") OFF,
    @SerialName("bullets") BULLETS,
    @SerialName("code") CODE,
}

@Serializable
data class MarkdownConfig(
    val tables: MarkdownTableMode? = null,
)

@Serializable
data class HumanDelayConfig(
    val mode: String? = null,
    val minMs: Long? = null,
    val maxMs: Long? = null,
)

@Serializable
data class SessionSendPolicyMatch(
    val channel: String? = null,
    val chatType: ChatType? = null,
    val keyPrefix: String? = null,
    val rawKeyPrefix: String? = null,
)

@Serializable
data class SessionSendPolicyRule(
    val action: SendPolicyDecision,
    val match: SessionSendPolicyMatch? = null,
)

@Serializable
data class SessionSendPolicyConfig(
    val default: SendPolicyDecision? = null,
    val rules: List<SessionSendPolicyRule>? = null,
)

@Serializable
enum class SessionResetMode {
    @SerialName("daily") DAILY,
    @SerialName("idle") IDLE,
}

@Serializable
data class SessionResetConfig(
    val mode: SessionResetMode? = null,
    val atHour: Int? = null,
    val idleMinutes: Int? = null,
)

@Serializable
data class SessionResetByTypeConfig(
    val direct: SessionResetConfig? = null,
    val dm: SessionResetConfig? = null,
    val group: SessionResetConfig? = null,
    val thread: SessionResetConfig? = null,
)

@Serializable
data class SessionThreadBindingsConfig(
    val enabled: Boolean? = null,
    val idleHours: Int? = null,
    val maxAgeHours: Int? = null,
)

@Serializable
enum class SessionMaintenanceMode {
    @SerialName("enforce") ENFORCE,
    @SerialName("warn") WARN,
}

@Serializable
data class SessionMaintenanceConfig(
    val mode: SessionMaintenanceMode? = null,
    val pruneAfter: String? = null,
    val pruneDays: Int? = null,
    val maxEntries: Int? = null,
    val rotateBytes: Long? = null,
    val resetArchiveRetention: String? = null,
    val maxDiskBytes: Long? = null,
    val highWaterBytes: Long? = null,
)

@Serializable
data class SessionConfig(
    val scope: SessionScope? = null,
    val dmScope: DmScope? = null,
    val identityLinks: Map<String, List<String>>? = null,
    val resetTriggers: List<String>? = null,
    val idleMinutes: Int? = null,
    val reset: SessionResetConfig? = null,
    val resetByType: SessionResetByTypeConfig? = null,
    val resetByChannel: Map<String, SessionResetConfig>? = null,
    val store: String? = null,
    val typingIntervalSeconds: Int? = null,
    val typingMode: TypingMode? = null,
    val parentForkMaxTokens: Int? = null,
    val mainKey: String? = null,
    val sendPolicy: SessionSendPolicyConfig? = null,
    val agentToAgent: AgentToAgentConfig? = null,
    val threadBindings: SessionThreadBindingsConfig? = null,
    val maintenance: SessionMaintenanceConfig? = null,
)

@Serializable
data class AgentToAgentConfig(
    val maxPingPongTurns: Int? = null,
)

@Serializable
data class LoggingConfig(
    val level: String? = null,
    val file: String? = null,
    val maxFileBytes: Long? = null,
    val consoleLevel: String? = null,
    val consoleStyle: String? = null,
    val redactSensitive: String? = null,
    val redactPatterns: List<String>? = null,
)

@Serializable
data class DiagnosticsOtelConfig(
    val enabled: Boolean? = null,
    val endpoint: String? = null,
    val protocol: String? = null,
    val headers: Map<String, String>? = null,
    val serviceName: String? = null,
    val services: Boolean? = null,
    val metrics: Boolean? = null,
    val logs: Boolean? = null,
    val sampleRate: Double? = null,
    val flushIntervalMs: Long? = null,
)

@Serializable
data class DiagnosticsConfig(
    val enabled: Boolean? = null,
    val flags: List<String>? = null,
    val stuckSessionWarnMs: Long? = null,
    val otel: DiagnosticsOtelConfig? = null,
)

@Serializable
data class IdentityConfig(
    val name: String? = null,
    val theme: String? = null,
    val emoji: String? = null,
    val avatar: String? = null,
)
