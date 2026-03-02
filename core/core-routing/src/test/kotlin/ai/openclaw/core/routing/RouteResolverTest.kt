package ai.openclaw.core.routing

import ai.openclaw.core.model.*
import kotlin.test.Test
import kotlin.test.assertEquals

class RouteResolverTest {

    private fun makeConfig(
        agents: List<AgentConfig> = listOf(
            AgentConfig(id = "main", default = true, name = "Main Agent"),
            AgentConfig(id = "support", name = "Support Agent"),
        ),
        bindings: List<AgentBinding> = emptyList(),
    ): OpenClawConfig = OpenClawConfig(
        agents = AgentsConfig(list = agents),
        bindings = bindings,
    )

    @Test
    fun `default fallback when no bindings`() {
        val config = makeConfig()
        val result = resolveAgentRoute(config, ResolveAgentRouteInput(
            channel = "discord", accountId = "acc1", from = "user1", chatType = ChatType.DIRECT,
        ))
        assertEquals("main", result.agentId)
        assertEquals("default", result.matchDescription)
    }

    @Test
    fun `peer match`() {
        val config = makeConfig(bindings = listOf(
            AgentBinding(
                agentId = "support",
                match = AgentBindingMatch(
                    channel = "discord",
                    peer = PeerMatch(kind = ChatType.DIRECT, id = "vip-user"),
                ),
            ),
        ))
        val result = resolveAgentRoute(config, ResolveAgentRouteInput(
            channel = "discord", accountId = "acc1", from = "vip-user", chatType = ChatType.DIRECT,
        ))
        assertEquals("support", result.agentId)
        assertEquals("binding.peer", result.matchDescription)
    }

    @Test
    fun `peer match skips non-matching peer`() {
        val config = makeConfig(bindings = listOf(
            AgentBinding(
                agentId = "support",
                match = AgentBindingMatch(
                    channel = "discord",
                    peer = PeerMatch(kind = ChatType.DIRECT, id = "other-user"),
                ),
            ),
        ))
        val result = resolveAgentRoute(config, ResolveAgentRouteInput(
            channel = "discord", accountId = "acc1", from = "user1", chatType = ChatType.DIRECT,
        ))
        assertEquals("main", result.agentId)
        assertEquals("default", result.matchDescription)
    }

    @Test
    fun `guild + roles match`() {
        val config = makeConfig(bindings = listOf(
            AgentBinding(
                agentId = "support",
                match = AgentBindingMatch(
                    channel = "discord",
                    guildId = "guild-1",
                    roles = listOf("admin", "mod"),
                ),
            ),
        ))
        val result = resolveAgentRoute(config, ResolveAgentRouteInput(
            channel = "discord", accountId = "acc1", from = "user1",
            chatType = ChatType.GROUP, guildId = "guild-1", roles = listOf("mod"),
        ))
        assertEquals("support", result.agentId)
        assertEquals("binding.guild+roles", result.matchDescription)
    }

    @Test
    fun `guild only match`() {
        val config = makeConfig(bindings = listOf(
            AgentBinding(
                agentId = "support",
                match = AgentBindingMatch(
                    channel = "discord",
                    guildId = "guild-1",
                ),
            ),
        ))
        val result = resolveAgentRoute(config, ResolveAgentRouteInput(
            channel = "discord", accountId = "acc1", from = "user1",
            chatType = ChatType.GROUP, guildId = "guild-1",
        ))
        assertEquals("support", result.agentId)
        assertEquals("binding.guild", result.matchDescription)
    }

    @Test
    fun `team match`() {
        val config = makeConfig(bindings = listOf(
            AgentBinding(
                agentId = "support",
                match = AgentBindingMatch(
                    channel = "slack",
                    teamId = "team-1",
                ),
            ),
        ))
        val result = resolveAgentRoute(config, ResolveAgentRouteInput(
            channel = "slack", accountId = "acc1", from = "user1",
            chatType = ChatType.GROUP, teamId = "team-1",
        ))
        assertEquals("support", result.agentId)
        assertEquals("binding.team", result.matchDescription)
    }

    @Test
    fun `account match with wildcard`() {
        val config = makeConfig(bindings = listOf(
            AgentBinding(
                agentId = "support",
                match = AgentBindingMatch(
                    channel = "telegram",
                    accountId = "*",
                ),
            ),
        ))
        val result = resolveAgentRoute(config, ResolveAgentRouteInput(
            channel = "telegram", accountId = "any-account", from = "user1",
            chatType = ChatType.DIRECT,
        ))
        assertEquals("support", result.agentId)
        assertEquals("binding.account", result.matchDescription)
    }

    @Test
    fun `channel match lowest priority`() {
        val config = makeConfig(bindings = listOf(
            AgentBinding(
                agentId = "support",
                match = AgentBindingMatch(channel = "telegram"),
            ),
        ))
        val result = resolveAgentRoute(config, ResolveAgentRouteInput(
            channel = "telegram", accountId = "acc1", from = "user1",
            chatType = ChatType.DIRECT,
        ))
        assertEquals("support", result.agentId)
        assertEquals("binding.channel", result.matchDescription)
    }

    @Test
    fun `channel mismatch falls through to default`() {
        val config = makeConfig(bindings = listOf(
            AgentBinding(
                agentId = "support",
                match = AgentBindingMatch(channel = "slack"),
            ),
        ))
        val result = resolveAgentRoute(config, ResolveAgentRouteInput(
            channel = "discord", accountId = "acc1", from = "user1",
            chatType = ChatType.DIRECT,
        ))
        assertEquals("main", result.agentId)
        assertEquals("default", result.matchDescription)
    }
}
