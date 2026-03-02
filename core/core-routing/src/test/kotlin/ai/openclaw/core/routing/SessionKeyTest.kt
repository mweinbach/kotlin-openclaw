package ai.openclaw.core.routing

import ai.openclaw.core.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionKeyTest {

    @Test
    fun `normalizeAgentId with valid ID`() {
        assertEquals("myagent", normalizeAgentId("MyAgent"))
        assertEquals("agent-1", normalizeAgentId("agent-1"))
    }

    @Test
    fun `normalizeAgentId with invalid chars`() {
        assertEquals("my-agent", normalizeAgentId("my agent!"))
        assertEquals(DEFAULT_AGENT_ID, normalizeAgentId(""))
        assertEquals(DEFAULT_AGENT_ID, normalizeAgentId(null))
    }

    @Test
    fun `normalizeMainKey defaults to main`() {
        assertEquals(DEFAULT_MAIN_KEY, normalizeMainKey(null))
        assertEquals(DEFAULT_MAIN_KEY, normalizeMainKey(""))
        assertEquals("custom", normalizeMainKey("Custom"))
    }

    @Test
    fun `buildAgentMainSessionKey`() {
        assertEquals("agent:main:main", buildAgentMainSessionKey("main"))
        assertEquals("agent:bot1:main", buildAgentMainSessionKey("bot1"))
        assertEquals("agent:bot1:custom", buildAgentMainSessionKey("bot1", "custom"))
    }

    @Test
    fun `buildAgentPeerSessionKey with DM scope main`() {
        val key = buildAgentPeerSessionKey(
            agentId = "main",
            channel = "discord",
            peerKind = ChatType.DIRECT,
            peerId = "user123",
            dmScope = DmScope.MAIN,
        )
        assertEquals("agent:main:main", key)
    }

    @Test
    fun `buildAgentPeerSessionKey with DM scope per-peer`() {
        val key = buildAgentPeerSessionKey(
            agentId = "main",
            channel = "discord",
            peerKind = ChatType.DIRECT,
            peerId = "user123",
            dmScope = DmScope.PER_PEER,
        )
        assertEquals("agent:main:direct:user123", key)
    }

    @Test
    fun `buildAgentPeerSessionKey with DM scope per-channel-peer`() {
        val key = buildAgentPeerSessionKey(
            agentId = "main",
            channel = "discord",
            peerKind = ChatType.DIRECT,
            peerId = "user123",
            dmScope = DmScope.PER_CHANNEL_PEER,
        )
        assertEquals("agent:main:discord:direct:user123", key)
    }

    @Test
    fun `buildAgentPeerSessionKey with DM scope per-account-channel-peer`() {
        val key = buildAgentPeerSessionKey(
            agentId = "main",
            channel = "discord",
            accountId = "acc1",
            peerKind = ChatType.DIRECT,
            peerId = "user123",
            dmScope = DmScope.PER_ACCOUNT_CHANNEL_PEER,
        )
        assertEquals("agent:main:discord:acc1:direct:user123", key)
    }

    @Test
    fun `buildAgentPeerSessionKey for group chat`() {
        val key = buildAgentPeerSessionKey(
            agentId = "main",
            channel = "discord",
            peerKind = ChatType.GROUP,
            peerId = "room123",
        )
        assertEquals("agent:main:discord:group:room123", key)
    }

    @Test
    fun `resolveThreadSessionKeys appends thread suffix`() {
        val (key, parent) = resolveThreadSessionKeys(
            baseSessionKey = "agent:main:discord:group:room1",
            threadId = "thread-abc",
        )
        assertEquals("agent:main:discord:group:room1:thread:thread-abc", key)
        assertNull(parent)
    }

    @Test
    fun `resolveThreadSessionKeys with no thread`() {
        val (key, _) = resolveThreadSessionKeys(
            baseSessionKey = "agent:main:discord:group:room1",
        )
        assertEquals("agent:main:discord:group:room1", key)
    }

    @Test
    fun `classifySessionKeyShape`() {
        assertEquals(SessionKeyShape.MISSING, classifySessionKeyShape(null))
        assertEquals(SessionKeyShape.MISSING, classifySessionKeyShape(""))
        assertEquals(SessionKeyShape.AGENT, classifySessionKeyShape("agent:main:discord:direct:user1"))
        assertEquals(SessionKeyShape.MALFORMED_AGENT, classifySessionKeyShape("agent:"))
        assertEquals(SessionKeyShape.LEGACY_OR_ALIAS, classifySessionKeyShape("discord:user1"))
    }

    @Test
    fun `normalizeAccountId`() {
        assertEquals(DEFAULT_ACCOUNT_ID, normalizeAccountId(null))
        assertEquals(DEFAULT_ACCOUNT_ID, normalizeAccountId(""))
        assertEquals("myaccount", normalizeAccountId("MyAccount"))
        assertEquals("acc-1", normalizeAccountId("acc 1"))
    }

    @Test
    fun `normalizeAccountId blocks prototype keys`() {
        assertEquals(DEFAULT_ACCOUNT_ID, normalizeAccountId("__proto__"))
        assertEquals(DEFAULT_ACCOUNT_ID, normalizeAccountId("constructor"))
    }

    @Test
    fun `toAgentStoreSessionKey wraps raw keys`() {
        val key = toAgentStoreSessionKey("bot1", "discord:direct:user1")
        assertEquals("agent:bot1:discord:direct:user1", key)
    }

    @Test
    fun `toAgentStoreSessionKey preserves agent keys`() {
        val key = toAgentStoreSessionKey("bot1", "agent:bot2:custom")
        assertEquals("agent:bot2:custom", key)
    }

    @Test
    fun `toAgentStoreSessionKey empty defaults to main`() {
        val key = toAgentStoreSessionKey("bot1", "")
        assertEquals("agent:bot1:main", key)
    }

    @Test
    fun `identity links resolve canonical peer`() {
        val key = buildAgentPeerSessionKey(
            agentId = "main",
            channel = "discord",
            peerKind = ChatType.DIRECT,
            peerId = "discord_user_123",
            dmScope = DmScope.PER_PEER,
            identityLinks = mapOf(
                "john" to listOf("discord_user_123", "telegram_user_456"),
            ),
        )
        assertEquals("agent:main:direct:john", key)
    }
}
