package ai.openclaw.runtime.cron

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.TimeZone
import java.util.UUID

/**
 * Callback interface for when a cron job fires.
 */
fun interface CronJobExecutor {
    suspend fun execute(job: CronJob)
}

/**
 * Events emitted by the cron scheduler.
 */
sealed class CronEvent {
    data class JobFired(val jobId: String, val jobName: String) : CronEvent()
    data class JobCompleted(val jobId: String, val durationMs: Long, val status: CronRunStatus) : CronEvent()
    data class JobError(val jobId: String, val error: String) : CronEvent()
    data class JobCreated(val jobId: String, val jobName: String) : CronEvent()
    data class JobDeleted(val jobId: String) : CronEvent()
}

/**
 * Coroutine-based cron scheduler. Uses delay-based scheduling for Android compatibility
 * (no AlarmManager or WorkManager dependency).
 *
 * Ported from src/cron/service.ts CronService.
 */
class CronScheduler(
    private val store: CronStore,
    private val executor: CronJobExecutor,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var jobs = mutableListOf<CronJob>()
    private val timers = mutableMapOf<String, Job>()
    private val _events = MutableSharedFlow<CronEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<CronEvent> = _events.asSharedFlow()

    private var started = false

    /**
     * Start the scheduler: load persisted jobs and arm timers.
     */
    suspend fun start() = mutex.withLock {
        if (started) return
        val storeFile = store.load()
        jobs = storeFile.jobs.toMutableList()
        for (job in jobs) {
            if (job.enabled) {
                armTimer(job)
            }
        }
        started = true
    }

    /**
     * Stop the scheduler: cancel all timers.
     */
    fun stop() {
        scope.coroutineContext.cancelChildren()
        timers.clear()
        started = false
    }

    /**
     * List all jobs, optionally including disabled ones.
     */
    suspend fun list(includeDisabled: Boolean = false): List<CronJob> = mutex.withLock {
        if (includeDisabled) jobs.toList()
        else jobs.filter { it.enabled }
    }

    /**
     * Get a job by ID.
     */
    suspend fun getJob(id: String): CronJob? = mutex.withLock {
        jobs.firstOrNull { it.id == id }
    }

    /**
     * Add a new cron job and persist it.
     */
    suspend fun add(
        name: String,
        schedule: CronSchedule,
        payload: CronPayload,
        agentId: String? = null,
        sessionKey: String? = null,
        description: String? = null,
        enabled: Boolean = true,
        deleteAfterRun: Boolean = false,
        sessionTarget: CronSessionTarget = CronSessionTarget.MAIN,
    ): CronJob = mutex.withLock {
        val now = clock()
        val nextRunAtMs = computeNextRunAtMs(schedule, now)
        val job = CronJob(
            id = UUID.randomUUID().toString(),
            agentId = agentId,
            sessionKey = sessionKey,
            name = name,
            description = description,
            enabled = enabled,
            deleteAfterRun = deleteAfterRun,
            createdAtMs = now,
            updatedAtMs = now,
            schedule = schedule,
            sessionTarget = sessionTarget,
            payload = payload,
            state = CronJobState(nextRunAtMs = nextRunAtMs),
        )
        jobs.add(job)
        persist()
        if (job.enabled) {
            armTimer(job)
        }
        _events.tryEmit(CronEvent.JobCreated(job.id, job.name))
        job
    }

    /**
     * Remove a cron job.
     */
    suspend fun remove(id: String): Boolean = mutex.withLock {
        val removed = jobs.removeAll { it.id == id }
        if (removed) {
            timers[id]?.cancel()
            timers.remove(id)
            persist()
            _events.tryEmit(CronEvent.JobDeleted(id))
        }
        removed
    }

    /**
     * Enable or disable a job.
     */
    suspend fun setEnabled(id: String, enabled: Boolean): CronJob? = mutex.withLock {
        val index = jobs.indexOfFirst { it.id == id }
        if (index < 0) return@withLock null
        val now = clock()
        val updated = jobs[index].copy(
            enabled = enabled,
            updatedAtMs = now,
            state = if (enabled) {
                jobs[index].state.copy(nextRunAtMs = computeNextRunAtMs(jobs[index].schedule, now))
            } else {
                jobs[index].state.copy(nextRunAtMs = null)
            },
        )
        jobs[index] = updated
        persist()
        if (enabled) {
            armTimer(updated)
        } else {
            timers[id]?.cancel()
            timers.remove(id)
        }
        updated
    }

    /**
     * Force-run a job immediately, regardless of schedule.
     */
    suspend fun runNow(id: String) {
        val job = mutex.withLock { jobs.firstOrNull { it.id == id } } ?: return
        executeJob(job)
    }

    // --- Internal ---

    private fun computeNextRunAtMs(schedule: CronSchedule, nowMs: Long): Long? {
        return when (schedule) {
            is CronSchedule.At -> {
                val atMs = schedule.at.toLongOrNull()
                    ?: try { java.time.Instant.parse(schedule.at).toEpochMilli() } catch (_: Exception) { null }
                if (atMs != null && atMs > nowMs) atMs else null
            }
            is CronSchedule.Every -> {
                val anchor = schedule.anchorMs ?: nowMs
                if (nowMs < anchor) anchor
                else {
                    val elapsed = nowMs - anchor
                    val steps = ((elapsed + schedule.everyMs - 1) / schedule.everyMs).coerceAtLeast(1)
                    anchor + steps * schedule.everyMs
                }
            }
            is CronSchedule.Cron -> {
                val tz = if (!schedule.tz.isNullOrBlank()) TimeZone.getTimeZone(schedule.tz) else TimeZone.getDefault()
                CronExpression(schedule.expr).nextAfter(nowMs, tz)
            }
        }
    }

    private fun armTimer(job: CronJob) {
        timers[job.id]?.cancel()
        val nextMs = job.state.nextRunAtMs ?: return
        val delayMs = (nextMs - clock()).coerceAtLeast(0)
        timers[job.id] = scope.launch {
            delay(delayMs)
            executeJob(job)
        }
    }

    private suspend fun executeJob(job: CronJob) {
        _events.tryEmit(CronEvent.JobFired(job.id, job.name))
        val startMs = clock()
        var status = CronRunStatus.OK
        var error: String? = null

        try {
            executor.execute(job)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = CronRunStatus.ERROR
            error = e.message
            _events.tryEmit(CronEvent.JobError(job.id, e.message ?: "unknown error"))
        }

        val durationMs = clock() - startMs
        _events.tryEmit(CronEvent.JobCompleted(job.id, durationMs, status))

        mutex.withLock {
            val index = jobs.indexOfFirst { it.id == job.id }
            if (index < 0) return

            if (job.deleteAfterRun) {
                jobs.removeAt(index)
                timers[job.id]?.cancel()
                timers.remove(job.id)
                _events.tryEmit(CronEvent.JobDeleted(job.id))
            } else {
                val now = clock()
                val nextRunAtMs = computeNextRunAtMs(job.schedule, now)
                val consecutiveErrors = if (status == CronRunStatus.ERROR) {
                    job.state.consecutiveErrors + 1
                } else 0
                val updated = job.copy(
                    updatedAtMs = now,
                    state = CronJobState(
                        nextRunAtMs = nextRunAtMs,
                        lastRunAtMs = startMs,
                        lastRunStatus = status,
                        lastError = error,
                        lastDurationMs = durationMs,
                        consecutiveErrors = consecutiveErrors,
                    ),
                )
                jobs[index] = updated
                if (updated.enabled && nextRunAtMs != null) {
                    armTimer(updated)
                }
            }
            persist()
        }
    }

    private fun persist() {
        store.save(CronStoreFile(version = 1, jobs = jobs.toList()))
    }
}
