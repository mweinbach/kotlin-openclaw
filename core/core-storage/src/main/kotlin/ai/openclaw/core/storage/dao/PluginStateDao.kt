package ai.openclaw.core.storage.dao

import ai.openclaw.core.storage.entity.PluginStateEntity
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PluginStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: PluginStateEntity)

    @Update
    suspend fun update(state: PluginStateEntity)

    @Delete
    suspend fun delete(state: PluginStateEntity)

    @Query("DELETE FROM plugin_states WHERE pluginId = :pluginId")
    suspend fun deleteByPluginId(pluginId: String)

    @Query("SELECT * FROM plugin_states WHERE pluginId = :pluginId")
    suspend fun getByPluginId(pluginId: String): PluginStateEntity?

    @Query("SELECT * FROM plugin_states WHERE enabled = 1")
    suspend fun getEnabled(): List<PluginStateEntity>

    @Query("SELECT * FROM plugin_states ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<PluginStateEntity>>

    @Query("SELECT COUNT(*) FROM plugin_states")
    suspend fun count(): Int
}
