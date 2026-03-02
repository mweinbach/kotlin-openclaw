package ai.openclaw.core.model

import kotlinx.serialization.Serializable

// --- Root Config (ported from src/config/types.openclaw.ts) ---

@Serializable
data class AuthProfileConfig(
    val provider: String,
    val mode: String,
    val email: String? = null,
)

@Serializable
data class AuthCooldownsConfig(
    val billingBackoffHours: Int? = null,
    val billingBackoffHoursByProvider: Map<String, Int>? = null,
    val billingMaxHours: Int? = null,
    val failureWindowHours: Int? = null,
)

@Serializable
data class AuthConfig(
    val profiles: Map<String, AuthProfileConfig>? = null,
    val order: Map<String, List<String>>? = null,
    val cooldowns: AuthCooldownsConfig? = null,
)

@Serializable
data class AcpDispatchConfig(
    val enabled: Boolean? = null,
)

@Serializable
data class AcpStreamConfig(
    val coalesceIdleMs: Long? = null,
    val maxChunkChars: Int? = null,
    val repeatSuppression: Boolean? = null,
    val deliveryMode: String? = null,
    val hiddenBoundarySeparator: String? = null,
    val maxOutputChars: Int? = null,
    val maxSessionUpdateChars: Int? = null,
)

@Serializable
data class AcpRuntimeConfig(
    val ttlMinutes: Int? = null,
    val installCommand: String? = null,
)

@Serializable
data class AcpConfig(
    val enabled: Boolean? = null,
    val dispatch: AcpDispatchConfig? = null,
    val backend: String? = null,
    val defaultAgent: String? = null,
    val allowedAgents: List<String>? = null,
    val maxConcurrentSessions: Int? = null,
    val stream: AcpStreamConfig? = null,
    val runtime: AcpRuntimeConfig? = null,
)

@Serializable
data class GatewayAuthConfig(
    val mode: String? = null,
    val token: String? = null,
    val password: String? = null,
    val allowTailscale: Boolean? = null,
)

@Serializable
data class GatewayTlsConfig(
    val enabled: Boolean? = null,
    val autoGenerate: Boolean? = null,
    val certPath: String? = null,
    val keyPath: String? = null,
    val caPath: String? = null,
)

@Serializable
data class GatewayConfig(
    val port: Int? = null,
    val mode: String? = null,
    val bind: String? = null,
    val customBindHost: String? = null,
    val auth: GatewayAuthConfig? = null,
    val tls: GatewayTlsConfig? = null,
    val channelHealthCheckMinutes: Int? = null,
)

@Serializable
data class HookMappingConfig(
    val id: String? = null,
    val action: String? = null,
    val name: String? = null,
    val agentId: String? = null,
    val sessionKey: String? = null,
    val messageTemplate: String? = null,
    val textTemplate: String? = null,
    val deliver: Boolean? = null,
    val channel: String? = null,
    val to: String? = null,
    val model: String? = null,
    val thinking: String? = null,
    val timeoutSeconds: Int? = null,
)

@Serializable
data class HooksConfig(
    val enabled: Boolean? = null,
    val path: String? = null,
    val token: String? = null,
    val defaultSessionKey: String? = null,
    val allowRequestSessionKey: Boolean? = null,
    val allowedSessionKeyPrefixes: List<String>? = null,
    val allowedAgentIds: List<String>? = null,
    val maxBodyBytes: Long? = null,
    val mappings: List<HookMappingConfig>? = null,
)

@Serializable
data class MemoryConfig(
    val backend: String? = null,
    val citations: String? = null,
)

@Serializable
data class ToolsConfig(
    val profile: String? = null,
    val allow: List<String>? = null,
    val alsoAllow: List<String>? = null,
    val deny: List<String>? = null,
)

@Serializable
data class MessagesConfig(
    val responsePrefix: String? = null,
    val maxLength: Int? = null,
)

@Serializable
data class CronConfig(
    val enabled: Boolean? = null,
)

@Serializable
data class PluginsConfig(
    val enabled: Boolean? = null,
    val entries: Map<String, PluginEntryConfig>? = null,
)

@Serializable
data class PluginEntryConfig(
    val enabled: Boolean? = null,
    val path: String? = null,
)

@Serializable
data class OpenClawConfig(
    val auth: AuthConfig? = null,
    val acp: AcpConfig? = null,
    val diagnostics: DiagnosticsConfig? = null,
    val logging: LoggingConfig? = null,
    val skills: SkillsConfig? = null,
    val plugins: PluginsConfig? = null,
    val models: ModelsConfig? = null,
    val agents: AgentsConfig? = null,
    val tools: ToolsConfig? = null,
    val bindings: List<AgentBinding>? = null,
    val messages: MessagesConfig? = null,
    val session: SessionConfig? = null,
    val channels: ChannelsConfig? = null,
    val cron: CronConfig? = null,
    val hooks: HooksConfig? = null,
    val gateway: GatewayConfig? = null,
    val memory: MemoryConfig? = null,
)

@Serializable
data class ConfigValidationIssue(
    val path: String,
    val message: String,
)
