package ai.openclaw.runtime.engine.tools.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ProcessRegistry(
    private val cleanupMs: Long = 5 * 60_000L,
    private val maxOutputChars: Int = 200_000,
) {
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
        fun isRunning(): Boolean = process.isAlive
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, ProcessSession>()

    fun create(command: String, workingDir: String, process: Process): ProcessSession {
        purgeExpired()
        val session = ProcessSession(
            id = UUID.randomUUID().toString(),
            command = command,
            workingDir = workingDir,
            startedAtMs = System.currentTimeMillis(),
            process = process,
        )
        sessions[session.id] = session
        startCapture(session)
        return session
    }

    fun list(): List<ProcessSession> {
        purgeExpired()
        return sessions.values.sortedByDescending { it.startedAtMs }
    }

    fun get(sessionId: String): ProcessSession? {
        purgeExpired()
        return sessions[sessionId]
    }

    fun remove(sessionId: String): Boolean {
        return sessions.remove(sessionId) != null
    }

    fun kill(sessionId: String): Boolean {
        val session = sessions[sessionId] ?: return false
        session.process.destroyForcibly()
        return true
    }

    fun clearOutput(sessionId: String): Boolean {
        val session = sessions[sessionId] ?: return false
        synchronized(session.output) {
            session.output.clear()
        }
        return true
    }

    fun appendInput(sessionId: String, text: String): Boolean {
        val session = sessions[sessionId] ?: return false
        return runCatching {
            val writer = session.process.outputStream.bufferedWriter()
            writer.write(text)
            writer.flush()
            true
        }.getOrDefault(false)
    }

    fun tail(session: ProcessSession, maxChars: Int): String {
        val limit = maxChars.coerceIn(1_000, maxOutputChars)
        synchronized(session.output) {
            val text = session.output.toString()
            return if (text.length <= limit) text else text.takeLast(limit)
        }
    }

    private fun startCapture(session: ProcessSession) {
        scope.launch {
            captureStream(session)
        }
        scope.launch {
            runCatching {
                val code = session.process.waitFor()
                session.exitCode = code
                session.finishedAtMs = System.currentTimeMillis()
            }
        }
    }

    private suspend fun captureStream(session: ProcessSession) {
        runCatching {
            BufferedReader(InputStreamReader(session.process.inputStream)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    appendOutput(session, line)
                    line = reader.readLine()
                }
            }
        }
    }

    private fun appendOutput(session: ProcessSession, line: String) {
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

    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val (_, session) = iterator.next()
            val finishedAt = session.finishedAtMs
            if (finishedAt != null && now - finishedAt > cleanupMs) {
                iterator.remove()
            }
        }
    }
}
