package ai.openclaw.core.storage.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cron_jobs",
    indices = [Index("enabled"), Index("nextRunAt")],
)
data class CronJobEntity(
    @PrimaryKey
    val jobId: String,
    val name: String,
    val scheduleJson: String,
    val payloadJson: String? = null,
    val cronExpression: String? = null,
    val agentId: String? = null,
    val sessionKey: String? = null,
    val messageTemplate: String? = null,
    val enabled: Boolean = true,
    val lastRunAt: Long? = null,
    val nextRunAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
