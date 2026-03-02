package ai.openclaw.core.security

import ai.openclaw.core.model.*

/**
 * Tool security enforcement with profiles, per-agent allow/deny,
 * rate limiting, and audit logging.
 *
 * Ported from src/security/tool-policy.ts
 */
class ToolPolicyEnforcer(
    private val config: OpenClawConfig,
    private val auditor: ToolAuditor = ToolAuditor(),
) {
    /**
     * Check whether a tool call should be allowed.
     * Returns a decision with the reason and whether the call was audited.
     */
    fun check(
        toolName: String,
        agentId: String? = null,
        sessionKey: String? = null,
    ): ToolEnforcementResult {
        // 1. Profile-based check
        val profile = resolveProfile(agentId)
        val profileResult = checkProfile(toolName, profile)
        if (!profileResult.allowed) {
            auditor.record(toolName, agentId, sessionKey, allowed = false, reason = profileResult.reason)
            return profileResult
        }

        // 2. Global deny list (highest priority)
        val globalDeny = config.tools?.deny
        if (globalDeny != null && toolName in globalDeny) {
            val result = ToolEnforcementResult(
                allowed = false,
                reason = "denied by global tools.deny",
            )
            auditor.record(toolName, agentId, sessionKey, allowed = false, reason = result.reason)
            return result
        }

        // 3. Agent-specific tool config
        if (agentId != null) {
            val agentConfig = config.agents?.list?.firstOrNull { it.id == agentId }
            val agentTools = agentConfig?.tools
            if (agentTools != null) {
                val disabled = agentTools.disabled
                if (disabled != null && toolName in disabled) {
                    val result = ToolEnforcementResult(
                        allowed = false,
                        reason = "disabled for agent $agentId",
                    )
                    auditor.record(toolName, agentId, sessionKey, allowed = false, reason = result.reason)
                    return result
                }
                val enabled = agentTools.enabled
                if (enabled != null && toolName !in enabled) {
                    val result = ToolEnforcementResult(
                        allowed = false,
                        reason = "not in enabled tools for agent $agentId",
                    )
                    auditor.record(toolName, agentId, sessionKey, allowed = false, reason = result.reason)
                    return result
                }
            }
        }

        // 4. Global allow list
        val globalAllow = config.tools?.allow
        if (globalAllow != null) {
            val globalAlsoAllow = config.tools?.alsoAllow
            val allowed = toolName in globalAllow || (globalAlsoAllow != null && toolName in globalAlsoAllow)
            if (!allowed) {
                val result = ToolEnforcementResult(
                    allowed = false,
                    reason = "not in global allow list",
                )
                auditor.record(toolName, agentId, sessionKey, allowed = false, reason = result.reason)
                return result
            }
        }

        // 5. Rate limit check
        if (!auditor.checkRateLimit(toolName, agentId)) {
            val result = ToolEnforcementResult(
                allowed = false,
                reason = "rate limit exceeded for tool $toolName",
            )
            auditor.record(toolName, agentId, sessionKey, allowed = false, reason = result.reason)
            return result
        }

        // Allowed
        val result = ToolEnforcementResult(allowed = true, reason = "allowed")
        auditor.record(toolName, agentId, sessionKey, allowed = true, reason = result.reason)
        return result
    }

    /**
     * Resolve the effective tool profile for an agent.
     */
    private fun resolveProfile(agentId: String?): ToolProfileId {
        // Check agent-specific profile first
        if (agentId != null) {
            val agentConfig = config.agents?.list?.firstOrNull { it.id == agentId }
            // Check per-agent tools config for profile hints
            val agentTools = agentConfig?.tools
            if (agentTools != null) {
                // If agent has specific tools enabled, treat as BASIC profile
                if (agentTools.enabled != null) return ToolProfileId.CODING
            }
        }
        // Global profile
        return config.tools?.profile ?: ToolProfileId.FULL
    }

    companion object {
        // Tool categories for profile-based filtering
        val MINIMAL_TOOLS = setOf(
            "read_file", "list_files", "search_files",
            "web_search", "web_fetch",
        )

        val BASIC_TOOLS = MINIMAL_TOOLS + setOf(
            "write_file", "edit_file", "create_file",
            "execute_command",
            "message_send", "message_reply",
        )

        val CODING_TOOLS = BASIC_TOOLS + setOf(
            "git_status", "git_diff", "git_log", "git_commit",
            "run_tests", "lint",
            "subagent",
        )

        val FULL_TOOLS: Set<String>? = null // null means all tools allowed

        // Restricted tools not allowed in BASIC or MINIMAL profiles
        val RESTRICTED_TOOLS = setOf(
            "browser_navigate", "browser_screenshot", "browser_click",
            "screen_capture", "screen_click",
            "system_exec", "shell",
        )

        fun checkProfile(toolName: String, profile: ToolProfileId): ToolEnforcementResult {
            val allowedSet = when (profile) {
                ToolProfileId.MINIMAL -> MINIMAL_TOOLS
                ToolProfileId.MESSAGING -> BASIC_TOOLS
                ToolProfileId.CODING -> CODING_TOOLS
                ToolProfileId.FULL -> null // all allowed
            }

            // FULL profile allows everything
            if (allowedSet == null) {
                return ToolEnforcementResult(allowed = true, reason = "full profile")
            }

            // Non-full profiles deny restricted tools
            if (profile != ToolProfileId.FULL && toolName in RESTRICTED_TOOLS) {
                return ToolEnforcementResult(
                    allowed = false,
                    reason = "tool $toolName restricted in $profile profile",
                )
            }

            // Check if tool is in allowed set
            return if (toolName in allowedSet) {
                ToolEnforcementResult(allowed = true, reason = "$profile profile")
            } else {
                ToolEnforcementResult(
                    allowed = false,
                    reason = "tool $toolName not in $profile profile",
                )
            }
        }
    }
}

/**
 * Result of a tool enforcement check.
 */
data class ToolEnforcementResult(
    val allowed: Boolean,
    val reason: String? = null,
)

/**
 * Audit log entry for tool calls with timing and result info.
 */
data class ToolAuditEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val toolName: String,
    val agentId: String? = null,
    val sessionKey: String? = null,
    val allowed: Boolean,
    val reason: String? = null,
    val durationMs: Long? = null,
    val resultStatus: String? = null,
    val params: String? = null,
)

/**
 * Audit logger for tool calls with rate limiting support.
 * Tracks every tool invocation with params, result status, and duration.
 */
class ToolAuditor(
    private val maxEntries: Int = 10_000,
    private val rateLimitPerMinute: Int = 120,
) {
    private val entries = ArrayDeque<ToolAuditEntry>()
    private val rateLimitCounters = java.util.concurrent.ConcurrentHashMap<String, RateLimitCounter>()

    private data class RateLimitCounter(
        val count: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger(0),
        @Volatile var windowStart: Long = System.currentTimeMillis(),
    )

    /**
     * Record a tool access event.
     */
    fun record(
        toolName: String,
        agentId: String?,
        sessionKey: String?,
        allowed: Boolean,
        reason: String? = null,
        durationMs: Long? = null,
        resultStatus: String? = null,
        params: String? = null,
    ) {
        val entry = ToolAuditEntry(
            toolName = toolName,
            agentId = agentId,
            sessionKey = sessionKey,
            allowed = allowed,
            reason = reason,
            durationMs = durationMs,
            resultStatus = resultStatus,
            params = params,
        )
        synchronized(entries) {
            entries.addLast(entry)
            while (entries.size > maxEntries) {
                entries.removeFirst()
            }
        }
    }

    /**
     * Check if a tool call is within rate limits.
     */
    fun checkRateLimit(toolName: String, agentId: String?): Boolean {
        val key = "${agentId ?: "global"}:$toolName"
        val now = System.currentTimeMillis()
        val counter = rateLimitCounters.getOrPut(key) { RateLimitCounter() }

        if (now - counter.windowStart > 60_000) {
            counter.count.set(0)
            counter.windowStart = now
        }

        return counter.count.incrementAndGet() <= rateLimitPerMinute
    }

    /**
     * Get recent audit entries.
     */
    fun recent(limit: Int = 100): List<ToolAuditEntry> {
        synchronized(entries) {
            return entries.takeLast(limit)
        }
    }

    /**
     * Get entries for a specific agent.
     */
    fun forAgent(agentId: String, limit: Int = 100): List<ToolAuditEntry> {
        synchronized(entries) {
            return entries.filter { it.agentId == agentId }.takeLast(limit)
        }
    }

    /**
     * Get entries for a specific tool.
     */
    fun forTool(toolName: String, limit: Int = 100): List<ToolAuditEntry> {
        synchronized(entries) {
            return entries.filter { it.toolName == toolName }.takeLast(limit)
        }
    }

    fun size(): Int = synchronized(entries) { entries.size }
}
