package ai.openclaw.core.agent

import ai.openclaw.core.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentScopeTest {

    private val testConfig = OpenClawConfig(
        agents = AgentsConfig(
            defaults = AgentDefaultsConfig(
                model = AgentModelConfig(primary = "claude-3-sonnet", fallbacks = listOf("gpt-4o")),
            ),
            list = listOf(
                AgentConfig(id = "main", default = true, name = "Main", model = null),
                AgentConfig(
                    id = "support",
                    name = "Support",
                    model = AgentModelConfig(primary = "claude-3-haiku"),
                    skills = listOf("translate", "summarize"),
                    workspace = "/data/support",
                ),
                AgentConfig(
                    id = "advanced",
                    name = "Advanced",
                    model = AgentModelConfig(primary = "claude-3-opus", fallbacks = emptyList()),
                ),
            ),
        ),
    )

    @Test
    fun `resolveDefaultAgentId returns default agent`() {
        assertEquals("main", resolveDefaultAgentId(testConfig))
    }

    @Test
    fun `resolveDefaultAgentId falls back to DEFAULT_AGENT_ID`() {
        assertEquals(DEFAULT_AGENT_ID, resolveDefaultAgentId(OpenClawConfig()))
    }

    @Test
    fun `resolveAgentConfig finds agent by ID`() {
        val config = resolveAgentConfig(testConfig, "support")
        assertEquals("support", config?.id)
        assertEquals("Support", config?.name)
    }

    @Test
    fun `resolveAgentConfig returns null for unknown`() {
        assertNull(resolveAgentConfig(testConfig, "nonexistent"))
    }

    @Test
    fun `resolveAgentEffectiveModelPrimary uses agent-specific model`() {
        assertEquals("claude-3-haiku", resolveAgentEffectiveModelPrimary(testConfig, "support"))
    }

    @Test
    fun `resolveAgentEffectiveModelPrimary falls back to defaults`() {
        assertEquals("claude-3-sonnet", resolveAgentEffectiveModelPrimary(testConfig, "main"))
    }

    @Test
    fun `resolveAgentExplicitModelPrimary returns null for default agent`() {
        assertNull(resolveAgentExplicitModelPrimary(testConfig, "main"))
    }

    @Test
    fun `resolveAgentModelFallbacks uses agent override`() {
        // "advanced" has explicit empty fallbacks (disabling global fallbacks)
        val fallbacks = resolveAgentModelFallbacks(testConfig, "advanced")
        assertTrue(fallbacks.isEmpty())
    }

    @Test
    fun `resolveAgentModelFallbacks falls back to defaults`() {
        val fallbacks = resolveAgentModelFallbacks(testConfig, "main")
        assertEquals(listOf("gpt-4o"), fallbacks)
    }

    @Test
    fun `hasConfiguredModelFallbacks`() {
        assertTrue(hasConfiguredModelFallbacks(testConfig, "main")) // defaults have fallbacks
        assertTrue(!hasConfiguredModelFallbacks(testConfig, "advanced")) // explicitly empty
    }

    @Test
    fun `resolveSessionAgentId from explicit`() {
        assertEquals("support", resolveSessionAgentId(testConfig, explicitAgentId = "support"))
    }

    @Test
    fun `resolveSessionAgentId from session key`() {
        assertEquals("support", resolveSessionAgentId(
            testConfig, sessionKey = "agent:support:discord:direct:user1",
        ))
    }

    @Test
    fun `resolveSessionAgentId defaults`() {
        assertEquals("main", resolveSessionAgentId(testConfig))
    }

    @Test
    fun `resolveAgentSkillsFilter`() {
        assertEquals(listOf("translate", "summarize"), resolveAgentSkillsFilter(testConfig, "support"))
        assertNull(resolveAgentSkillsFilter(testConfig, "main"))
    }

    @Test
    fun `resolveAgentWorkspaceDir`() {
        assertEquals("/data/support", resolveAgentWorkspaceDir(testConfig, "support"))
        assertNull(resolveAgentWorkspaceDir(testConfig, "main"))
    }

    @Test
    fun `listAgentIds`() {
        assertEquals(listOf("main", "support", "advanced"), listAgentIds(testConfig))
    }
}
