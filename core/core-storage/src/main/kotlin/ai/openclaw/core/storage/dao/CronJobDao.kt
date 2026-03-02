package ai.openclaw.core.storage.dao

import ai.openclaw.core.storage.entity.CronJobEntity
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CronJobDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: CronJobEntity)

    @Update
    suspend fun update(job: CronJobEntity)

    @Delete
    suspend fun delete(job: CronJobEntity)

    @Query("DELETE FROM cron_jobs WHERE jobId = :jobId")
    suspend fun deleteById(jobId: String)

    @Query("SELECT * FROM cron_jobs WHERE jobId = :jobId")
    suspend fun getById(jobId: String): CronJobEntity?

    @Query("SELECT * FROM cron_jobs WHERE enabled = 1 ORDER BY nextRunAt ASC")
    suspend fun getEnabled(): List<CronJobEntity>

    @Query("SELECT * FROM cron_jobs ORDER BY updatedAt DESC")
    suspend fun getAll(): List<CronJobEntity>

    @Query("SELECT * FROM cron_jobs WHERE enabled = 1 AND nextRunAt <= :timestamp ORDER BY nextRunAt ASC")
    suspend fun getDue(timestamp: Long): List<CronJobEntity>

    @Query("SELECT * FROM cron_jobs WHERE agentId = :agentId")
    suspend fun getByAgentId(agentId: String): List<CronJobEntity>

    @Query("SELECT * FROM cron_jobs ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<CronJobEntity>>

    @Query("SELECT COUNT(*) FROM cron_jobs")
    suspend fun count(): Int
}
