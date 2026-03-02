package ai.openclaw.channels.matrix

import ai.openclaw.channels.core.BaseChannelAdapter
import ai.openclaw.core.model.*

class MatrixChannel : BaseChannelAdapter() {

    override val channelId: ChannelId = "matrix"
    override val displayName: String = "Matrix"

    override val capabilities: ChannelCapabilities = ChannelCapabilities(
        text = true,
        images = true,
        reactions = true,
        threads = true,
        groups = true,
        typing = true,
    )

    override suspend fun onStart() {
        // TODO: Connect to Matrix homeserver and start syncing
    }

    override suspend fun onStop() {
        // TODO: Disconnect from Matrix homeserver and clean up resources
    }

    override suspend fun send(message: OutboundMessage): Boolean {
        // TODO: Send message via Matrix client-server API
        return false
    }
}
