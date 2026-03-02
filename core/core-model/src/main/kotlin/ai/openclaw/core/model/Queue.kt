package ai.openclaw.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Queue Config (ported from src/config/types.queue.ts) ---

@Serializable
data class QueueModeByProvider(
    val whatsapp: QueueMode? = null,
    val telegram: QueueMode? = null,
    val discord: QueueMode? = null,
    val irc: QueueMode? = null,
    val googlechat: QueueMode? = null,
    val slack: QueueMode? = null,
    val signal: QueueMode? = null,
    val imessage: QueueMode? = null,
    val msteams: QueueMode? = null,
    val webchat: QueueMode? = null,
)

@Serializable
data class QueueConfig(
    val mode: QueueMode? = null,
    val byChannel: QueueModeByProvider? = null,
    val debounceMs: Long? = null,
    val debounceMsByChannel: Map<String, Long>? = null,
    val cap: Int? = null,
    val drop: QueueDrop? = null,
)

@Serializable
data class InboundDebounceConfig(
    val debounceMs: Long? = null,
    val byChannel: Map<String, Long>? = null,
)
