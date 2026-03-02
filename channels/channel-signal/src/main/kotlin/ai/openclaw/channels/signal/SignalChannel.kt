package ai.openclaw.channels.signal

import ai.openclaw.channels.core.BaseChannelAdapter
import ai.openclaw.core.model.*

class SignalChannel : BaseChannelAdapter() {

    override val channelId: ChannelId = "signal"
    override val displayName: String = "Signal"

    override val capabilities: ChannelCapabilities = ChannelCapabilities(
        text = true,
        images = true,
        reactions = true,
        groups = true,
    )

    override suspend fun onStart() {
        // TODO: Connect to Signal CLI REST API and start listening for messages
    }

    override suspend fun onStop() {
        // TODO: Disconnect from Signal CLI REST API and clean up resources
    }

    override suspend fun send(message: OutboundMessage): Boolean {
        // TODO: Send message via Signal CLI REST API
        return false
    }
}
