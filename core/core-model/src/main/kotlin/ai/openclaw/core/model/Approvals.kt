package ai.openclaw.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Approvals Config (ported from src/config/types.approvals.ts) ---

@Serializable
enum class ExecApprovalForwardingMode {
    @SerialName("session") SESSION,
    @SerialName("targets") TARGETS,
    @SerialName("both") BOTH,
}

@Serializable
data class ExecApprovalForwardTarget(
    val channel: String,
    val to: String,
    val accountId: String? = null,
    val threadId: String? = null,
)

@Serializable
data class ExecApprovalForwardingConfig(
    val enabled: Boolean? = null,
    val mode: ExecApprovalForwardingMode? = null,
    val agentFilter: List<String>? = null,
    val sessionFilter: List<String>? = null,
    val targets: List<ExecApprovalForwardTarget>? = null,
)

@Serializable
data class ApprovalsConfig(
    val exec: ExecApprovalForwardingConfig? = null,
)
