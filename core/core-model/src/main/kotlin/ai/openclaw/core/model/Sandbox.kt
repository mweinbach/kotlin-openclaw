package ai.openclaw.core.model

import kotlinx.serialization.Serializable

// --- Sandbox Config (ported from src/config/types.sandbox.ts) ---

@Serializable
data class SandboxDockerSettings(
    val image: String? = null,
    val containerPrefix: String? = null,
    val workdir: String? = null,
    val readOnlyRoot: Boolean? = null,
    val tmpfs: List<String>? = null,
    val network: String? = null,
    val user: String? = null,
    val capDrop: List<String>? = null,
    val env: Map<String, String>? = null,
    val setupCommand: String? = null,
    val pidsLimit: Int? = null,
    val memory: String? = null,
    val memorySwap: String? = null,
    val cpus: Double? = null,
    val seccompProfile: String? = null,
    val apparmorProfile: String? = null,
    val dns: List<String>? = null,
    val extraHosts: List<String>? = null,
    val binds: List<String>? = null,
    val dangerouslyAllowReservedContainerTargets: Boolean? = null,
    val dangerouslyAllowExternalBindSources: Boolean? = null,
    val dangerouslyAllowContainerNamespaceJoin: Boolean? = null,
)

@Serializable
data class SandboxBrowserSettings(
    val enabled: Boolean? = null,
    val image: String? = null,
    val containerPrefix: String? = null,
    val network: String? = null,
    val cdpPort: Int? = null,
    val cdpSourceRange: String? = null,
    val vncPort: Int? = null,
    val noVncPort: Int? = null,
    val headless: Boolean? = null,
    val enableNoVnc: Boolean? = null,
    val allowHostControl: Boolean? = null,
    val autoStart: Boolean? = null,
    val autoStartTimeoutMs: Int? = null,
    val binds: List<String>? = null,
)

@Serializable
data class SandboxPruneSettings(
    val idleHours: Int? = null,
    val maxAgeDays: Int? = null,
)
