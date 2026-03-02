package ai.openclaw.core.security

import ai.openclaw.core.model.*

/**
 * Result of a tool policy evaluation.
 */
data class ToolPolicyResult(
    val allowed: Boolean,
    val reason: String? = null,
)

/**
 * Evaluate whether a tool is allowed for a given context.
 * Checks: global deny → agent-specific tools → global allow → default allow.
 */
fun isToolAllowed(
    toolName: String,
    config: OpenClawConfig,
    agentId: String? = null,
): Boolean = evaluateToolPolicy(toolName, config, agentId).allowed

/**
 * Evaluate tool policy with detailed reason.
 */
fun evaluateToolPolicy(
    toolName: String,
    config: OpenClawConfig,
    agentId: String? = null,
): ToolPolicyResult {
    val globalDeny = config.tools?.deny
    val globalAllow = config.tools?.allow
    val globalAlsoAllow = config.tools?.alsoAllow

    // 1. Global deny list (highest priority)
    if (globalDeny != null && toolName in globalDeny) {
        return ToolPolicyResult(allowed = false, reason = "denied by global tools.deny")
    }

    // 2. Agent-specific tool config
    if (agentId != null) {
        val agentConfig = config.agents?.list?.firstOrNull { it.id == agentId }
        val agentTools = agentConfig?.tools
        if (agentTools != null) {
            val disabled = agentTools.disabled
            if (disabled != null && toolName in disabled) {
                return ToolPolicyResult(allowed = false, reason = "disabled for agent $agentId")
            }
            val enabled = agentTools.enabled
            if (enabled != null) {
                return if (toolName in enabled) {
                    ToolPolicyResult(allowed = true, reason = "enabled for agent $agentId")
                } else {
                    ToolPolicyResult(allowed = false, reason = "not in enabled tools for agent $agentId")
                }
            }
        }
    }

    // 3. Global allow list
    if (globalAllow != null) {
        val allowed = toolName in globalAllow || (globalAlsoAllow != null && toolName in globalAlsoAllow)
        return ToolPolicyResult(
            allowed = allowed,
            reason = if (allowed) "global allow list" else "not in global allow list",
        )
    }

    // 4. No restrictions = allowed
    return ToolPolicyResult(allowed = true, reason = "no restrictions")
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
        DmPolicy.PAIRING -> true
        null -> true
    }
}

/**
 * Evaluate group chat policy.
 */
fun evaluateGroupPolicy(
    policy: GroupPolicy?,
    senderId: String,
    allowList: List<String>? = null,
): Boolean {
    return when (policy) {
        GroupPolicy.OPEN -> true
        GroupPolicy.DISABLED -> false
        GroupPolicy.ALLOWLIST -> allowList?.contains(senderId) == true
        null -> true
    }
}

/**
 * Simple audit log entry for security events.
 */
data class AuditEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val event: String,
    val agentId: String? = null,
    val sessionKey: String? = null,
    val toolName: String? = null,
    val allowed: Boolean? = null,
    val details: String? = null,
)

/**
 * In-memory audit log with configurable retention.
 */
class AuditLog(private val maxEntries: Int = 10000) {
    private val entries = ArrayDeque<AuditEntry>()

    fun log(entry: AuditEntry) {
        synchronized(entries) {
            entries.addLast(entry)
            while (entries.size > maxEntries) {
                entries.removeFirst()
            }
        }
    }

    fun logToolAccess(
        toolName: String,
        agentId: String?,
        sessionKey: String?,
        allowed: Boolean,
        reason: String? = null,
    ) {
        log(AuditEntry(
            event = "tool_access",
            agentId = agentId,
            sessionKey = sessionKey,
            toolName = toolName,
            allowed = allowed,
            details = reason,
        ))
    }

    fun recent(limit: Int = 100): List<AuditEntry> {
        synchronized(entries) {
            return entries.takeLast(limit)
        }
    }

    fun size(): Int = synchronized(entries) { entries.size }
}
