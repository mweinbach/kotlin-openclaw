package ai.openclaw.core.storage.dao

import ai.openclaw.core.storage.entity.SessionEntity
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity)

    @Update
    suspend fun update(session: SessionEntity)

    @Delete
    suspend fun delete(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE sessionId = :sessionId")
    suspend fun deleteById(sessionId: String)

    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId")
    suspend fun getById(sessionId: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE sessionKey = :sessionKey")
    suspend fun getBySessionKey(sessionKey: String): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE agentId = :agentId AND senderKey = :senderKey ORDER BY lastActiveAt DESC")
    suspend fun getByAgentAndSender(agentId: String, senderKey: String): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE agentId = :agentId ORDER BY lastActiveAt DESC")
    suspend fun getByAgentId(agentId: String): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE channelType = :channelType ORDER BY lastActiveAt DESC")
    suspend fun getByChannel(channelType: String): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE lastActiveAt > :since ORDER BY lastActiveAt DESC")
    suspend fun getActive(since: Long): List<SessionEntity>

    @Query("SELECT * FROM sessions ORDER BY lastActiveAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY lastActiveAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<SessionEntity>

    @Query("SELECT COUNT(*) FROM sessions WHERE agentId = :agentId")
    suspend fun countByAgent(agentId: String): Int

    @Query("DELETE FROM sessions WHERE lastActiveAt < :beforeTimestamp")
    suspend fun deleteOlderThan(beforeTimestamp: Long): Int

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun count(): Int
}
