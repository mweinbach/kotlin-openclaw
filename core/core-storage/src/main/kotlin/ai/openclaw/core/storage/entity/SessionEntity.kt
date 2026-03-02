package ai.openclaw.core.storage.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sessions",
    indices = [
        Index("agentId"),
        Index("channelType"),
        Index("senderKey"),
        Index("lastActiveAt"),
    ],
)
data class SessionEntity(
    @PrimaryKey
    val sessionId: String,
    val sessionKey: String,
    val agentId: String,
    val channelType: String? = null,
    val channelAccountId: String? = null,
    val senderKey: String? = null,
    val chatType: String? = null,
    val queueMode: String? = null,
    val model: String? = null,
    val modelProvider: String? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val totalTokens: Int = 0,
    val compactionCount: Int = 0,
    val label: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastActiveAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val metadataJson: String? = null,
)
