package ai.openclaw.core.storage.repository

import ai.openclaw.core.storage.dao.SessionDao
import ai.openclaw.core.storage.entity.SessionEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Repository for session persistence operations.
 * Wraps SessionDao with coroutine dispatcher support.
 */
class SessionRepository(
    private val sessionDao: SessionDao,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun save(session: SessionEntity) = withContext(dispatcher) {
        sessionDao.insert(session)
    }

    suspend fun update(session: SessionEntity) = withContext(dispatcher) {
        sessionDao.update(session)
    }

    suspend fun delete(sessionId: String) = withContext(dispatcher) {
        sessionDao.deleteById(sessionId)
    }

    suspend fun getById(sessionId: String): SessionEntity? = withContext(dispatcher) {
        sessionDao.getById(sessionId)
    }

    suspend fun getBySessionKey(sessionKey: String): List<SessionEntity> = withContext(dispatcher) {
        sessionDao.getBySessionKey(sessionKey)
    }

    suspend fun getByAgentAndSender(agentId: String, senderKey: String): List<SessionEntity> = withContext(dispatcher) {
        sessionDao.getByAgentAndSender(agentId, senderKey)
    }

    suspend fun getByAgentId(agentId: String): List<SessionEntity> = withContext(dispatcher) {
        sessionDao.getByAgentId(agentId)
    }

    suspend fun getByChannel(channelType: String): List<SessionEntity> = withContext(dispatcher) {
        sessionDao.getByChannel(channelType)
    }

    suspend fun getActive(since: Long): List<SessionEntity> = withContext(dispatcher) {
        sessionDao.getActive(since)
    }

    fun observeAll(): Flow<List<SessionEntity>> {
        return sessionDao.observeAll()
    }

    suspend fun getRecent(limit: Int = 50): List<SessionEntity> = withContext(dispatcher) {
        sessionDao.getRecent(limit)
    }

    suspend fun countByAgent(agentId: String): Int = withContext(dispatcher) {
        sessionDao.countByAgent(agentId)
    }

    suspend fun pruneOlderThan(beforeTimestamp: Long): Int = withContext(dispatcher) {
        sessionDao.deleteOlderThan(beforeTimestamp)
    }

    suspend fun count(): Int = withContext(dispatcher) {
        sessionDao.count()
    }
}
