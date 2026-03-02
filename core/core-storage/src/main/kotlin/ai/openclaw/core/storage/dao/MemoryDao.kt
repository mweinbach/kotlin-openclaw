package ai.openclaw.core.storage.dao

import ai.openclaw.core.storage.entity.MemoryEntryEntity
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface MemoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: MemoryEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<MemoryEntryEntity>)

    @Update
    suspend fun update(entry: MemoryEntryEntity)

    @Delete
    suspend fun delete(entry: MemoryEntryEntity)

    @Query("DELETE FROM memory_entries WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM memory_entries WHERE id = :id")
    suspend fun getById(id: String): MemoryEntryEntity?

    @Query("SELECT * FROM memory_entries WHERE source = :source ORDER BY createdAt DESC")
    suspend fun getBySource(source: String): List<MemoryEntryEntity>

    @Query("SELECT * FROM memory_entries ORDER BY createdAt DESC")
    suspend fun getAll(): List<MemoryEntryEntity>

    @Query("SELECT * FROM memory_entries ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<MemoryEntryEntity>

    @Query("DELETE FROM memory_entries WHERE createdAt < :beforeTimestamp")
    suspend fun deleteOlderThan(beforeTimestamp: Long): Int

    @Query("SELECT COUNT(*) FROM memory_entries")
    suspend fun count(): Int
}
