package ai.openclaw.core.storage.repository

import ai.openclaw.core.storage.dao.MemoryDao
import ai.openclaw.core.storage.entity.MemoryEntryEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for memory entry persistence operations.
 * Wraps MemoryDao with coroutine dispatcher support.
 */
class MemoryRepository(
    private val memoryDao: MemoryDao,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun save(entry: MemoryEntryEntity) = withContext(dispatcher) {
        memoryDao.insert(entry)
    }

    suspend fun saveAll(entries: List<MemoryEntryEntity>) = withContext(dispatcher) {
        memoryDao.insertAll(entries)
    }

    suspend fun update(entry: MemoryEntryEntity) = withContext(dispatcher) {
        memoryDao.update(entry)
    }

    suspend fun delete(id: String) = withContext(dispatcher) {
        memoryDao.deleteById(id)
    }

    suspend fun getById(id: String): MemoryEntryEntity? = withContext(dispatcher) {
        memoryDao.getById(id)
    }

    suspend fun getBySource(source: String): List<MemoryEntryEntity> = withContext(dispatcher) {
        memoryDao.getBySource(source)
    }

    suspend fun getAll(): List<MemoryEntryEntity> = withContext(dispatcher) {
        memoryDao.getAll()
    }

    suspend fun getRecent(limit: Int = 100): List<MemoryEntryEntity> = withContext(dispatcher) {
        memoryDao.getRecent(limit)
    }

    suspend fun pruneOlderThan(beforeTimestamp: Long): Int = withContext(dispatcher) {
        memoryDao.deleteOlderThan(beforeTimestamp)
    }

    suspend fun count(): Int = withContext(dispatcher) {
        memoryDao.count()
    }
}
