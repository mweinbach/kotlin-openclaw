package ai.openclaw.core.storage.repository

import ai.openclaw.core.storage.dao.MessageDao
import ai.openclaw.core.storage.entity.MessageEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Repository for message persistence operations.
 * Wraps MessageDao with coroutine dispatcher support.
 */
class MessageRepository(
    private val messageDao: MessageDao,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun save(message: MessageEntity) = withContext(dispatcher) {
        messageDao.insert(message)
    }

    suspend fun saveAll(messages: List<MessageEntity>) = withContext(dispatcher) {
        messageDao.insertAll(messages)
    }

    suspend fun delete(messageId: String) = withContext(dispatcher) {
        messageDao.deleteById(messageId)
    }

    suspend fun getById(messageId: String): MessageEntity? = withContext(dispatcher) {
        messageDao.getById(messageId)
    }

    suspend fun getBySessionId(sessionId: String): List<MessageEntity> = withContext(dispatcher) {
        messageDao.getBySessionId(sessionId)
    }

    fun observeBySessionId(sessionId: String): Flow<List<MessageEntity>> {
        return messageDao.observeBySessionId(sessionId)
    }

    suspend fun getRecentBySession(sessionId: String, limit: Int = 50): List<MessageEntity> = withContext(dispatcher) {
        messageDao.getRecentBySession(sessionId, limit)
    }

    suspend fun deleteBySessionId(sessionId: String): Int = withContext(dispatcher) {
        messageDao.deleteBySessionId(sessionId)
    }

    suspend fun countBySession(sessionId: String): Int = withContext(dispatcher) {
        messageDao.countBySession(sessionId)
    }

    suspend fun count(): Int = withContext(dispatcher) {
        messageDao.count()
    }
}
