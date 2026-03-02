package ai.openclaw.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Memory Config (ported from src/config/types.memory.ts) ---

@Serializable
enum class MemoryBackend {
    @SerialName("builtin") BUILTIN,
    @SerialName("qmd") QMD,
}

@Serializable
enum class MemoryCitationsMode {
    @SerialName("auto") AUTO,
    @SerialName("on") ON,
    @SerialName("off") OFF,
}

@Serializable
enum class MemoryQmdSearchMode {
    @SerialName("query") QUERY,
    @SerialName("search") SEARCH,
    @SerialName("vsearch") VSEARCH,
}

@Serializable
data class MemoryQmdMcporterConfig(
    val enabled: Boolean? = null,
    val serverName: String? = null,
    val startDaemon: Boolean? = null,
)

@Serializable
data class MemoryQmdIndexPath(
    val path: String,
    val name: String? = null,
    val pattern: String? = null,
)

@Serializable
data class MemoryQmdSessionConfig(
    val enabled: Boolean? = null,
    val exportDir: String? = null,
    val retentionDays: Int? = null,
)

@Serializable
data class MemoryQmdUpdateConfig(
    val interval: String? = null,
    val debounceMs: Long? = null,
    val onBoot: Boolean? = null,
    val waitForBootSync: Boolean? = null,
    val embedInterval: String? = null,
    val commandTimeoutMs: Long? = null,
    val updateTimeoutMs: Long? = null,
    val embedTimeoutMs: Long? = null,
)

@Serializable
data class MemoryQmdLimitsConfig(
    val maxResults: Int? = null,
    val maxSnippetChars: Int? = null,
    val maxInjectedChars: Int? = null,
    val timeoutMs: Long? = null,
)

@Serializable
data class MemoryQmdConfig(
    val command: String? = null,
    val mcporter: MemoryQmdMcporterConfig? = null,
    val searchMode: MemoryQmdSearchMode? = null,
    val includeDefaultMemory: Boolean? = null,
    val paths: List<MemoryQmdIndexPath>? = null,
    val sessions: MemoryQmdSessionConfig? = null,
    val update: MemoryQmdUpdateConfig? = null,
    val limits: MemoryQmdLimitsConfig? = null,
    val scope: SessionSendPolicyConfig? = null,
)

@Serializable
data class ExpandedMemoryConfig(
    val backend: MemoryBackend? = null,
    val citations: MemoryCitationsMode? = null,
    val qmd: MemoryQmdConfig? = null,
)
