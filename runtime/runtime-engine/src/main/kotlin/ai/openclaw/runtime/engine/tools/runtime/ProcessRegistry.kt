package ai.openclaw.runtime.engine.tools.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.lang.ref.Cleaner
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ProcessRegistry(
    private val cleanupMs: Long = 5 * 60_000L,
    private val maxOutputChars: Int = 200_000,
) : AutoCloseable {
    data class ProcessSession(
        val id: String,
        val command: String,
        val workingDir: String,
        val startedAtMs: Long,
        val process: Process,
        val output: StringBuilder = StringBuilder(),
        @Volatile var exitCode: Int? = null,
        @Volatile var finishedAtMs: Long? = null,
    ) {
        @Volatile
        internal var captureJob: Job? = null

        fun isRunning(): Boolean = process.isAlive
    }

    private val state = State(
        cleanupMs = cleanupMs,
        maxOutputChars = maxOutputChars,
    )
    private val cleanable = CLEANER.register(this, state)

    fun create(command: String, workingDir: String, process: Process): ProcessSession {
        return state.create(command = command, workingDir = workingDir, process = process)
    }

    fun list(): List<ProcessSession> {
        return state.list()
    }

    fun get(sessionId: String): ProcessSession? {
        return state.get(sessionId)
    }

    fun remove(sessionId: String): Boolean {
        return state.remove(sessionId)
    }

    fun kill(sessionId: String): Boolean {
        return state.kill(sessionId)
    }

    fun killAll(removeSessions: Boolean = false): Int {
        return state.killAll(removeSessions)
    }

    fun clearOutput(sessionId: String): Boolean {
        val session = state.get(sessionId) ?: return false
        synchronized(session.output) {
            session.output.clear()
        }
        return true
    }

    fun appendInput(sessionId: String, text: String): Boolean {
        val session = state.get(sessionId) ?: return false
        return runCatching {
            val writer = session.process.outputStream.bufferedWriter()
            writer.write(text)
            writer.flush()
            true
        }.getOrDefault(false)
    }

    fun tail(session: ProcessSession, maxChars: Int): String {
        val limit = maxChars.coerceIn(1_000, maxOutputChars)
        if (!session.isRunning()) {
            runBlocking {
                session.captureJob?.join()
            }
        }
        synchronized(session.output) {
            val text = session.output.toString()
            return if (text.length <= limit) text else text.takeLast(limit)
        }
    }

    override fun close() {
        cleanable.clean()
    }

    private class State(
        private val cleanupMs: Long,
        private val maxOutputChars: Int,
    ) : Runnable {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val sessions = ConcurrentHashMap<String, ProcessSession>()
        private val lifecycleLock = Any()
        @Volatile
        private var closed = false

        fun create(command: String, workingDir: String, process: Process): ProcessSession {
            val session = synchronized(lifecycleLock) {
                purgeExpiredLocked()
                if (closed) {
                    null
                } else {
                    ProcessSession(
                        id = UUID.randomUUID().toString(),
                        command = command,
                        workingDir = workingDir,
                        startedAtMs = System.currentTimeMillis(),
                        process = process,
                    ).also { created ->
                        sessions[created.id] = created
                    }
                }
            } ?: run {
                terminateProcess(process)
                throw IllegalStateException("Process registry is closed")
            }

            startCapture(session)
            return session
        }

        fun list(): List<ProcessSession> = synchronized(lifecycleLock) {
            purgeExpiredLocked()
            sessions.values.sortedByDescending { it.startedAtMs }
        }

        fun get(sessionId: String): ProcessSession? = synchronized(lifecycleLock) {
            purgeExpiredLocked()
            sessions[sessionId]
        }

        fun remove(sessionId: String): Boolean {
            val session = synchronized(lifecycleLock) {
                purgeExpiredLocked()
                sessions.remove(sessionId)
            } ?: return false
            terminateSession(session)
            return true
        }

        fun kill(sessionId: String): Boolean {
            val session = synchronized(lifecycleLock) {
                purgeExpiredLocked()
                sessions[sessionId]
            } ?: return false
            terminateSession(session)
            return true
        }

        fun killAll(removeSessions: Boolean = false): Int {
            val snapshot = synchronized(lifecycleLock) {
                purgeExpiredLocked()
                val current = sessions.values.toList()
                if (removeSessions) {
                    sessions.clear()
                }
                current
            }
            snapshot.forEach(::terminateSession)
            return snapshot.size
        }

        override fun run() {
            val snapshot = synchronized(lifecycleLock) {
                if (closed) {
                    emptyList()
                } else {
                    closed = true
                    val current = sessions.values.toList()
                    sessions.clear()
                    current
                }
            }
            snapshot.forEach(::terminateSession)
            scope.cancel()
        }

        private fun startCapture(session: ProcessSession) {
            val outputLimit = maxOutputChars
            session.captureJob = scope.launch {
                captureStream(session, outputLimit)
            }
            scope.launch {
                awaitExit(session)
            }
        }

        private fun purgeExpiredLocked() {
            val now = System.currentTimeMillis()
            val expiredIds = sessions.values
                .filter { session ->
                    val finishedAt = session.finishedAtMs
                    finishedAt != null && now - finishedAt > cleanupMs
                }
                .map(ProcessSession::id)
            expiredIds.forEach(sessions::remove)
        }
    }

    companion object {
        private val CLEANER: Cleaner = Cleaner.create()
        private const val GRACEFUL_TERMINATION_WAIT_MS = 100L
        private const val FORCE_TERMINATION_WAIT_MS = 1_000L

        private suspend fun captureStream(session: ProcessSession, maxOutputChars: Int) {
            runCatching {
                BufferedReader(InputStreamReader(session.process.inputStream)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        appendOutput(session, line, maxOutputChars)
                        line = reader.readLine()
                    }
                }
            }
        }

        private fun awaitExit(session: ProcessSession) {
            runCatching {
                val code = session.process.waitFor()
                session.exitCode = code
                session.finishedAtMs = System.currentTimeMillis()
            }
        }

        private fun appendOutput(session: ProcessSession, line: String, maxOutputChars: Int) {
            synchronized(session.output) {
                if (session.output.isNotEmpty()) {
                    session.output.append('\n')
                }
                session.output.append(line)
                if (session.output.length > maxOutputChars) {
                    val extra = session.output.length - maxOutputChars
                    session.output.delete(0, extra)
                }
            }
        }

        private fun terminateSession(session: ProcessSession) {
            terminateProcess(session.process)
            if (!session.process.isAlive) {
                session.exitCode = runCatching { session.process.exitValue() }.getOrNull() ?: session.exitCode
                session.finishedAtMs = session.finishedAtMs ?: System.currentTimeMillis()
            }
        }

        private fun terminateProcess(process: Process) {
            if (process.isAlive) {
                runCatching { process.destroy() }
                val exitedGracefully = runCatching {
                    process.waitFor(GRACEFUL_TERMINATION_WAIT_MS, TimeUnit.MILLISECONDS)
                }.getOrDefault(false)
                if (!exitedGracefully && process.isAlive) {
                    runCatching { process.destroyForcibly() }
                }
                if (process.isAlive) {
                    runCatching { process.waitFor(FORCE_TERMINATION_WAIT_MS, TimeUnit.MILLISECONDS) }
                }
            }

            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }
}
