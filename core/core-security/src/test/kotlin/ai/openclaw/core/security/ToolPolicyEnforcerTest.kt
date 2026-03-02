package ai.openclaw.core.security

import ai.openclaw.core.model.*
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class ToolPolicyEnforcerTest {

    private fun configWith(
        deny: List<String>? = null,
        allow: List<String>? = null,
        alsoAllow: List<String>? = null,
        profile: ToolProfileId? = null,
        agents: List<AgentConfig>? = null,
    ): OpenClawConfig {
        return OpenClawConfig(
            tools = ExpandedToolsConfig(
                deny = deny,
                allow = allow,
                alsoAllow = alsoAllow,
                profile = profile,
            ),
            agents = if (agents != null) AgentsConfig(list = agents) else null,
        )
    }

    @Test
    fun `allows all tools in FULL profile with no restrictions`() {
        val enforcer = ToolPolicyEnforcer(configWith(profile = ToolProfileId.FULL))
        assertTrue(enforcer.check("read_file").allowed)
        assertTrue(enforcer.check("browser_navigate").allowed)
        assertTrue(enforcer.check("custom_tool").allowed)
    }

    @Test
    fun `denies tools in global deny list`() {
        val enforcer = ToolPolicyEnforcer(configWith(
            profile = ToolProfileId.FULL,
            deny = listOf("dangerous_tool"),
        ))
        assertFalse(enforcer.check("dangerous_tool").allowed)
        assertTrue(enforcer.check("read_file").allowed)
    }

    @Test
    fun `allows only global allow list when set`() {
        val enforcer = ToolPolicyEnforcer(configWith(
            profile = ToolProfileId.FULL,
            allow = listOf("read_file", "write_file"),
        ))
        assertTrue(enforcer.check("read_file").allowed)
        assertTrue(enforcer.check("write_file").allowed)
        assertFalse(enforcer.check("execute_command").allowed)
    }

    @Test
    fun `alsoAllow extends allow list`() {
        val enforcer = ToolPolicyEnforcer(configWith(
            profile = ToolProfileId.FULL,
            allow = listOf("read_file"),
            alsoAllow = listOf("write_file"),
        ))
        assertTrue(enforcer.check("read_file").allowed)
        assertTrue(enforcer.check("write_file").allowed)
        assertFalse(enforcer.check("execute_command").allowed)
    }

    @Test
    fun `deny takes priority over allow`() {
        val enforcer = ToolPolicyEnforcer(configWith(
            profile = ToolProfileId.FULL,
            deny = listOf("read_file"),
            allow = listOf("read_file", "write_file"),
        ))
        assertFalse(enforcer.check("read_file").allowed)
        assertTrue(enforcer.check("write_file").allowed)
    }

    @Test
    fun `agent disabled list denies tools`() {
        val config = configWith(
            profile = ToolProfileId.FULL,
            agents = listOf(AgentConfig(
                id = "bot",
                tools = AgentToolsConfig(disabled = listOf("execute_command")),
            )),
        )
        val enforcer = ToolPolicyEnforcer(config)
        assertFalse(enforcer.check("execute_command", agentId = "bot").allowed)
        assertTrue(enforcer.check("read_file", agentId = "bot").allowed)
    }

    @Test
    fun `agent enabled list restricts to only those tools`() {
        val config = configWith(
            profile = ToolProfileId.FULL,
            agents = listOf(AgentConfig(
                id = "restricted",
                tools = AgentToolsConfig(enabled = listOf("read_file")),
            )),
        )
        val enforcer = ToolPolicyEnforcer(config)
        assertTrue(enforcer.check("read_file", agentId = "restricted").allowed)
        assertFalse(enforcer.check("write_file", agentId = "restricted").allowed)
    }

    @Test
    fun `minimal profile restricts tool set`() {
        val result = ToolPolicyEnforcer.checkProfile("browser_navigate", ToolProfileId.MINIMAL)
        assertFalse(result.allowed)

        val result2 = ToolPolicyEnforcer.checkProfile("read_file", ToolProfileId.MINIMAL)
        assertTrue(result2.allowed)
    }

    @Test
    fun `restricted tools denied in non-full profiles`() {
        for (tool in ToolPolicyEnforcer.RESTRICTED_TOOLS) {
            assertFalse(ToolPolicyEnforcer.checkProfile(tool, ToolProfileId.MINIMAL).allowed)
            assertFalse(ToolPolicyEnforcer.checkProfile(tool, ToolProfileId.MESSAGING).allowed)
            assertFalse(ToolPolicyEnforcer.checkProfile(tool, ToolProfileId.CODING).allowed)
        }
    }
}

class ToolAuditorTest {

    @Test
    fun `records and retrieves audit entries`() {
        val auditor = ToolAuditor()
        auditor.record("read_file", "bot", "session-1", allowed = true, reason = "allowed")
        auditor.record("write_file", "bot", "session-1", allowed = false, reason = "denied")

        val entries = auditor.recent()
        assertEquals(2, entries.size)
        assertTrue(entries[0].allowed)
        assertFalse(entries[1].allowed)
    }

    @Test
    fun `respects max entries limit`() {
        val auditor = ToolAuditor(maxEntries = 5)
        repeat(10) {
            auditor.record("tool_$it", "bot", null, allowed = true)
        }
        assertEquals(5, auditor.size())
    }

    @Test
    fun `filters entries by agent`() {
        val auditor = ToolAuditor()
        auditor.record("read_file", "bot-a", null, allowed = true)
        auditor.record("read_file", "bot-b", null, allowed = true)
        auditor.record("write_file", "bot-a", null, allowed = true)

        val entries = auditor.forAgent("bot-a")
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.agentId == "bot-a" })
    }

    @Test
    fun `filters entries by tool`() {
        val auditor = ToolAuditor()
        auditor.record("read_file", "bot", null, allowed = true)
        auditor.record("write_file", "bot", null, allowed = true)
        auditor.record("read_file", "bot", null, allowed = true)

        val entries = auditor.forTool("read_file")
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.toolName == "read_file" })
    }

    @Test
    fun `rate limiting triggers after threshold`() {
        val auditor = ToolAuditor(rateLimitPerMinute = 3)
        assertTrue(auditor.checkRateLimit("tool", "bot"))
        assertTrue(auditor.checkRateLimit("tool", "bot"))
        assertTrue(auditor.checkRateLimit("tool", "bot"))
        assertFalse(auditor.checkRateLimit("tool", "bot")) // 4th call exceeds limit
    }

    @Test
    fun `rate limiting is per tool per agent`() {
        val auditor = ToolAuditor(rateLimitPerMinute = 2)
        assertTrue(auditor.checkRateLimit("tool_a", "bot"))
        assertTrue(auditor.checkRateLimit("tool_a", "bot"))
        assertFalse(auditor.checkRateLimit("tool_a", "bot"))

        // Different tool still works
        assertTrue(auditor.checkRateLimit("tool_b", "bot"))

        // Different agent still works
        assertTrue(auditor.checkRateLimit("tool_a", "bot2"))
    }
}
