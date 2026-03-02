package ai.openclaw.core.agent

import ai.openclaw.core.model.*

/**
 * Resolve agent configuration by ID from the global config.
 */
fun resolveAgentConfig(config: OpenClawConfig, agentId: String): AgentConfig? {
    return config.agents?.list?.firstOrNull { it.id == agentId }
}

/**
 * Get the default agent configuration.
 */
fun resolveDefaultAgent(config: OpenClawConfig): AgentConfig? {
    return config.agents?.list?.firstOrNull { it.default == true }
        ?: config.agents?.list?.firstOrNull { it.id == DEFAULT_AGENT_ID }
        ?: config.agents?.list?.firstOrNull()
}

/**
 * Resolve the primary model for an agent.
 * Resolution chain: agent config > agent defaults > global default
 */
fun resolveAgentModel(config: OpenClawConfig, agentId: String): String? {
    val agentConfig = resolveAgentConfig(config, agentId)
    val agentModel = agentConfig?.model?.primary
    if (agentModel != null) return agentModel

    val defaultsModel = config.agents?.defaults?.model?.primary
    if (defaultsModel != null) return defaultsModel

    return null
}

/**
 * Resolve model fallbacks for an agent.
 */
fun resolveAgentModelFallbacks(config: OpenClawConfig, agentId: String): List<String> {
    val agentConfig = resolveAgentConfig(config, agentId)
    val agentFallbacks = agentConfig?.model?.fallbacks
    if (!agentFallbacks.isNullOrEmpty()) return agentFallbacks

    val defaultsFallbacks = config.agents?.defaults?.model?.fallbacks
    return defaultsFallbacks.orEmpty()
}

/**
 * Resolve skill filter for an agent.
 */
fun resolveAgentSkillsFilter(config: OpenClawConfig, agentId: String): List<String>? {
    return resolveAgentConfig(config, agentId)?.skills
}

/**
 * List all configured agent IDs.
 */
fun listAgentIds(config: OpenClawConfig): List<String> {
    return config.agents?.list?.map { it.id }.orEmpty()
}

/**
 * Normalize an agent ID to a safe format.
 */
fun normalizeAgentId(agentId: String): String {
    val trimmed = agentId.trim().lowercase()
    if (trimmed.isEmpty()) return DEFAULT_AGENT_ID
    return trimmed.replace(Regex("[^a-z0-9_-]"), "_").take(64)
}
