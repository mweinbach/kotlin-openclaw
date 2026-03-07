package ai.openclaw.runtime.gateway

import ai.openclaw.core.model.InboundMessage
import ai.openclaw.core.model.OutboundMessage
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChannelManagerTest {

    @Test
    fun `non retryable startup failures remain in error state`() = runTest {
        val manager = ChannelManager(this)
        manager.registerChannel(
            adapter = object : ChannelManager.ChannelAdapter {
                override val channelId: String = "googlechat"
                override val displayName: String = "Google Chat"

                override suspend fun start(handler: suspend (InboundMessage) -> Unit) {
                    throw ChannelManager.NonRetryableChannelException("Not wired yet")
                }

                override suspend fun stop() = Unit

                override suspend fun send(message: OutboundMessage): Boolean = false
            },
            accountId = "default",
        )

        manager.startAll()

        val snapshot = manager.getSnapshot().getValue("googlechat:default")
        assertEquals("error", snapshot.status)
        assertEquals("Not wired yet", snapshot.error)
        assertEquals(0, snapshot.restartAttempts)
    }

    @Test
    fun `retryable startup failures enter restarting state`() = runTest {
        val manager = ChannelManager(this)
        manager.registerChannel(
            adapter = object : ChannelManager.ChannelAdapter {
                override val channelId: String = "discord"
                override val displayName: String = "Discord"

                override suspend fun start(handler: suspend (InboundMessage) -> Unit) {
                    throw IllegalStateException("temporary startup failure")
                }

                override suspend fun stop() = Unit

                override suspend fun send(message: OutboundMessage): Boolean = false
            },
            accountId = "default",
        )

        manager.startAll()

        val snapshot = manager.getSnapshot().getValue("discord:default")
        assertEquals("restarting", snapshot.status)
        assertEquals("temporary startup failure", snapshot.error)
        assertTrue(snapshot.restartAttempts > 0)
    }
}
