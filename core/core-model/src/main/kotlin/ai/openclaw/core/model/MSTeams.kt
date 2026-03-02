package ai.openclaw.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- MS Teams Config (ported from src/config/types.msteams.ts) ---

@Serializable
data class MSTeamsWebhookConfig(
    val port: Int? = null,
    val path: String? = null,
)

@Serializable
enum class MSTeamsReplyStyle {
    @SerialName("thread") THREAD,
    @SerialName("top-level") TOP_LEVEL,
}

@Serializable
data class MSTeamsChannelConfig(
    val requireMention: Boolean? = null,
    val tools: GroupToolPolicyConfig? = null,
    val replyStyle: MSTeamsReplyStyle? = null,
)

@Serializable
data class MSTeamsTeamConfig(
    val requireMention: Boolean? = null,
    val tools: GroupToolPolicyConfig? = null,
    val replyStyle: MSTeamsReplyStyle? = null,
    val channels: Map<String, MSTeamsChannelConfig>? = null,
)

@Serializable
data class MSTeamsConfig(
    val enabled: Boolean? = null,
    val capabilities: List<String>? = null,
    val dangerouslyAllowNameMatching: Boolean? = null,
    val markdown: MarkdownConfig? = null,
    val configWrites: Boolean? = null,
    val appId: String? = null,
    val appPassword: String? = null,
    val tenantId: String? = null,
    val webhook: MSTeamsWebhookConfig? = null,
    val dmPolicy: DmPolicy? = null,
    val allowFrom: List<String>? = null,
    val defaultTo: String? = null,
    val groupAllowFrom: List<String>? = null,
    val groupPolicy: GroupPolicy? = null,
    val textChunkLimit: Int? = null,
    val chunkMode: String? = null,
    val blockStreamingCoalesce: BlockStreamingCoalesceConfig? = null,
    val mediaAllowHosts: List<String>? = null,
    val mediaAuthAllowHosts: List<String>? = null,
    val requireMention: Boolean? = null,
    val historyLimit: Int? = null,
    val dmHistoryLimit: Int? = null,
    val replyStyle: MSTeamsReplyStyle? = null,
    val teams: Map<String, MSTeamsTeamConfig>? = null,
    val mediaMaxMb: Int? = null,
    val sharePointSiteId: String? = null,
    val heartbeat: ChannelHeartbeatVisibilityConfig? = null,
    val responsePrefix: String? = null,
)
