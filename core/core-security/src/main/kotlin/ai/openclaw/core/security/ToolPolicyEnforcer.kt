package ai.openclaw.core.security

import ai.openclaw.core.model.*

data class ToolPolicyContext(
    val agentId: String? = null,
    val sessionKey: String? = null,
    val modelProvider: String? = null,
    val modelId: String? = null,
    val messageProvider: String? = null,
    val senderIsOwner: Boolean? = null,
    val sandboxed: Boolean? = null,
    val sandboxPolicy: SandboxToolsPolicy? = null,
    val groupPolicy: GroupToolPolicyConfig? = null,
    val subagentDepth: Int? = null,
)

/**
 * Tool security enforcement with profiles, per-agent allow/deny,
 * layered policy stages, and audit logging.
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
        includeRateLimit: Boolean = true,
        recordAudit: Boolean = true,
    ): ToolEnforcementResult = check(
        toolName = toolName,
        context = ToolPolicyContext(
            agentId = agentId,
            sessionKey = sessionKey,
        ),
        includeRateLimit = includeRateLimit,
        recordAudit = recordAudit,
    )

    /**
     * Check whether a tool call should be allowed using layered policy context.
     */
    fun check(
        toolName: String,
        context: ToolPolicyContext,
        includeRateLimit: Boolean = true,
        recordAudit: Boolean = true,
    ): ToolEnforcementResult {
        val normalizedTool = normalizeToolName(toolName)

        fun audited(result: ToolEnforcementResult): ToolEnforcementResult {
            if (recordAudit) {
                auditor.record(
                    toolName = normalizedTool,
                    agentId = context.agentId,
                    sessionKey = context.sessionKey,
                    allowed = result.allowed,
                    reason = result.reason,
                )
            }
            return result
        }

        val normalizedProvider = normalizeMessageProvider(context.messageProvider)
        if (normalizedProvider != null) {
            val denied = TOOL_DENY_BY_MESSAGE_PROVIDER[normalizedProvider]
            if (denied?.contains(normalizedTool) == true) {
                return audited(
                    ToolEnforcementResult(
                        allowed = false,
                        reason = "tool $normalizedTool denied for message provider $normalizedProvider",
                    ),
                )
            }
        }

        if (context.senderIsOwner == false && isOwnerOnlyTool(normalizedTool)) {
            return audited(
                ToolEnforcementResult(
                    allowed = false,
                    reason = "tool $normalizedTool restricted to owner senders",
                ),
            )
        }

        for (stage in resolvePolicyStages(context)) {
            val stageResult = evaluateStage(normalizedTool, stage)
            if (stageResult != null) {
                return audited(stageResult)
            }
        }

        if (includeRateLimit && !auditor.checkRateLimit(normalizedTool, context.agentId)) {
            return audited(
                ToolEnforcementResult(
                    allowed = false,
                    reason = "rate limit exceeded for tool $normalizedTool",
                ),
            )
        }

        return audited(ToolEnforcementResult(allowed = true, reason = "allowed"))
    }

    private data class PolicyStage(
        val label: String,
        val policy: ToolPolicyConfig? = null,
        val profile: ToolProfileId? = null,
    )

    private fun resolvePolicyStages(context: ToolPolicyContext): List<PolicyStage> {
        val globalTools = config.tools
        val agentTools = context.agentId
            ?.let { id -> config.agents?.list?.firstOrNull { agent -> agent.id == id }?.tools }
            ?.normalize()

        val globalProviderPolicy = resolveProviderPolicy(
            byProvider = globalTools?.byProvider,
            modelProvider = context.modelProvider,
            modelId = context.modelId,
        )
        val agentProviderPolicy = resolveProviderPolicy(
            byProvider = agentTools?.byProvider,
            modelProvider = context.modelProvider,
            modelId = context.modelId,
        )

        val profile = agentTools?.profile ?: globalTools?.profile
        val providerProfile = agentProviderPolicy?.profile ?: globalProviderPolicy?.profile
        val profileAlsoAllow = agentTools?.alsoAllow ?: globalTools?.alsoAllow
        val providerProfileAlsoAllow = agentProviderPolicy?.alsoAllow ?: globalProviderPolicy?.alsoAllow

        val profilePolicy = mergeProfileAlsoAllow(
            policy = resolveToolProfilePolicy(profile),
            alsoAllow = profileAlsoAllow,
        )
        val providerProfilePolicy = mergeProfileAlsoAllow(
            policy = resolveToolProfilePolicy(providerProfile),
            alsoAllow = providerProfileAlsoAllow,
        )

        val stages = mutableListOf<PolicyStage>()
        if (profilePolicy != null) {
            stages += PolicyStage(label = "tools.profile", policy = profilePolicy)
        }
        if (providerProfilePolicy != null) {
            stages += PolicyStage(label = "tools.byProvider.profile", policy = providerProfilePolicy)
        }
        stages += PolicyStage(
            label = "tools.allow",
            policy = pickPolicy(
                allow = globalTools?.allow,
                alsoAllow = globalTools?.alsoAllow,
                deny = globalTools?.deny,
            ),
        )
        stages += PolicyStage(
            label = "tools.byProvider.allow",
            policy = globalProviderPolicy
                ?.withoutProfile()
                ?.let { pickPolicy(it.allow, it.alsoAllow, it.deny) },
        )
        stages += PolicyStage(
            label = if (context.agentId != null) "agents.${context.agentId}.tools.allow" else "agent tools.allow",
            policy = agentTools
                ?.withoutProviderOnlyFields()
                ?.let { pickPolicy(it.allow, it.alsoAllow, it.deny) },
        )
        stages += PolicyStage(
            label = if (context.agentId != null) "agents.${context.agentId}.tools.byProvider.allow" else "agent tools.byProvider.allow",
            policy = agentProviderPolicy
                ?.withoutProfile()
                ?.let { pickPolicy(it.allow, it.alsoAllow, it.deny) },
        )
        stages += PolicyStage(
            label = "group tools.allow",
            policy = context.groupPolicy?.asToolPolicy(),
        )
        if (context.sandboxed == true) {
            val sandboxPolicy = context.sandboxPolicy ?: globalTools?.sandbox?.tools
            stages += PolicyStage(
                label = "sandbox tools.allow",
                policy = sandboxPolicy?.asToolPolicy(),
            )
        }
        resolveSubagentPolicy(context)?.let { subagentPolicy ->
            stages += PolicyStage(
                label = "subagent tools.allow",
                policy = subagentPolicy,
            )
        }
        return stages
    }

    private fun evaluateStage(toolName: String, stage: PolicyStage): ToolEnforcementResult? {
        stage.profile?.let { profileId ->
            val profileResult = checkProfile(toolName, profileId)
            if (!profileResult.allowed) {
                return ToolEnforcementResult(
                    allowed = false,
                    reason = "${stage.label}: ${profileResult.reason}",
                )
            }
        }

        val policy = stage.policy ?: return null
        if (matchesPolicyList(toolName, policy.deny)) {
            return ToolEnforcementResult(
                allowed = false,
                reason = "${stage.label}: denied",
            )
        }
        val allowEntries = mergeAllow(policy.allow, policy.alsoAllow)
        if (!allowEntries.isNullOrEmpty() && !matchesAllowList(toolName, allowEntries)) {
            return ToolEnforcementResult(
                allowed = false,
                reason = "${stage.label}: not in allowlist",
            )
        }
        return null
    }

    private fun resolveProviderPolicy(
        byProvider: Map<String, ToolPolicyConfig>?,
        modelProvider: String?,
        modelId: String?,
    ): ToolPolicyConfig? {
        if (byProvider.isNullOrEmpty()) return null
        val normalizedProvider = modelProvider?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val normalizedModelId = modelId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val fullModelId = normalizedModelId?.let {
            if ("/" in it) it else "$normalizedProvider/$it"
        }

        val lookup = byProvider.entries.associateBy(
            keySelector = { it.key.trim().lowercase() },
            valueTransform = { it.value },
        )
        return when {
            fullModelId != null && lookup[fullModelId] != null -> lookup[fullModelId]
            else -> lookup[normalizedProvider]
        }
    }

    private fun resolveSubagentPolicy(context: ToolPolicyContext): ToolPolicyConfig? {
        val isSubagent = context.sessionKey?.let { isSubagentSessionKeyValue(it) } == true
        if (!isSubagent) return null

        val configured = config.tools?.subagents?.tools
        val configuredAllow = configured?.allow.orEmpty()
        val configuredAlsoAllow = configured?.alsoAllow.orEmpty()
        val explicitAllow = (configuredAllow + configuredAlsoAllow)
            .map(::normalizeToolName)
            .toSet()
        val maxSpawnDepth = config.agents?.defaults?.subagents?.maxSpawnDepth
            ?.takeIf { it > 0 }
            ?: DEFAULT_SUBAGENT_MAX_SPAWN_DEPTH
        val depth = context.subagentDepth
            ?.takeIf { it >= 0 }
            ?: subagentDepthFromSessionKey(context.sessionKey)
        val baseDeny = resolveSubagentDenyList(depth, maxSpawnDepth)
            .filterNot { explicitAllow.contains(normalizeToolName(it)) }
        val mergedAllow = mergeAllow(configured?.allow, configured?.alsoAllow)
        val mergedDeny = mergeAllow(baseDeny, configured?.deny)
        return ToolPolicyConfig(
            allow = mergedAllow,
            deny = mergedDeny,
        )
    }

    private fun resolveSubagentDenyList(depth: Int, maxSpawnDepth: Int): List<String> {
        val effectiveDepth = depth.coerceAtLeast(1)
        val effectiveMaxDepth = maxSpawnDepth.coerceAtLeast(1)
        val isLeaf = effectiveDepth >= effectiveMaxDepth
        return if (isLeaf) {
            SUBAGENT_TOOL_DENY_ALWAYS + SUBAGENT_TOOL_DENY_LEAF
        } else {
            SUBAGENT_TOOL_DENY_ALWAYS
        }
    }

    private fun normalizeMessageProvider(messageProvider: String?): String? {
        return messageProvider?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    }

    private fun isSubagentSessionKeyValue(sessionKey: String?): Boolean {
        val raw = sessionKey?.trim().orEmpty()
        if (raw.isEmpty()) return false
        if (raw.lowercase().startsWith("subagent:")) return true
        val lower = raw.lowercase()
        return lower.contains(":subagent:")
    }

    private fun subagentDepthFromSessionKey(sessionKey: String?): Int {
        val raw = sessionKey?.trim()?.lowercase().orEmpty()
        if (raw.isEmpty()) return 0
        return raw.split(":subagent:").size - 1
    }

    private fun isOwnerOnlyTool(toolName: String): Boolean {
        return toolName in OWNER_ONLY_TOOL_NAME_FALLBACKS
    }

    private fun mergeAllow(primary: List<String>?, secondary: List<String>?): List<String>? {
        val merged = mutableListOf<String>()
        primary.orEmpty().forEach { merged += it }
        secondary.orEmpty().forEach { merged += it }
        val deduped = merged
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return deduped.takeIf { it.isNotEmpty() }
    }

    private fun unionAllow(allow: List<String>?, alsoAllow: List<String>?): List<String>? {
        if (alsoAllow.isNullOrEmpty()) return allow?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()
        if (allow.isNullOrEmpty()) {
            return mergeAllow(listOf("*"), alsoAllow)
        }
        return mergeAllow(allow, alsoAllow)
    }

    private fun pickPolicy(
        allow: List<String>?,
        alsoAllow: List<String>?,
        deny: List<String>?,
    ): ToolPolicyConfig? {
        val resolvedAllow = unionAllow(allow, alsoAllow)
        val resolvedDeny = deny
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
        if (resolvedAllow == null && resolvedDeny == null) return null
        return ToolPolicyConfig(
            allow = resolvedAllow,
            deny = resolvedDeny,
        )
    }

    private fun resolveToolProfilePolicy(profile: ToolProfileId?): ToolPolicyConfig? {
        val allow = when (profile) {
            ToolProfileId.MINIMAL -> MINIMAL_TOOLS.toList()
            ToolProfileId.MESSAGING -> BASIC_TOOLS.toList()
            ToolProfileId.CODING -> CODING_TOOLS.toList()
            ToolProfileId.FULL, null -> null
        }
        return allow?.let { ToolPolicyConfig(allow = it) }
    }

    private fun mergeProfileAlsoAllow(policy: ToolPolicyConfig?, alsoAllow: List<String>?): ToolPolicyConfig? {
        if (policy == null) return null
        if (policy.allow.isNullOrEmpty() || alsoAllow.isNullOrEmpty()) return policy
        return policy.copy(allow = mergeAllow(policy.allow, alsoAllow))
    }

    private fun matchesPolicyList(toolName: String, entries: List<String>?): Boolean {
        if (entries.isNullOrEmpty()) return false
        val normalizedTool = normalizeToolName(toolName)
        return entries.any { entry ->
            val normalizedEntry = normalizeToolName(entry)
            ToolGlobMatcher.matches(normalizedEntry, normalizedTool)
        }
    }

    private fun matchesAllowList(toolName: String, entries: List<String>?): Boolean {
        if (entries.isNullOrEmpty()) return false
        val normalizedTool = normalizeToolName(toolName)
        if (matchesPolicyList(normalizedTool, entries)) return true

        // OpenClaw parity: allow-listing `exec` should also permit apply_patch.
        if (normalizedTool == "apply_patch") {
            return entries.any { entry ->
                ToolGlobMatcher.matches(normalizeToolName(entry), "exec")
            }
        }
        return false
    }

    private fun AgentToolsConfig.normalize(): AgentToolsConfig {
        val mergedAllow = mergeAllow(allow, enabled)
        val mergedDeny = mergeAllow(deny, disabled)
        return copy(
            allow = mergedAllow,
            deny = mergedDeny,
        )
    }

    private fun ToolPolicyConfig.withoutProfile(): ToolPolicyConfig {
        return copy(profile = null)
    }

    private fun AgentToolsConfig.withoutProviderOnlyFields(): ToolPolicyConfig {
        return ToolPolicyConfig(
            allow = allow,
            alsoAllow = alsoAllow,
            deny = deny,
            profile = null,
        )
    }

    private fun GroupToolPolicyConfig.asToolPolicy(): ToolPolicyConfig {
        return ToolPolicyConfig(
            allow = allow,
            alsoAllow = alsoAllow,
            deny = deny,
        )
    }

    private fun SandboxToolsPolicy.asToolPolicy(): ToolPolicyConfig {
        return ToolPolicyConfig(
            allow = allow,
            deny = deny,
        )
    }

    companion object {
        private const val DEFAULT_SUBAGENT_MAX_SPAWN_DEPTH = 1
        private val TOOL_DENY_BY_MESSAGE_PROVIDER: Map<String, Set<String>> = mapOf(
            "voice" to setOf("tts"),
        )
        private val OWNER_ONLY_TOOL_NAME_FALLBACKS = setOf("whatsapp_login", "cron", "gateway")
        private val SUBAGENT_TOOL_DENY_ALWAYS = listOf(
            "gateway",
            "agents_list",
            "whatsapp_login",
            "session_status",
            "cron",
            "memory_search",
            "memory_get",
            "sessions_send",
        )
        private val SUBAGENT_TOOL_DENY_LEAF = listOf(
            "sessions_list",
            "sessions_history",
            "sessions_spawn",
        )

        // Tool categories for profile-based filtering
        val MINIMAL_TOOLS = setOf(
            "read", "find", "grep", "ls", "web_search", "web_fetch",
        )

        val BASIC_TOOLS = MINIMAL_TOOLS + setOf(
            "write", "edit", "message",
        )

        val CODING_TOOLS = BASIC_TOOLS + setOf(
            "exec", "process", "apply_patch", "sessions_spawn", "subagents",
        )

        val FULL_TOOLS: Set<String>? = null // null means all tools allowed

        // Restricted tools not allowed in BASIC or MINIMAL profiles
        val RESTRICTED_TOOLS = setOf(
            "browser", "screen_capture",
        )

        fun checkProfile(toolName: String, profile: ToolProfileId): ToolEnforcementResult {
            val normalizedTool = normalizeToolName(toolName)
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
            if (profile != ToolProfileId.FULL && normalizedTool in RESTRICTED_TOOLS) {
                return ToolEnforcementResult(
                    allowed = false,
                    reason = "tool $normalizedTool restricted in $profile profile",
                )
            }

            // Check if tool is in allowed set
            return if (normalizedTool in allowedSet) {
                ToolEnforcementResult(allowed = true, reason = "$profile profile")
            } else {
                ToolEnforcementResult(
                    allowed = false,
                    reason = "tool $normalizedTool not in $profile profile",
                )
            }
        }

        fun normalizeToolName(name: String): String {
            return when (name.trim().lowercase()) {
                "read_file", "cat", "read-file" -> "read"
                "write_file", "create_file", "write-file" -> "write"
                "edit_file", "edit-file" -> "edit"
                "execute_command", "run_command", "bash", "shell" -> "exec"
                "apply-patch" -> "apply_patch"
                "list_files" -> "find"
                "search_files" -> "grep"
                else -> name.trim().lowercase()
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
