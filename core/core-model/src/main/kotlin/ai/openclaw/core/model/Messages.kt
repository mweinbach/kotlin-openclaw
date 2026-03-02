package ai.openclaw.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Messages Config (ported from src/config/types.messages.ts) ---

@Serializable
data class GroupChatMessagesConfig(
    val mentionPatterns: List<String>? = null,
    val historyLimit: Int? = null,
)

@Serializable
data class DmConfig(
    val historyLimit: Int? = null,
)

@Serializable
enum class BroadcastStrategy {
    @SerialName("parallel") PARALLEL,
    @SerialName("sequential") SEQUENTIAL,
}

@Serializable
data class BroadcastConfig(
    val strategy: BroadcastStrategy? = null,
)

@Serializable
data class AudioTranscriptionConfig(
    val command: List<String>,
    val timeoutSeconds: Int? = null,
)

@Serializable
data class AudioConfig(
    val transcription: AudioTranscriptionConfig? = null,
)

@Serializable
data class StatusReactionsEmojiConfig(
    val thinking: String? = null,
    val tool: String? = null,
    val coding: String? = null,
    val web: String? = null,
    val done: String? = null,
    val error: String? = null,
    val stallSoft: String? = null,
    val stallHard: String? = null,
)

@Serializable
data class StatusReactionsTimingConfig(
    val debounceMs: Int? = null,
    val stallSoftMs: Int? = null,
    val stallHardMs: Int? = null,
    val doneHoldMs: Int? = null,
    val errorHoldMs: Int? = null,
)

@Serializable
data class StatusReactionsConfig(
    val enabled: Boolean? = null,
    val emojis: StatusReactionsEmojiConfig? = null,
    val timing: StatusReactionsTimingConfig? = null,
)

@Serializable
data class ExpandedMessagesConfig(
    val messagePrefix: String? = null,
    val responsePrefix: String? = null,
    val groupChat: GroupChatMessagesConfig? = null,
    val queue: QueueConfig? = null,
    val inbound: InboundDebounceConfig? = null,
    val ackReaction: String? = null,
    val ackReactionScope: String? = null,
    val removeAckAfterReply: Boolean? = null,
    val statusReactions: StatusReactionsConfig? = null,
    val suppressToolErrors: Boolean? = null,
    val tts: TtsConfig? = null,
    val maxLength: Int? = null,
)

@Serializable
enum class NativeCommandsSetting {
    @SerialName("true") TRUE,
    @SerialName("false") FALSE,
    @SerialName("auto") AUTO,
}

@Serializable
data class CommandsConfig(
    val text: Boolean? = null,
    val bash: Boolean? = null,
    val bashForegroundMs: Int? = null,
    val config: Boolean? = null,
    val debug: Boolean? = null,
    val restart: Boolean? = null,
    val useAccessGroups: Boolean? = null,
    val ownerAllowFrom: List<String>? = null,
    val ownerDisplay: String? = null,
    val ownerDisplaySecret: String? = null,
    val allowFrom: Map<String, List<String>>? = null,
)
