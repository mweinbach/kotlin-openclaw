package ai.openclaw.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Channel Types (ported from src/config/types.channels.ts) ---

@Serializable
data class ChannelHeartbeatVisibilityConfig(
    val showOk: Boolean? = null,
    val showAlerts: Boolean? = null,
    val useIndicator: Boolean? = null,
)

@Serializable
data class ChannelDefaultsConfig(
    val groupPolicy: GroupPolicy? = null,
    val heartbeat: ChannelHeartbeatVisibilityConfig? = null,
)

@Serializable
data class ChannelsConfig(
    val defaults: ChannelDefaultsConfig? = null,
    val modelByChannel: Map<String, Map<String, String>>? = null,
    val telegram: TelegramConfig? = null,
    val discord: DiscordConfig? = null,
    val slack: SlackConfig? = null,
    val signal: SignalConfig? = null,
    val irc: IrcConfig? = null,
    val googlechat: GoogleChatConfig? = null,
)

// --- Telegram Config ---

@Serializable
data class TelegramAccountConfig(
    val botToken: SecretInput? = null,
    val allowFrom: List<String>? = null,
    val dmPolicy: DmPolicy? = null,
    val groupPolicy: GroupPolicy? = null,
    val polling: Boolean? = null,
    val webhook: TelegramWebhookConfig? = null,
)

@Serializable
data class TelegramWebhookConfig(
    val url: String? = null,
    val port: Int? = null,
    val secretToken: String? = null,
)

@Serializable
data class TelegramConfig(
    val enabled: Boolean? = null,
    val accounts: Map<String, TelegramAccountConfig>? = null,
)

// --- Discord Config ---

@Serializable
data class DiscordAccountConfig(
    val botToken: SecretInput? = null,
    val allowFrom: List<String>? = null,
    val dmPolicy: DmPolicy? = null,
    val groupPolicy: GroupPolicy? = null,
    val intents: List<String>? = null,
)

@Serializable
data class DiscordConfig(
    val enabled: Boolean? = null,
    val accounts: Map<String, DiscordAccountConfig>? = null,
)

// --- Slack Config ---

@Serializable
data class SlackAccountConfig(
    val botToken: SecretInput? = null,
    val appToken: SecretInput? = null,
    val signingSecret: SecretInput? = null,
    val allowFrom: List<String>? = null,
    val dmPolicy: DmPolicy? = null,
    val groupPolicy: GroupPolicy? = null,
)

@Serializable
data class SlackConfig(
    val enabled: Boolean? = null,
    val accounts: Map<String, SlackAccountConfig>? = null,
)

// --- Signal Config ---

@Serializable
data class SignalAccountConfig(
    val apiUrl: String? = null,
    val number: String? = null,
    val allowFrom: List<String>? = null,
    val dmPolicy: DmPolicy? = null,
    val groupPolicy: GroupPolicy? = null,
)

@Serializable
data class SignalConfig(
    val enabled: Boolean? = null,
    val accounts: Map<String, SignalAccountConfig>? = null,
)

// --- IRC Config ---

@Serializable
data class IrcAccountConfig(
    val server: String? = null,
    val port: Int? = null,
    val nick: String? = null,
    val password: SecretInput? = null,
    val channels: List<String>? = null,
    val useTls: Boolean? = null,
)

@Serializable
data class IrcConfig(
    val enabled: Boolean? = null,
    val accounts: Map<String, IrcAccountConfig>? = null,
)

// --- Google Chat Config ---

@Serializable
data class GoogleChatAccountConfig(
    val serviceAccountKey: SecretInput? = null,
    val allowFrom: List<String>? = null,
    val dmPolicy: DmPolicy? = null,
    val groupPolicy: GroupPolicy? = null,
)

@Serializable
data class GoogleChatConfig(
    val enabled: Boolean? = null,
    val accounts: Map<String, GoogleChatAccountConfig>? = null,
)

// --- Matrix Config ---

@Serializable
data class MatrixAccountConfig(
    val homeserverUrl: String? = null,
    val accessToken: SecretInput? = null,
    val userId: String? = null,
    val allowFrom: List<String>? = null,
    val dmPolicy: DmPolicy? = null,
    val groupPolicy: GroupPolicy? = null,
)

@Serializable
data class MatrixConfig(
    val enabled: Boolean? = null,
    val accounts: Map<String, MatrixAccountConfig>? = null,
)

// --- Channel Plugin Types ---

typealias ChannelId = String

@Serializable
data class ChannelMeta(
    val id: ChannelId,
    val name: String,
    val capabilities: ChannelCapabilities,
)

@Serializable
data class ChannelCapabilities(
    val text: Boolean = true,
    val images: Boolean = false,
    val audio: Boolean = false,
    val video: Boolean = false,
    val files: Boolean = false,
    val reactions: Boolean = false,
    val threads: Boolean = false,
    val groups: Boolean = false,
    val typing: Boolean = false,
    val editing: Boolean = false,
    val deletion: Boolean = false,
    val polls: Boolean = false,
    val richText: Boolean = false,
)

// --- Inbound/Outbound Message Types ---

@Serializable
data class InboundMessage(
    val channel: ChannelId,
    val accountId: String,
    val from: String,
    val to: String? = null,
    val text: String,
    val chatType: ChatType,
    val threadId: String? = null,
    val guildId: String? = null,
    val roles: List<String>? = null,
    val replyToMessageId: String? = null,
    val attachments: List<MessageAttachment>? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val messageId: String? = null,
)

@Serializable
data class OutboundMessage(
    val channel: ChannelId,
    val accountId: String,
    val to: String,
    val text: String,
    val threadId: String? = null,
    val replyToMessageId: String? = null,
    val attachments: List<MessageAttachment>? = null,
)

@Serializable
data class MessageAttachment(
    val type: AttachmentType,
    val url: String? = null,
    val data: String? = null,
    val mimeType: String? = null,
    val filename: String? = null,
)

@Serializable
enum class AttachmentType {
    @SerialName("image") IMAGE,
    @SerialName("audio") AUDIO,
    @SerialName("video") VIDEO,
    @SerialName("file") FILE,
}
