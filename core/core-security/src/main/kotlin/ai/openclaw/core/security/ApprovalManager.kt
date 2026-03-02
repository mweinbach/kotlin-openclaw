package ai.openclaw.core.security

import ai.openclaw.core.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * A pending approval request.
 */
data class ApprovalRequest(
    val id: String,
    val toolName: String,
    val toolInput: String,
    val agentId: String,
    val sessionKey: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
)

/**
 * Result of an approval decision.
 */
enum class ApprovalDecision {
    APPROVED,
    DENIED,
    TIMED_OUT,
}

/**
 * Events emitted by the approval system.
 */
sealed class ApprovalEvent {
    data class ApprovalRequired(
        val request: ApprovalRequest,
    ) : ApprovalEvent()

    data class ApprovalResolved(
        val requestId: String,
        val decision: ApprovalDecision,
    ) : ApprovalEvent()
}

/**
 * Policy that determines which tool calls require approval.
 */
interface ApprovalPolicy {
    /**
     * Check if a tool call requires approval.
     * Returns true if approval is needed.
     */
    fun requiresApproval(
        toolName: String,
        agentId: String,
        sessionKey: String,
    ): Boolean
}

/**
 * Default approval policy based on ApprovalsConfig.
 * Currently checks if exec approval forwarding is enabled.
 */
class ConfigBasedApprovalPolicy(
    private val config: ApprovalsConfig?,
    private val toolsRequiringApproval: Set<String> = DEFAULT_TOOLS_REQUIRING_APPROVAL,
) : ApprovalPolicy {
    override fun requiresApproval(toolName: String, agentId: String, sessionKey: String): Boolean {
        // If no config or exec forwarding is not enabled, no approvals needed
        if (config?.exec?.enabled != true) return false

        // Check agent filter
        val agentFilter = config.exec?.agentFilter
        if (agentFilter != null && agentId !in agentFilter) return false

        // Check session filter
        val sessionFilter = config.exec?.sessionFilter
        if (sessionFilter != null && !sessionFilter.any { sessionKey.startsWith(it) }) return false

        // Check if the tool is in the set that requires approval
        return toolName in toolsRequiringApproval
    }

    companion object {
        val DEFAULT_TOOLS_REQUIRING_APPROVAL = setOf("exec", "bash", "shell", "run_command")
    }
}

/**
 * Manages approval workflows for tool calls.
 * Intercepts tool calls before execution, emits approval events,
 * and waits for approve/deny decisions.
 *
 * Ported from src/agents/bash-tools.exec-approval-request.ts.
 */
class ApprovalManager(
    private val policy: ApprovalPolicy = ConfigBasedApprovalPolicy(null),
    private val defaultTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val pendingApprovals = mutableMapOf<String, CompletableDeferred<ApprovalDecision>>()
    private val pendingRequests = mutableMapOf<String, ApprovalRequest>()
    private val _events = MutableSharedFlow<ApprovalEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ApprovalEvent> = _events.asSharedFlow()

    /**
     * Check if a tool call requires approval and, if so, wait for the decision.
     * Returns the approval decision.
     *
     * This is called from the AgentRunner tool execution loop, before tool.execute().
     */
    suspend fun checkApproval(
        toolName: String,
        toolInput: String,
        agentId: String,
        sessionKey: String,
    ): ApprovalDecision {
        if (!policy.requiresApproval(toolName, agentId, sessionKey)) {
            return ApprovalDecision.APPROVED
        }

        val now = clock()
        val request = ApprovalRequest(
            id = UUID.randomUUID().toString(),
            toolName = toolName,
            toolInput = toolInput,
            agentId = agentId,
            sessionKey = sessionKey,
            createdAtMs = now,
            expiresAtMs = now + defaultTimeoutMs,
        )

        val deferred = CompletableDeferred<ApprovalDecision>()

        mutex.withLock {
            pendingApprovals[request.id] = deferred
            pendingRequests[request.id] = request
        }

        _events.tryEmit(ApprovalEvent.ApprovalRequired(request))

        // Wait for decision with timeout
        val decision = withTimeoutOrNull(defaultTimeoutMs) {
            deferred.await()
        } ?: ApprovalDecision.TIMED_OUT

        mutex.withLock {
            pendingApprovals.remove(request.id)
            pendingRequests.remove(request.id)
        }

        _events.tryEmit(ApprovalEvent.ApprovalResolved(request.id, decision))
        return decision
    }

    /**
     * Approve a pending approval request.
     */
    suspend fun approve(requestId: String): Boolean = mutex.withLock {
        pendingApprovals[requestId]?.complete(ApprovalDecision.APPROVED) != null
    }

    /**
     * Deny a pending approval request.
     */
    suspend fun deny(requestId: String): Boolean = mutex.withLock {
        pendingApprovals[requestId]?.complete(ApprovalDecision.DENIED) != null
    }

    /**
     * Get all pending approval requests.
     */
    suspend fun pendingRequests(): List<ApprovalRequest> = mutex.withLock {
        pendingRequests.values.filter { it.expiresAtMs > clock() }
    }

    /**
     * Get a specific pending request.
     */
    suspend fun getRequest(requestId: String): ApprovalRequest? = mutex.withLock {
        pendingRequests[requestId]
    }

    /**
     * Expire all timed-out pending approvals.
     */
    suspend fun expireTimedOut() {
        val now = clock()
        mutex.withLock {
            val expired = pendingRequests.filter { it.value.expiresAtMs <= now }
            for ((id, _) in expired) {
                pendingApprovals[id]?.complete(ApprovalDecision.TIMED_OUT)
                pendingApprovals.remove(id)
                pendingRequests.remove(id)
            }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 120_000L // 2 minutes
    }
}
