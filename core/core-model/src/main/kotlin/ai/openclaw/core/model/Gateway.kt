package ai.openclaw.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Expanded Gateway Config (ported from src/config/types.gateway.ts) ---

@Serializable
enum class GatewayBindMode {
    @SerialName("auto") AUTO,
    @SerialName("lan") LAN,
    @SerialName("loopback") LOOPBACK,
    @SerialName("custom") CUSTOM,
    @SerialName("tailnet") TAILNET,
}

@Serializable
enum class GatewayAuthMode {
    @SerialName("none") NONE,
    @SerialName("token") TOKEN,
    @SerialName("password") PASSWORD,
    @SerialName("trusted-proxy") TRUSTED_PROXY,
}

@Serializable
data class GatewayAuthRateLimitConfig(
    val maxAttempts: Int? = null,
    val windowMs: Long? = null,
    val lockoutMs: Long? = null,
    val exemptLoopback: Boolean? = null,
)

@Serializable
data class GatewayTrustedProxyConfig(
    val userHeader: String,
    val requiredHeaders: List<String>? = null,
    val allowUsers: List<String>? = null,
)

@Serializable
data class GatewayControlUiConfig(
    val enabled: Boolean? = null,
    val basePath: String? = null,
    val root: String? = null,
    val allowedOrigins: List<String>? = null,
    val dangerouslyAllowHostHeaderOriginFallback: Boolean? = null,
    val allowInsecureAuth: Boolean? = null,
    val dangerouslyDisableDeviceAuth: Boolean? = null,
)

@Serializable
enum class GatewayTailscaleMode {
    @SerialName("off") OFF,
    @SerialName("serve") SERVE,
    @SerialName("funnel") FUNNEL,
}

@Serializable
data class GatewayTailscaleConfig(
    val mode: GatewayTailscaleMode? = null,
    val resetOnExit: Boolean? = null,
)

@Serializable
data class GatewayRemoteConfig(
    val url: String? = null,
    val transport: String? = null,
    val token: String? = null,
    val password: String? = null,
    val tlsFingerprint: String? = null,
    val sshTarget: String? = null,
    val sshIdentity: String? = null,
)

@Serializable
enum class GatewayReloadMode {
    @SerialName("off") OFF,
    @SerialName("restart") RESTART,
    @SerialName("hot") HOT,
    @SerialName("hybrid") HYBRID,
}

@Serializable
data class GatewayReloadConfig(
    val mode: GatewayReloadMode? = null,
    val debounceMs: Long? = null,
)

@Serializable
data class GatewayHttpEndpointsConfig(
    val chatCompletionsEnabled: Boolean? = null,
    val responsesEnabled: Boolean? = null,
)

@Serializable
data class GatewayHttpConfig(
    val endpoints: GatewayHttpEndpointsConfig? = null,
)

@Serializable
data class GatewayNodesConfig(
    val allowCommands: List<String>? = null,
    val denyCommands: List<String>? = null,
)

@Serializable
data class GatewayToolsConfig(
    val deny: List<String>? = null,
    val allow: List<String>? = null,
)

@Serializable
data class DiscoveryConfig(
    val wideArea: WideAreaDiscoveryConfig? = null,
    val mdns: MdnsDiscoveryConfig? = null,
)

@Serializable
data class WideAreaDiscoveryConfig(
    val enabled: Boolean? = null,
    val domain: String? = null,
)

@Serializable
enum class MdnsDiscoveryMode {
    @SerialName("off") OFF,
    @SerialName("minimal") MINIMAL,
    @SerialName("full") FULL,
}

@Serializable
data class MdnsDiscoveryConfig(
    val mode: MdnsDiscoveryMode? = null,
)

@Serializable
data class CanvasHostConfig(
    val enabled: Boolean? = null,
    val root: String? = null,
    val port: Int? = null,
    val liveReload: Boolean? = null,
)

@Serializable
data class TalkProviderConfig(
    val voiceId: String? = null,
    val voiceAliases: Map<String, String>? = null,
    val modelId: String? = null,
    val outputFormat: String? = null,
    val apiKey: String? = null,
)

@Serializable
data class TalkConfig(
    val provider: String? = null,
    val providers: Map<String, TalkProviderConfig>? = null,
    val interruptOnSpeech: Boolean? = null,
    val voiceId: String? = null,
    val voiceAliases: Map<String, String>? = null,
    val modelId: String? = null,
    val outputFormat: String? = null,
    val apiKey: String? = null,
)

@Serializable
data class NodeHostBrowserProxyConfig(
    val enabled: Boolean? = null,
    val allowProfiles: List<String>? = null,
)

@Serializable
data class NodeHostConfig(
    val browserProxy: NodeHostBrowserProxyConfig? = null,
)
