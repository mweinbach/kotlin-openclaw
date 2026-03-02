package ai.openclaw.runtime.cron

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The schedule type for a cron job.
 * Ported from src/cron/types.ts CronSchedule.
 */
@Serializable
sealed class CronSchedule {
    /** One-shot: run at a specific time (ISO 8601 or epoch ms). */
    @Serializable
    @SerialName("at")
    data class At(val at: String) : CronSchedule()

    /** Repeating: run every N milliseconds. */
    @Serializable
    @SerialName("every")
    data class Every(val everyMs: Long, val anchorMs: Long? = null) : CronSchedule()

    /** Standard cron expression. */
    @Serializable
    @SerialName("cron")
    data class Cron(val expr: String, val tz: String? = null) : CronSchedule()
}

@Serializable
enum class CronSessionTarget {
    @SerialName("main") MAIN,
    @SerialName("isolated") ISOLATED,
}

@Serializable
enum class CronRunStatus {
    @SerialName("ok") OK,
    @SerialName("error") ERROR,
    @SerialName("skipped") SKIPPED,
}

@Serializable
data class CronJobState(
    val nextRunAtMs: Long? = null,
    val lastRunAtMs: Long? = null,
    val lastRunStatus: CronRunStatus? = null,
    val lastError: String? = null,
    val lastDurationMs: Long? = null,
    val consecutiveErrors: Int = 0,
)

@Serializable
sealed class CronPayload {
    @Serializable
    @SerialName("systemEvent")
    data class SystemEvent(val text: String) : CronPayload()

    @Serializable
    @SerialName("agentTurn")
    data class AgentTurn(
        val message: String,
        val model: String? = null,
        val timeoutSeconds: Int? = null,
    ) : CronPayload()
}

/**
 * A cron job definition, persisted to JSON.
 * Ported from src/cron/types.ts CronJob.
 */
@Serializable
data class CronJob(
    val id: String,
    val agentId: String? = null,
    val sessionKey: String? = null,
    val name: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val deleteAfterRun: Boolean = false,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val schedule: CronSchedule,
    val sessionTarget: CronSessionTarget = CronSessionTarget.MAIN,
    val payload: CronPayload,
    val state: CronJobState = CronJobState(),
)

@Serializable
data class CronStoreFile(
    val version: Int = 1,
    val jobs: List<CronJob> = emptyList(),
)
