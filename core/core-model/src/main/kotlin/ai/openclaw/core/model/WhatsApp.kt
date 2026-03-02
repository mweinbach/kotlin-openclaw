package ai.openclaw.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- WhatsApp Config (ported from src/config/types.whatsapp.ts) ---

@Serializable
data class WhatsAppActionConfig(
    val reactions: Boolean? = null,
    val sendMessage: Boolean? = null,
    val polls: Boolean? = null,
)

@Serializable
data class WhatsAppGroupConfig(
    val requireMention: Boolean? = null,
    val tools: GroupToolPolicyConfig? = null,
)

@Serializable
data class WhatsAppAckReactionConfig(
    val emoji: String? = null,
    val direct: Boolean? = null,
    val group: String? = null,
)

@Serializable
data class WhatsAppAccountConfig(
    val name: String? = null,
    val enabled: Boolean? = null,
    val authDir: String? = null,
    val capabilities: List<String>? = null,
    val markdown: MarkdownConfig? = null,
    val configWrites: Boolean? = null,
    val sendReadReceipts: Boolean? = null,
    val messagePrefix: String? = null,
    val responsePrefix: String? = null,
    val dmPolicy: DmPolicy? = null,
    val selfChatMode: Boolean? = null,
    val allowFrom: List<String>? = null,
    val defaultTo: String? = null,
    val groupAllowFrom: List<String>? = null,
    val groupPolicy: GroupPolicy? = null,
    val historyLimit: Int? = null,
    val dmHistoryLimit: Int? = null,
    val textChunkLimit: Int? = null,
    val chunkMode: String? = null,
    val mediaMaxMb: Int? = null,
    val blockStreaming: Boolean? = null,
    val blockStreamingCoalesce: BlockStreamingCoalesceConfig? = null,
    val groups: Map<String, WhatsAppGroupConfig>? = null,
    val ackReaction: WhatsAppAckReactionConfig? = null,
    val debounceMs: Long? = null,
    val heartbeat: ChannelHeartbeatVisibilityConfig? = null,
)

@Serializable
data class WhatsAppConfig(
    val enabled: Boolean? = null,
    val capabilities: List<String>? = null,
    val markdown: MarkdownConfig? = null,
    val configWrites: Boolean? = null,
    val sendReadReceipts: Boolean? = null,
    val messagePrefix: String? = null,
    val responsePrefix: String? = null,
    val dmPolicy: DmPolicy? = null,
    val selfChatMode: Boolean? = null,
    val allowFrom: List<String>? = null,
    val defaultTo: String? = null,
    val groupAllowFrom: List<String>? = null,
    val groupPolicy: GroupPolicy? = null,
    val historyLimit: Int? = null,
    val dmHistoryLimit: Int? = null,
    val textChunkLimit: Int? = null,
    val chunkMode: String? = null,
    val mediaMaxMb: Int? = null,
    val blockStreaming: Boolean? = null,
    val blockStreamingCoalesce: BlockStreamingCoalesceConfig? = null,
    val groups: Map<String, WhatsAppGroupConfig>? = null,
    val ackReaction: WhatsAppAckReactionConfig? = null,
    val debounceMs: Long? = null,
    val heartbeat: ChannelHeartbeatVisibilityConfig? = null,
    val accounts: Map<String, WhatsAppAccountConfig>? = null,
    val actions: WhatsAppActionConfig? = null,
)
