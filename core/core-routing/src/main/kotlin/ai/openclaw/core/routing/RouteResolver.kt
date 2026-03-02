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
    val identityLinks: Map<String, List<String>>? = null,
    val dmScope: DmScope? = null,
    val mainKey: String? = null,
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

        // Tier 1: Peer match (highest priority)
        val peer = match.peer
        if (peer != null) {
            if (peer.kind == input.chatType && peer.id == input.from) {
                val sessionKey = buildRouteSessionKey(binding.agentId, input)
                return ResolvedAgentRoute(
                    agentId = binding.agentId,
                    sessionKey = sessionKey,
                    matchDescription = "binding.peer",
                    binding = binding,
                )
            }
            continue
        }

        // Tier 2: Guild + roles match
        val guildId = match.guildId
        if (guildId != null && guildId == input.guildId) {
            val roles = match.roles
            if (!roles.isNullOrEmpty()) {
                val hasRole = input.roles?.any { it in roles } == true
                if (hasRole) {
                    val sessionKey = buildRouteSessionKey(binding.agentId, input)
                    return ResolvedAgentRoute(
                        agentId = binding.agentId,
                        sessionKey = sessionKey,
                        matchDescription = "binding.guild+roles",
                        binding = binding,
                    )
                }
                continue
            }
            // Tier 3: Guild-only match
            val sessionKey = buildRouteSessionKey(binding.agentId, input)
            return ResolvedAgentRoute(
                agentId = binding.agentId,
                sessionKey = sessionKey,
                matchDescription = "binding.guild",
                binding = binding,
            )
        }

        // Tier 4: Team match
        val teamId = match.teamId
        if (teamId != null && teamId == input.teamId) {
            val sessionKey = buildRouteSessionKey(binding.agentId, input)
            return ResolvedAgentRoute(
                agentId = binding.agentId,
                sessionKey = sessionKey,
                matchDescription = "binding.team",
                binding = binding,
            )
        }

        // Tier 5: Account match
        val accountId = match.accountId
        if (accountId != null) {
            // "*" matches any account, specific value matches exact
            if (accountId == "*" || accountId == input.accountId) {
                val sessionKey = buildRouteSessionKey(binding.agentId, input)
                return ResolvedAgentRoute(
                    agentId = binding.agentId,
                    sessionKey = sessionKey,
                    matchDescription = "binding.account",
                    binding = binding,
                )
            }
            continue
        }

        // Tier 6: Channel match (lowest binding priority - no other match criteria)
        if (guildId == null && teamId == null && peer == null) {
            val sessionKey = buildRouteSessionKey(binding.agentId, input)
            return ResolvedAgentRoute(
                agentId = binding.agentId,
                sessionKey = sessionKey,
                matchDescription = "binding.channel",
                binding = binding,
            )
        }
    }

    // Tier 7: Default agent fallback
    val sessionKey = buildRouteSessionKey(defaultAgentId, input)
    return ResolvedAgentRoute(
        agentId = defaultAgentId,
        sessionKey = sessionKey,
        matchDescription = "default",
    )
}

/**
 * Build a full session key from route resolution input using the proper peer session key builder.
 */
private fun buildRouteSessionKey(agentId: String, input: ResolveAgentRouteInput): String {
    val baseKey = buildAgentPeerSessionKey(
        agentId = agentId,
        channel = input.channel,
        mainKey = input.mainKey,
        accountId = input.accountId,
        peerKind = input.chatType,
        peerId = input.from,
        identityLinks = input.identityLinks,
        dmScope = input.dmScope,
    )

    // Apply thread suffix if needed
    val threadId = input.threadId
    if (threadId != null) {
        val (sessionKey, _) = resolveThreadSessionKeys(
            baseSessionKey = baseKey,
            threadId = threadId,
            parentSessionKey = input.parentSessionKey,
        )
        return sessionKey
    }
    return baseKey
}

// --- Binding utilities ---

/**
 * List all bindings from config.
 */
fun listBindings(config: OpenClawConfig): List<AgentBinding> {
    return config.bindings.orEmpty()
}

/**
 * List bound account IDs for a given channel.
 */
fun listBoundAccountIds(config: OpenClawConfig, channelId: String): List<String> {
    val normalizedChannel = channelId.trim().lowercase()
    if (normalizedChannel.isEmpty()) return emptyList()
    val ids = mutableSetOf<String>()
    for (binding in listBindings(config)) {
        val match = binding.match
        if (match.channel.trim().lowercase() != normalizedChannel) continue
        val accountId = match.accountId?.trim() ?: continue
        if (accountId.isEmpty() || accountId == "*") continue
        ids.add(normalizeAccountId(accountId))
    }
    return ids.sorted()
}

/**
 * Resolve default agent's bound account ID for a channel.
 */
fun resolveDefaultAgentBoundAccountId(config: OpenClawConfig, channelId: String): String? {
    val normalizedChannel = channelId.trim().lowercase()
    if (normalizedChannel.isEmpty()) return null
    val defaultAgentId = normalizeAgentId(
        config.agents?.list?.firstOrNull { it.default == true }?.id ?: DEFAULT_AGENT_ID
    )
    for (binding in listBindings(config)) {
        val match = binding.match
        if (match.channel.trim().lowercase() != normalizedChannel) continue
        if (normalizeAgentId(binding.agentId) != defaultAgentId) continue
        val accountId = match.accountId?.trim() ?: continue
        if (accountId.isEmpty() || accountId == "*") continue
        return normalizeAccountId(accountId)
    }
    return null
}
