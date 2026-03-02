package ai.openclaw.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Browser Config (ported from src/config/types.browser.ts) ---

@Serializable
data class BrowserProfileConfig(
    val cdpPort: Int? = null,
    val cdpUrl: String? = null,
    val driver: String? = null,
    val color: String,
)

@Serializable
data class BrowserSnapshotDefaults(
    val mode: String? = null,
)

@Serializable
data class BrowserSsrfPolicyConfig(
    val allowPrivateNetwork: Boolean? = null,
    val dangerouslyAllowPrivateNetwork: Boolean? = null,
    val allowedHostnames: List<String>? = null,
    val hostnameAllowlist: List<String>? = null,
)

@Serializable
data class BrowserConfig(
    val enabled: Boolean? = null,
    val evaluateEnabled: Boolean? = null,
    val cdpUrl: String? = null,
    val remoteCdpTimeoutMs: Int? = null,
    val remoteCdpHandshakeTimeoutMs: Int? = null,
    val color: String? = null,
    val executablePath: String? = null,
    val headless: Boolean? = null,
    val noSandbox: Boolean? = null,
    val attachOnly: Boolean? = null,
    val defaultProfile: String? = null,
    val profiles: Map<String, BrowserProfileConfig>? = null,
    val snapshotDefaults: BrowserSnapshotDefaults? = null,
    val ssrfPolicy: BrowserSsrfPolicyConfig? = null,
    val extraArgs: List<String>? = null,
)
