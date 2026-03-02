package ai.openclaw.core.storage.dao

import ai.openclaw.core.storage.entity.ChannelStateEntity
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ChannelStateEntity)

    @Update
    suspend fun update(state: ChannelStateEntity)

    @Delete
    suspend fun delete(state: ChannelStateEntity)

    @Query("SELECT * FROM channel_states WHERE channelType = :channelType AND accountId = :accountId")
    suspend fun getByChannel(channelType: String, accountId: String): ChannelStateEntity?

    @Query("SELECT * FROM channel_states WHERE channelType = :channelType")
    suspend fun getByChannelType(channelType: String): List<ChannelStateEntity>

    @Query("DELETE FROM channel_states WHERE channelType = :channelType AND accountId = :accountId")
    suspend fun deleteByChannel(channelType: String, accountId: String)

    @Query("SELECT * FROM channel_states WHERE connected = 1")
    suspend fun getConnected(): List<ChannelStateEntity>

    @Query("SELECT * FROM channel_states ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ChannelStateEntity>>

    @Query("SELECT COUNT(*) FROM channel_states")
    suspend fun count(): Int
}
