package ai.openclaw.core.storage.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "plugin_states",
    indices = [Index("enabled")],
)
data class PluginStateEntity(
    @PrimaryKey
    val pluginId: String,
    val name: String? = null,
    val enabled: Boolean = true,
    val version: String? = null,
    val configJson: String? = null,
    val stateJson: String? = null,
    val installedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
