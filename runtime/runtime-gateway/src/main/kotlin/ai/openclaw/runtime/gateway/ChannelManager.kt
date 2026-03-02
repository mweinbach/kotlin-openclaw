package ai.openclaw.runtime.gateway

import ai.openclaw.core.model.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages channel plugin lifecycles, restart policies, and message routing.
 * Ported from src/gateway/server-channels.ts
 */
class ChannelManager(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /**
     * Interface for channel adapters to implement for gateway integration.
     */
    interface ChannelAdapter {
        val channelId: ChannelId
        val displayName: String
        suspend fun start(handler: suspend (InboundMessage) -> Unit)
        suspend fun stop()
        suspend fun send(message: OutboundMessage): Boolean
    }

    private data class ChannelAccountState(
        val adapter: ChannelAdapter,
        val accountId: String,
        var status: ChannelStatus = ChannelStatus.STOPPED,
        var error: String? = null,
        var startedAt: Long? = null,
        var restartAttempts: Int = 0,
        var manuallyStopped: Boolean = false,
        var restartJob: Job? = null,
    )

    private val channels = ConcurrentHashMap<String, ChannelAccountState>()
    private var inboundHandler: (suspend (InboundMessage) -> Unit)? = null

    enum class ChannelStatus { STOPPED, STARTING, RUNNING, ERROR, RESTARTING }

    // --- Registration ---

    fun registerChannel(adapter: ChannelAdapter, accountId: String = "default") {
        val key = channelKey(adapter.channelId, accountId)
        channels[key] = ChannelAccountState(adapter = adapter, accountId = accountId)
    }

    fun setInboundHandler(handler: suspend (InboundMessage) -> Unit) {
        inboundHandler = handler
    }

    // --- Lifecycle ---

    suspend fun startAll() {
        channels.forEach { (key, state) ->
            if (!state.manuallyStopped) {
                startChannel(state)
            }
        }
    }

    suspend fun stopAll() {
        channels.forEach { (_, state) ->
            stopChannel(state)
        }
    }

    suspend fun startChannel(channelId: ChannelId, accountId: String = "default") {
        val key = channelKey(channelId, accountId)
        val state = channels[key] ?: throw IllegalArgumentException("Unknown channel: $key")
        state.manuallyStopped = false
        startChannel(state)
    }

    suspend fun stopChannel(channelId: ChannelId, accountId: String = "default") {
        val key = channelKey(channelId, accountId)
        val state = channels[key] ?: return
        state.manuallyStopped = true
        stopChannel(state)
    }

    private suspend fun startChannel(state: ChannelAccountState) {
        if (state.status == ChannelStatus.RUNNING) return
        state.status = ChannelStatus.STARTING
        state.error = null

        try {
            state.adapter.start { message ->
                inboundHandler?.invoke(message)
            }
            state.status = ChannelStatus.RUNNING
            state.startedAt = System.currentTimeMillis()
            state.restartAttempts = 0
        } catch (e: Exception) {
            state.status = ChannelStatus.ERROR
            state.error = e.message
            scheduleRestart(state)
        }
    }

    private suspend fun stopChannel(state: ChannelAccountState) {
        state.restartJob?.cancel()
        state.restartJob = null
        try {
            state.adapter.stop()
        } catch (_: Exception) {
            // Ignore stop errors
        }
        state.status = ChannelStatus.STOPPED
        state.startedAt = null
    }

    // --- Restart Policy ---

    private fun scheduleRestart(state: ChannelAccountState) {
        if (state.manuallyStopped) return
        if (state.restartAttempts >= MAX_RESTART_ATTEMPTS) return

        state.restartAttempts++
        val backoffMs = computeBackoff(state.restartAttempts)
        state.status = ChannelStatus.RESTARTING

        state.restartJob = scope.launch {
            delay(backoffMs)
            try {
                startChannel(state)
            } catch (_: CancellationException) {
                // Cancelled
            }
        }
    }

    private fun computeBackoff(attempt: Int): Long {
        val base = INITIAL_BACKOFF_MS * Math.pow(BACKOFF_FACTOR, (attempt - 1).toDouble())
        val jitter = base * JITTER_FACTOR * (Math.random() * 2 - 1)
        return (base + jitter).toLong().coerceAtMost(MAX_BACKOFF_MS)
    }

    // --- Outbound ---

    suspend fun send(channelId: ChannelId, accountId: String = "default", message: OutboundMessage): Boolean {
        val key = channelKey(channelId, accountId)
        val state = channels[key] ?: return false
        if (state.status != ChannelStatus.RUNNING) return false
        return state.adapter.send(message)
    }

    // --- Status ---

    fun getSnapshot(): Map<String, ChannelSnapshot> {
        return channels.map { (key, state) ->
            key to ChannelSnapshot(
                channelId = state.adapter.channelId,
                accountId = state.accountId,
                displayName = state.adapter.displayName,
                status = state.status.name.lowercase(),
                error = state.error,
                startedAt = state.startedAt,
                restartAttempts = state.restartAttempts,
            )
        }.toMap()
    }

    data class ChannelSnapshot(
        val channelId: String,
        val accountId: String,
        val displayName: String,
        val status: String,
        val error: String?,
        val startedAt: Long?,
        val restartAttempts: Int,
    )

    private fun channelKey(channelId: String, accountId: String): String = "$channelId:$accountId"

    companion object {
        private const val MAX_RESTART_ATTEMPTS = 10
        private const val INITIAL_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 5 * 60_000L
        private const val BACKOFF_FACTOR = 2.0
        private const val JITTER_FACTOR = 0.1
    }
}
