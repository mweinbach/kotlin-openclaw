package ai.openclaw.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Expanded Tools Config (ported from src/config/types.tools.ts) ---

@Serializable
enum class ToolProfileId {
    @SerialName("minimal") MINIMAL,
    @SerialName("coding") CODING,
    @SerialName("messaging") MESSAGING,
    @SerialName("full") FULL,
}

@Serializable
data class ToolPolicyConfig(
    val allow: List<String>? = null,
    val alsoAllow: List<String>? = null,
    val deny: List<String>? = null,
    val profile: ToolProfileId? = null,
)

@Serializable
data class GroupToolPolicyConfig(
    val allow: List<String>? = null,
    val alsoAllow: List<String>? = null,
    val deny: List<String>? = null,
)

@Serializable
data class ToolLoopDetectionDetectorConfig(
    val genericRepeat: Boolean? = null,
    val knownPollNoProgress: Boolean? = null,
    val pingPong: Boolean? = null,
)

@Serializable
data class ToolLoopDetectionConfig(
    val enabled: Boolean? = null,
    val historySize: Int? = null,
    val warningThreshold: Int? = null,
    val criticalThreshold: Int? = null,
    val globalCircuitBreakerThreshold: Int? = null,
    val detectors: ToolLoopDetectionDetectorConfig? = null,
)

@Serializable
data class ExecApplyPatchConfig(
    val enabled: Boolean? = null,
    val workspaceOnly: Boolean? = null,
    val allowModels: List<String>? = null,
)

@Serializable
data class ExecToolConfig(
    val host: String? = null,
    val security: String? = null,
    val ask: String? = null,
    val node: String? = null,
    val pathPrepend: List<String>? = null,
    val safeBins: List<String>? = null,
    val safeBinTrustedDirs: List<String>? = null,
    val backgroundMs: Int? = null,
    val timeoutSec: Int? = null,
    val approvalRunningNoticeMs: Int? = null,
    val cleanupMs: Int? = null,
    val notifyOnExit: Boolean? = null,
    val notifyOnExitEmptySuccess: Boolean? = null,
    val applyPatch: ExecApplyPatchConfig? = null,
)

@Serializable
data class FsToolsConfig(
    val workspaceOnly: Boolean? = null,
)

@Serializable
data class WebSearchProviderConfig(
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val model: String? = null,
    val inlineCitations: Boolean? = null,
)

@Serializable
data class WebSearchConfig(
    val enabled: Boolean? = null,
    val provider: String? = null,
    val apiKey: String? = null,
    val maxResults: Int? = null,
    val timeoutSeconds: Int? = null,
    val cacheTtlMinutes: Int? = null,
    val perplexity: WebSearchProviderConfig? = null,
    val grok: WebSearchProviderConfig? = null,
    val gemini: WebSearchProviderConfig? = null,
    val kimi: WebSearchProviderConfig? = null,
)

@Serializable
data class FirecrawlConfig(
    val enabled: Boolean? = null,
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val onlyMainContent: Boolean? = null,
    val maxAgeMs: Long? = null,
    val timeoutSeconds: Int? = null,
)

@Serializable
data class WebFetchConfig(
    val enabled: Boolean? = null,
    val maxChars: Int? = null,
    val maxCharsCap: Int? = null,
    val timeoutSeconds: Int? = null,
    val cacheTtlMinutes: Int? = null,
    val maxRedirects: Int? = null,
    val userAgent: String? = null,
    val readability: Boolean? = null,
    val firecrawl: FirecrawlConfig? = null,
)

@Serializable
data class WebToolsConfig(
    val search: WebSearchConfig? = null,
    val fetch: WebFetchConfig? = null,
)

@Serializable
data class MessageToolCrossContextConfig(
    val allowWithinProvider: Boolean? = null,
    val allowAcrossProviders: Boolean? = null,
)

@Serializable
data class MessageToolConfig(
    val allowCrossContextSend: Boolean? = null,
    val crossContext: MessageToolCrossContextConfig? = null,
)

@Serializable
data class AgentToAgentToolsConfig(
    val enabled: Boolean? = null,
    val allow: List<String>? = null,
)

@Serializable
enum class SessionsToolsVisibility {
    @SerialName("self") SELF,
    @SerialName("tree") TREE,
    @SerialName("agent") AGENT,
    @SerialName("all") ALL,
}

@Serializable
data class SessionsToolConfig(
    val visibility: SessionsToolsVisibility? = null,
)

@Serializable
data class SandboxToolsPolicy(
    val allow: List<String>? = null,
    val deny: List<String>? = null,
)

@Serializable
data class SandboxToolsConfig(
    val tools: SandboxToolsPolicy? = null,
)

@Serializable
data class SubagentToolsPolicy(
    val allow: List<String>? = null,
    val alsoAllow: List<String>? = null,
    val deny: List<String>? = null,
)

@Serializable
data class SubagentToolsConfig(
    val model: String? = null,
    val tools: SubagentToolsPolicy? = null,
)

@Serializable
data class ElevatedToolsConfig(
    val enabled: Boolean? = null,
)

@Serializable
data class ExpandedToolsConfig(
    val profile: ToolProfileId? = null,
    val allow: List<String>? = null,
    val alsoAllow: List<String>? = null,
    val deny: List<String>? = null,
    val byProvider: Map<String, ToolPolicyConfig>? = null,
    val web: WebToolsConfig? = null,
    val message: MessageToolConfig? = null,
    val agentToAgent: AgentToAgentToolsConfig? = null,
    val sessions: SessionsToolConfig? = null,
    val elevated: ElevatedToolsConfig? = null,
    val exec: ExecToolConfig? = null,
    val fs: FsToolsConfig? = null,
    val loopDetection: ToolLoopDetectionConfig? = null,
    val subagents: SubagentToolsConfig? = null,
    val sandbox: SandboxToolsConfig? = null,
)
