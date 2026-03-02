package ai.openclaw.core.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionKeyUtilsTest {

    @Test
    fun `parse valid agent session key`() {
        val result = parseAgentSessionKey("agent:default:telegram:group:123")
        assertNotNull(result)
        assertEquals("default", result.agentId)
        assertEquals("telegram:group:123", result.rest)
    }

    @Test
    fun `parse normalizes to lowercase`() {
        val result = parseAgentSessionKey("Agent:MyBot:Discord:DM:456")
        assertNotNull(result)
        assertEquals("mybot", result.agentId)
        assertEquals("discord:dm:456", result.rest)
    }

    @Test
    fun `parse returns null for empty or short keys`() {
        assertNull(parseAgentSessionKey(null))
        assertNull(parseAgentSessionKey(""))
        assertNull(parseAgentSessionKey("  "))
        assertNull(parseAgentSessionKey("agent:default"))
        assertNull(parseAgentSessionKey("notanagent:x:y"))
    }

    @Test
    fun `derive chat type from session key`() {
        assertEquals(SessionKeyChatType.GROUP, deriveSessionChatType("agent:default:telegram:group:123"))
        assertEquals(SessionKeyChatType.CHANNEL, deriveSessionChatType("agent:default:slack:channel:general"))
        assertEquals(SessionKeyChatType.DIRECT, deriveSessionChatType("agent:default:telegram:direct:456"))
        assertEquals(SessionKeyChatType.DIRECT, deriveSessionChatType("agent:default:discord:dm:789"))
        assertEquals(SessionKeyChatType.UNKNOWN, deriveSessionChatType("agent:default:cron:daily"))
        assertEquals(SessionKeyChatType.UNKNOWN, deriveSessionChatType(null))
    }

    @Test
    fun `detect legacy Discord channel keys`() {
        assertEquals(
            SessionKeyChatType.CHANNEL,
            deriveSessionChatType("discord:myaccount:guild-123:channel-456")
        )
    }

    @Test
    fun `detect cron session keys`() {
        assertTrue(isCronSessionKey("agent:default:cron:daily"))
        assertTrue(isCronRunSessionKey("agent:default:cron:daily:run:abc123"))
        assertFalse(isCronRunSessionKey("agent:default:cron:daily"))
        assertFalse(isCronSessionKey("agent:default:telegram:group:123"))
    }

    @Test
    fun `detect subagent session keys`() {
        assertTrue(isSubagentSessionKey("subagent:task1"))
        assertTrue(isSubagentSessionKey("agent:default:subagent:task1"))
        assertFalse(isSubagentSessionKey("agent:default:telegram:dm:123"))
    }

    @Test
    fun `count subagent depth`() {
        assertEquals(0, getSubagentDepth("agent:default:telegram:dm:123"))
        assertEquals(1, getSubagentDepth("agent:default:subagent:task1"))
        assertEquals(2, getSubagentDepth("agent:default:subagent:t1:subagent:t2"))
    }

    @Test
    fun `detect ACP session keys`() {
        assertTrue(isAcpSessionKey("acp:session-1"))
        assertTrue(isAcpSessionKey("agent:default:acp:session-1"))
        assertFalse(isAcpSessionKey("agent:default:telegram:dm:123"))
    }

    @Test
    fun `resolve thread parent session key`() {
        assertEquals(
            "agent:default:telegram:group:123",
            resolveThreadParentSessionKey("agent:default:telegram:group:123:thread:456")
        )
        assertEquals(
            "agent:default:slack:channel:general",
            resolveThreadParentSessionKey("agent:default:slack:channel:general:topic:789")
        )
        assertNull(resolveThreadParentSessionKey("agent:default:telegram:dm:123"))
        assertNull(resolveThreadParentSessionKey(null))
    }
}
