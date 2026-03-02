package ai.openclaw.channels.sms

import ai.openclaw.channels.core.BaseChannelAdapter
import ai.openclaw.core.model.*

class SmsChannel : BaseChannelAdapter() {

    override val channelId: ChannelId = "sms"
    override val displayName: String = "SMS"

    override val capabilities: ChannelCapabilities = ChannelCapabilities(
        text = true,
    )

    override suspend fun onStart() {
        // TODO: Initialize SMS gateway connection and start listening for inbound messages
    }

    override suspend fun onStop() {
        // TODO: Disconnect from SMS gateway and clean up resources
    }

    override suspend fun send(message: OutboundMessage): Boolean {
        // TODO: Send SMS via gateway provider API
        return false
    }
}
