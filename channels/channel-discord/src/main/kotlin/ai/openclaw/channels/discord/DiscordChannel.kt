package ai.openclaw.channels.discord

import ai.openclaw.channels.core.BaseChannelAdapter
import ai.openclaw.core.model.*

class DiscordChannel : BaseChannelAdapter() {

    override val channelId: ChannelId = "discord"
    override val displayName: String = "Discord"

    override val capabilities: ChannelCapabilities = ChannelCapabilities(
        text = true,
        images = true,
        reactions = true,
        threads = true,
        groups = true,
        typing = true,
        richText = true,
    )

    override suspend fun onStart() {
        // TODO: Connect to Discord gateway and register event listeners
    }

    override suspend fun onStop() {
        // TODO: Disconnect from Discord gateway and clean up resources
    }

    override suspend fun send(message: OutboundMessage): Boolean {
        // TODO: Send message via Discord REST API
        return false
    }
}
