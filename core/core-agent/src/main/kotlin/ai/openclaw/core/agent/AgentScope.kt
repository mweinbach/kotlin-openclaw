package ai.openclaw.core.agent

import ai.openclaw.core.model.*
import ai.openclaw.core.session.parseAgentSessionKey

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
 * Resolve the default agent ID.
 */
fun resolveDefaultAgentId(config: OpenClawConfig): String {
    return resolveDefaultAgent(config)?.id ?: DEFAULT_AGENT_ID
}

/**
 * List all configured agent entries.
 */
fun listAgentEntries(config: OpenClawConfig): List<AgentConfig> {
    return config.agents?.list.orEmpty()
}

/**
 * List all configured agent IDs.
 */
fun listAgentIds(config: OpenClawConfig): List<String> {
    return config.agents?.list?.map { it.id }.orEmpty()
}

/**
 * Resolve session and default agent IDs for a given context.
 */
fun resolveSessionAgentIds(
    config: OpenClawConfig,
    explicitAgentId: String? = null,
    sessionKey: String? = null,
): Pair<String, String> {
    val defaultAgentId = resolveDefaultAgentId(config)
    val sessionAgentId = resolveSessionAgentId(config, explicitAgentId, sessionKey)
    return defaultAgentId to sessionAgentId
}

/**
 * Resolve which agent ID should handle a session.
 */
fun resolveSessionAgentId(
    config: OpenClawConfig,
    explicitAgentId: String? = null,
    sessionKey: String? = null,
): String {
    // Explicit agent ID takes priority
    if (!explicitAgentId.isNullOrBlank()) {
        return explicitAgentId.trim()
    }
    // Try to extract from session key
    if (!sessionKey.isNullOrBlank()) {
        val parsed = parseAgentSessionKey(sessionKey)
        if (parsed != null) return parsed.agentId
    }
    // Fall back to default
    return resolveDefaultAgentId(config)
}

/**
 * Resolve the primary model for an agent (agent-specific config only, no defaults).
 */
fun resolveAgentExplicitModelPrimary(config: OpenClawConfig, agentId: String): String? {
    return resolveAgentConfig(config, agentId)?.model?.primary
}

/**
 * Resolve the effective primary model for an agent.
 * Fallback chain: agent config > agent defaults > null
 */
fun resolveAgentEffectiveModelPrimary(config: OpenClawConfig, agentId: String): String? {
    // 1. Agent-specific model
    val agentModel = resolveAgentExplicitModelPrimary(config, agentId)
    if (agentModel != null) return agentModel

    // 2. Agent defaults model
    val defaultsModel = config.agents?.defaults?.model?.primary
    if (defaultsModel != null) return defaultsModel

    return null
}

// Keep old name as alias
fun resolveAgentModel(config: OpenClawConfig, agentId: String): String? {
    return resolveAgentEffectiveModelPrimary(config, agentId)
}

/**
 * Resolve the agent's explicit model fallbacks override.
 * Returns null if no override exists (meaning use defaults).
 * Returns empty list if explicitly set to empty (disabling global fallbacks).
 */
fun resolveAgentModelFallbacksOverride(config: OpenClawConfig, agentId: String): List<String>? {
    val agentConfig = resolveAgentConfig(config, agentId)
    return agentConfig?.model?.fallbacks  // null = no override, empty = explicit disable
}

/**
 * Resolve model fallbacks for an agent.
 * Falls back to defaults if agent has no override.
 */
fun resolveAgentModelFallbacks(config: OpenClawConfig, agentId: String): List<String> {
    val override = resolveAgentModelFallbacksOverride(config, agentId)
    if (override != null) return override
    return config.agents?.defaults?.model?.fallbacks.orEmpty()
}

/**
 * Check if any model fallbacks are configured (agent-specific or default).
 */
fun hasConfiguredModelFallbacks(config: OpenClawConfig, agentId: String): Boolean {
    val override = resolveAgentModelFallbacksOverride(config, agentId)
    if (override != null) return override.isNotEmpty()
    return config.agents?.defaults?.model?.fallbacks?.isNotEmpty() == true
}

/**
 * Resolve effective model fallbacks, considering session model overrides.
 */
fun resolveEffectiveModelFallbacks(
    config: OpenClawConfig,
    agentId: String,
    hasSessionModelOverride: Boolean = false,
): List<String> {
    if (hasSessionModelOverride) {
        // When session overrides model, use agent's explicit override or default fallbacks
        val override = resolveAgentModelFallbacksOverride(config, agentId)
        return override ?: config.agents?.defaults?.model?.fallbacks.orEmpty()
    }
    // Use only agent's explicit override
    return resolveAgentModelFallbacksOverride(config, agentId)
        ?: config.agents?.defaults?.model?.fallbacks.orEmpty()
}

/**
 * Resolve fallback agent ID from params or defaults.
 */
fun resolveFallbackAgentId(
    config: OpenClawConfig,
    explicitAgentId: String? = null,
    sessionKey: String? = null,
): String {
    return resolveSessionAgentId(config, explicitAgentId, sessionKey)
}

/**
 * Resolve skill filter for an agent.
 */
fun resolveAgentSkillsFilter(config: OpenClawConfig, agentId: String): List<String>? {
    return resolveAgentConfig(config, agentId)?.skills
}

/**
 * Resolve workspace directory for an agent.
 * Fallback chain: agent workspace > default agent workspace > null
 */
fun resolveAgentWorkspaceDir(config: OpenClawConfig, agentId: String): String? {
    val agentConfig = resolveAgentConfig(config, agentId)
    val agentWorkspace = agentConfig?.workspace
    if (agentWorkspace != null) return agentWorkspace

    // If this is the default agent, check defaults
    if (agentConfig?.default == true || agentId == resolveDefaultAgentId(config)) {
        val defaultWorkspace = config.agents?.defaults?.workspace
        if (defaultWorkspace != null) return defaultWorkspace
    }
    return null
}

/**
 * Resolve agent directory for an agent.
 */
fun resolveAgentDir(config: OpenClawConfig, agentId: String): String? {
    return resolveAgentConfig(config, agentId)?.agentDir
}
