package ai.openclaw.channels.core

import ai.openclaw.core.model.*
import kotlinx.coroutines.flow.Flow

/**
 * Callback for handling inbound messages from channels.
 */
typealias InboundMessageHandler = suspend (InboundMessage) -> Unit

/**
 * Interface that all channel implementations must implement.
 */
interface ChannelPlugin {
    /** Unique channel identifier (e.g., "telegram", "discord"). */
    val channelId: ChannelId

    /** Human-readable channel name. */
    val displayName: String

    /** Channel capabilities. */
    val capabilities: ChannelCapabilities

    /** Start the channel (connect, authenticate, begin listening). */
    suspend fun start(handler: InboundMessageHandler)

    /** Stop the channel. */
    suspend fun stop()

    /** Send a message to a channel target. */
    suspend fun send(message: OutboundMessage): Boolean

    /** Check if the channel is currently connected and healthy. */
    fun isConnected(): Boolean

    /** Get channel metadata. */
    fun getMeta(): ChannelMeta = ChannelMeta(
        id = channelId,
        name = displayName,
        capabilities = capabilities,
    )
}

/**
 * Base adapter providing common functionality for channel implementations.
 */
abstract class BaseChannelAdapter : ChannelPlugin {
    protected var messageHandler: InboundMessageHandler? = null
    protected var connected = false

    override suspend fun start(handler: InboundMessageHandler) {
        messageHandler = handler
        connected = true
        onStart()
    }

    override suspend fun stop() {
        connected = false
        messageHandler = null
        onStop()
    }

    override fun isConnected(): Boolean = connected

    protected abstract suspend fun onStart()
    protected abstract suspend fun onStop()

    protected suspend fun dispatchInbound(message: InboundMessage) {
        messageHandler?.invoke(message)
    }
}
