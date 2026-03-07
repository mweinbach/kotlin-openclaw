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
    val mode: GatewayAuthMode? = null,
    val token: String? = null,
    val password: String? = null,
    val allowTailscale: Boolean? = null,
    val rateLimit: GatewayAuthRateLimitConfig? = null,
    val trustedProxy: GatewayTrustedProxyConfig? = null,
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
    val bind: GatewayBindMode? = null,
    val customBindHost: String? = null,
    val controlUi: GatewayControlUiConfig? = null,
    val auth: GatewayAuthConfig? = null,
    val tailscale: GatewayTailscaleConfig? = null,
    val remote: GatewayRemoteConfig? = null,
    val reload: GatewayReloadConfig? = null,
    val tls: GatewayTlsConfig? = null,
    val http: GatewayHttpConfig? = null,
    val nodes: GatewayNodesConfig? = null,
    val trustedProxies: List<String>? = null,
    val allowRealIpFallback: Boolean? = null,
    val tools: GatewayToolsConfig? = null,
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
data class CronConfig(
    val enabled: Boolean? = null,
)

@Serializable
data class ConfigMetadata(
    val lastTouchedVersion: String? = null,
    val lastTouchedAt: String? = null,
)

@Serializable
data class ShellEnvConfig(
    val enabled: Boolean? = null,
    val timeoutMs: Int? = null,
)

@Serializable
data class EnvConfig(
    val shellEnv: ShellEnvConfig? = null,
    val vars: Map<String, String>? = null,
)

@Serializable
data class WizardConfig(
    val lastRunAt: String? = null,
    val lastRunVersion: String? = null,
    val lastRunCommit: String? = null,
    val lastRunCommand: String? = null,
    val lastRunMode: String? = null,
)

@Serializable
data class UpdateAutoConfig(
    val enabled: Boolean? = null,
    val stableDelayHours: Int? = null,
    val stableJitterHours: Int? = null,
    val betaCheckIntervalHours: Int? = null,
)

@Serializable
data class UpdateConfig(
    val channel: String? = null,
    val checkOnStart: Boolean? = null,
    val auto: UpdateAutoConfig? = null,
)

@Serializable
data class UiAssistantConfig(
    val name: String? = null,
    val avatar: String? = null,
)

@Serializable
data class UiConfig(
    val seamColor: String? = null,
    val assistant: UiAssistantConfig? = null,
)

@Serializable
data class AppRuntimeConfig(
    val keepAliveInBackground: Boolean? = null,
)

@Serializable
data class WebReconnectConfig(
    val initialMs: Long? = null,
    val maxMs: Long? = null,
    val factor: Double? = null,
    val jitter: Double? = null,
    val maxAttempts: Int? = null,
)

@Serializable
data class WebConfig(
    val enabled: Boolean? = null,
    val heartbeatSeconds: Int? = null,
    val reconnect: WebReconnectConfig? = null,
)

@Serializable
data class PluginSlotsConfig(
    val memory: String? = null,
)

@Serializable
data class PluginsLoadConfig(
    val paths: List<String>? = null,
)

@Serializable
data class PluginInstallRecord(
    val source: String,
    val spec: String? = null,
    val sourcePath: String? = null,
    val installPath: String? = null,
    val version: String? = null,
    val resolvedName: String? = null,
    val resolvedVersion: String? = null,
    val resolvedSpec: String? = null,
    val integrity: String? = null,
    val shasum: String? = null,
    val resolvedAt: String? = null,
    val installedAt: String? = null,
)

@Serializable
data class PluginsConfig(
    val enabled: Boolean? = null,
    val allow: List<String>? = null,
    val deny: List<String>? = null,
    val load: PluginsLoadConfig? = null,
    val slots: PluginSlotsConfig? = null,
    val entries: Map<String, PluginEntryConfig>? = null,
    val installs: Map<String, PluginInstallRecord>? = null,
)

@Serializable
data class PluginEntryConfig(
    val enabled: Boolean? = null,
    val config: Map<String, String>? = null,
)

@Serializable
data class OpenClawConfig(
    val meta: ConfigMetadata? = null,
    val auth: AuthConfig? = null,
    val acp: AcpConfig? = null,
    val env: EnvConfig? = null,
    val wizard: WizardConfig? = null,
    val diagnostics: DiagnosticsConfig? = null,
    val logging: LoggingConfig? = null,
    val update: UpdateConfig? = null,
    val browser: BrowserConfig? = null,
    val ui: UiConfig? = null,
    val appRuntime: AppRuntimeConfig? = null,
    val secrets: SecretsConfig? = null,
    val skills: SkillsConfig? = null,
    val plugins: PluginsConfig? = null,
    val models: ModelsConfig? = null,
    val nodeHost: NodeHostConfig? = null,
    val agents: AgentsConfig? = null,
    val tools: ExpandedToolsConfig? = null,
    val bindings: List<AgentBinding>? = null,
    val broadcast: BroadcastConfig? = null,
    val audio: AudioConfig? = null,
    val messages: ExpandedMessagesConfig? = null,
    val commands: CommandsConfig? = null,
    val approvals: ApprovalsConfig? = null,
    val session: SessionConfig? = null,
    val web: WebConfig? = null,
    val channels: ChannelsConfig? = null,
    val cron: CronConfig? = null,
    val hooks: HooksConfig? = null,
    val discovery: DiscoveryConfig? = null,
    val canvasHost: CanvasHostConfig? = null,
    val talk: TalkConfig? = null,
    val gateway: GatewayConfig? = null,
    val memory: ExpandedMemoryConfig? = null,
)

@Serializable
data class ConfigValidationIssue(
    val path: String,
    val message: String,
)
