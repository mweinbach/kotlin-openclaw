package ai.openclaw.core.routing

import ai.openclaw.core.model.*

/**
 * Resolved route mapping a message to an agent and session.
 */
data class ResolvedAgentRoute(
    val agentId: String,
    val sessionKey: String,
    val matchDescription: String,
    val binding: AgentBinding? = null,
)

/**
 * Input for route resolution.
 */
data class ResolveAgentRouteInput(
    val channel: String,
    val accountId: String,
    val from: String,
    val chatType: ChatType,
    val guildId: String? = null,
    val roles: List<String>? = null,
    val teamId: String? = null,
    val threadId: String? = null,
    val parentSessionKey: String? = null,
)

/**
 * Resolve which agent and session key should handle a message.
 * Uses tier-based binding resolution:
 *   1. Peer match (direct chats)
 *   2. Guild + roles match
 *   3. Guild-only match
 *   4. Team match
 *   5. Account match
 *   6. Channel match
 *   7. Default agent
 */
fun resolveAgentRoute(
    config: OpenClawConfig,
    input: ResolveAgentRouteInput,
): ResolvedAgentRoute {
    val bindings = config.bindings.orEmpty()
    val defaultAgentId = config.agents?.list
        ?.firstOrNull { it.default == true }?.id
        ?: DEFAULT_AGENT_ID

    // Try tier-based matching
    for (binding in bindings) {
        val match = binding.match
        if (match.channel != input.channel) continue

        // Peer match (highest priority)
        val peer = match.peer
        if (peer != null) {
            if (peer.kind == input.chatType && peer.id == input.from) {
                val sessionKey = buildSessionKey(binding.agentId, input)
                return ResolvedAgentRoute(
                    agentId = binding.agentId,
                    sessionKey = sessionKey,
                    matchDescription = "peer:${input.from}",
                    binding = binding,
                )
            }
            continue
        }

        // Guild + roles match
        val guildId = match.guildId
        if (guildId != null && guildId == input.guildId) {
            val roles = match.roles
            if (!roles.isNullOrEmpty()) {
                val hasRole = input.roles?.any { it in roles } == true
                if (hasRole) {
                    val sessionKey = buildSessionKey(binding.agentId, input)
                    return ResolvedAgentRoute(
                        agentId = binding.agentId,
                        sessionKey = sessionKey,
                        matchDescription = "guild+roles:$guildId",
                        binding = binding,
                    )
                }
                continue
            }
            // Guild-only match
            val sessionKey = buildSessionKey(binding.agentId, input)
            return ResolvedAgentRoute(
                agentId = binding.agentId,
                sessionKey = sessionKey,
                matchDescription = "guild:$guildId",
                binding = binding,
            )
        }

        // Team match
        val teamId = match.teamId
        if (teamId != null && teamId == input.teamId) {
            val sessionKey = buildSessionKey(binding.agentId, input)
            return ResolvedAgentRoute(
                agentId = binding.agentId,
                sessionKey = sessionKey,
                matchDescription = "team:$teamId",
                binding = binding,
            )
        }

        // Account match
        val accountId = match.accountId
        if (accountId != null && accountId == input.accountId) {
            val sessionKey = buildSessionKey(binding.agentId, input)
            return ResolvedAgentRoute(
                agentId = binding.agentId,
                sessionKey = sessionKey,
                matchDescription = "account:$accountId",
                binding = binding,
            )
        }

        // Channel match (lowest binding priority)
        if (match.accountId == null && match.guildId == null && match.teamId == null && match.peer == null) {
            val sessionKey = buildSessionKey(binding.agentId, input)
            return ResolvedAgentRoute(
                agentId = binding.agentId,
                sessionKey = sessionKey,
                matchDescription = "channel:${match.channel}",
                binding = binding,
            )
        }
    }

    // Default agent fallback
    val sessionKey = buildSessionKey(defaultAgentId, input)
    return ResolvedAgentRoute(
        agentId = defaultAgentId,
        sessionKey = sessionKey,
        matchDescription = "default",
    )
}

private fun buildSessionKey(agentId: String, input: ResolveAgentRouteInput): String {
    val normalizedAgentId = agentId.lowercase().replace(Regex("[^a-z0-9_-]"), "_")
    val chatTypeStr = when (input.chatType) {
        ChatType.DIRECT -> "direct"
        ChatType.GROUP -> "group"
        ChatType.CHANNEL -> "channel"
    }
    val base = "agent:$normalizedAgentId:${input.channel}:$chatTypeStr:${input.from}"
    return if (input.threadId != null) "$base:thread:${input.threadId}" else base
}
