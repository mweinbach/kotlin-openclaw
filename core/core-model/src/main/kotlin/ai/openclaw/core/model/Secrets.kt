package ai.openclaw.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Secrets Config (ported from src/config/types.secrets.ts) ---

@Serializable
enum class SecretRefSource {
    @SerialName("env") ENV,
    @SerialName("file") FILE,
    @SerialName("exec") EXEC,
}

@Serializable
data class SecretRef(
    val source: SecretRefSource,
    val provider: String,
    val id: String,
)

@Serializable
data class EnvSecretProviderConfig(
    val source: String = "env",
    val allowlist: List<String>? = null,
)

@Serializable
data class FileSecretProviderConfig(
    val source: String = "file",
    val path: String,
    val mode: String? = null,
    val timeoutMs: Int? = null,
    val maxBytes: Long? = null,
)

@Serializable
data class ExecSecretProviderConfig(
    val source: String = "exec",
    val command: String,
    val args: List<String>? = null,
    val timeoutMs: Int? = null,
    val noOutputTimeoutMs: Int? = null,
    val maxOutputBytes: Long? = null,
    val jsonOnly: Boolean? = null,
    val env: Map<String, String>? = null,
    val passEnv: List<String>? = null,
    val trustedDirs: List<String>? = null,
    val allowInsecurePath: Boolean? = null,
    val allowSymlinkCommand: Boolean? = null,
)

@Serializable
data class SecretsDefaultsConfig(
    val env: String? = null,
    val file: String? = null,
    val exec: String? = null,
)

@Serializable
data class SecretsResolutionConfig(
    val maxProviderConcurrency: Int? = null,
    val maxRefsPerProvider: Int? = null,
    val maxBatchBytes: Long? = null,
)

@Serializable
data class SecretsConfig(
    val defaults: SecretsDefaultsConfig? = null,
    val resolution: SecretsResolutionConfig? = null,
)
