package ai.openclaw.core.acp

import ai.openclaw.core.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory ACP session store with max sessions + idle TTL eviction.
 */
class InMemoryAcpSessionStore(
    private val maxSessions: Int = 5000,
    private val idleTtlMs: Long = 24 * 60 * 60 * 1000L, // 24 hours
) {
    private data class Entry(
        val handle: AcpRuntimeHandle,
        var lastActivityAt: Long = System.currentTimeMillis(),
    )

    private val sessions = ConcurrentHashMap<String, Entry>()

    fun get(sessionKey: String): AcpRuntimeHandle? {
        val entry = sessions[sessionKey] ?: return null
        entry.lastActivityAt = System.currentTimeMillis()
        return entry.handle
    }

    fun set(sessionKey: String, handle: AcpRuntimeHandle) {
        reapIdleSessions()
        if (sessions.size >= maxSessions) {
            evictOldest()
        }
        sessions[sessionKey] = Entry(handle)
    }

    fun remove(sessionKey: String) {
        sessions.remove(sessionKey)
    }

    fun size(): Int = sessions.size

    private fun reapIdleSessions() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { (_, entry) ->
            now - entry.lastActivityAt > idleTtlMs
        }
    }

    private fun evictOldest() {
        val oldest = sessions.entries.minByOrNull { it.value.lastActivityAt }
        if (oldest != null) {
            sessions.remove(oldest.key)
        }
    }
}

/**
 * LRU runtime cache with idle eviction.
 */
class RuntimeCache<T>(private val maxSize: Int = 100) {
    private data class CacheEntry<T>(val value: T, var lastAccessAt: Long = System.currentTimeMillis())

    private val cache = LinkedHashMap<String, CacheEntry<T>>(maxSize, 0.75f, true)

    @Synchronized
    fun get(key: String, touch: Boolean = true): T? {
        val entry = cache[key] ?: return null
        if (touch) entry.lastAccessAt = System.currentTimeMillis()
        return entry.value
    }

    @Synchronized
    fun set(key: String, value: T) {
        if (cache.size >= maxSize) {
            val oldest = cache.entries.firstOrNull()
            if (oldest != null) cache.remove(oldest.key)
        }
        cache[key] = CacheEntry(value)
    }

    @Synchronized
    fun remove(key: String) {
        cache.remove(key)
    }

    @Synchronized
    fun size(): Int = cache.size

    @Synchronized
    fun collectIdleCandidates(maxIdleMs: Long): List<String> {
        val now = System.currentTimeMillis()
        return cache.entries
            .filter { now - it.value.lastAccessAt > maxIdleMs }
            .map { it.key }
    }
}

/**
 * Per-session serialization queue.
 */
class SessionActorQueue {
    private val mutexMap = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withSession(sessionKey: String, block: suspend () -> T): T {
        val mutex = mutexMap.getOrPut(sessionKey) { Mutex() }
        return mutex.withLock { block() }
    }
}

/**
 * ACP Session Manager - the main turn execution orchestrator.
 * Ported from src/acp/control-plane/manager.core.ts
 */
class AcpSessionManager(
    private val sessionStore: InMemoryAcpSessionStore = InMemoryAcpSessionStore(),
    private val runtimeCache: RuntimeCache<AcpRuntime> = RuntimeCache(),
    private val actorQueue: SessionActorQueue = SessionActorQueue(),
) {
    /**
     * Run a turn for a session, returning a flow of events.
     */
    fun runTurn(
        runtime: AcpRuntime,
        sessionKey: String,
        text: String,
        mode: AcpRuntimePromptMode = AcpRuntimePromptMode.PROMPT,
        requestId: String = java.util.UUID.randomUUID().toString(),
        context: AcpRuntimeTurnContext? = null,
    ): Flow<AcpRuntimeEvent> = flow {
        val handle = sessionStore.get(sessionKey)
            ?: throw IllegalStateException("No session found for key: $sessionKey")

        val input = AcpRuntimeTurnInput(
            handle = handle,
            text = text,
            mode = mode,
            requestId = requestId,
            context = context,
        )

        runtime.runTurn(input).collect { event ->
            emit(event)
        }
    }

    /**
     * Ensure a session exists, creating one if needed.
     */
    suspend fun ensureSession(
        runtime: AcpRuntime,
        sessionKey: String,
        agent: String,
        mode: AcpRuntimeSessionMode = AcpRuntimeSessionMode.PERSISTENT,
    ): AcpRuntimeHandle {
        val existing = sessionStore.get(sessionKey)
        if (existing != null) return existing

        val handle = runtime.ensureSession(
            AcpRuntimeEnsureInput(
                sessionKey = sessionKey,
                agent = agent,
                mode = mode,
            )
        )
        sessionStore.set(sessionKey, handle)
        return handle
    }
}
