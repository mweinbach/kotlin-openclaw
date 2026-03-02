package ai.openclaw.core.security

import ai.openclaw.core.model.*

/**
 * Evaluate whether a tool is allowed for a given context.
 */
fun isToolAllowed(
    toolName: String,
    config: OpenClawConfig,
    agentId: String? = null,
): Boolean {
    val globalAllow = config.tools?.allow
    val globalDeny = config.tools?.deny
    val globalAlsoAllow = config.tools?.alsoAllow

    // Deny list takes priority
    if (globalDeny != null && toolName in globalDeny) return false

    // Check explicit allow
    if (globalAllow != null) {
        return toolName in globalAllow || (globalAlsoAllow != null && toolName in globalAlsoAllow)
    }

    // No restrictions = allowed
    return true
}

/**
 * Evaluate DM policy for a channel.
 */
fun evaluateDmPolicy(
    policy: DmPolicy?,
    senderId: String,
    allowList: List<String>? = null,
): Boolean {
    return when (policy) {
        DmPolicy.OPEN -> true
        DmPolicy.DISABLED -> false
        DmPolicy.ALLOWLIST -> allowList?.contains(senderId) == true
        DmPolicy.PAIRING -> true // Pairing flow handled separately
        null -> true // Default: allow
    }
}
