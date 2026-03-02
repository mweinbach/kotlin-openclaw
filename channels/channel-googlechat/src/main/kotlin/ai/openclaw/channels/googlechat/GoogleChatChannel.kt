package ai.openclaw.channels.googlechat

import ai.openclaw.channels.core.BaseChannelAdapter
import ai.openclaw.core.model.*

class GoogleChatChannel : BaseChannelAdapter() {

    override val channelId: ChannelId = "googlechat"
    override val displayName: String = "Google Chat"

    override val capabilities: ChannelCapabilities = ChannelCapabilities(
        text = true,
        images = true,
        threads = true,
        groups = true,
        typing = true,
    )

    override suspend fun onStart() {
        // TODO: Authenticate with Google service account and register Chat API webhook
    }

    override suspend fun onStop() {
        // TODO: Unregister webhook and clean up resources
    }

    override suspend fun send(message: OutboundMessage): Boolean {
        // TODO: Send message via Google Chat API
        return false
    }
}
