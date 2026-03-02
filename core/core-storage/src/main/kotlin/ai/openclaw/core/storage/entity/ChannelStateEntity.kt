package ai.openclaw.core.storage.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "channel_states",
    primaryKeys = ["channelType", "accountId"],
    indices = [Index("updatedAt")],
)
data class ChannelStateEntity(
    val channelType: String,
    val accountId: String,
    val connected: Boolean = false,
    val lastHeartbeatAt: Long? = null,
    val lastErrorMessage: String? = null,
    val stateJson: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)
