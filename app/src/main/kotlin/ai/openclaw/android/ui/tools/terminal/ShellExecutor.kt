package ai.openclaw.android.ui.tools.terminal

import ai.openclaw.runtime.engine.tools.runtime.ShellCommandBootstrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import kotlin.coroutines.coroutineContext

/**
 * Executes shell commands via ProcessBuilder.
 */
class ShellExecutor(
    defaultWorkingDir: String = detectDefaultWorkingDir(),
    private val environmentProvider: () -> Map<String, String> = { emptyMap() },
    private val shellPathProvider: () -> String = { detectShellPath() },
) {
    private val processLock = Any()
    private var process: Process? = null

    var workingDir: String = defaultWorkingDir

    fun execute(command: String): Flow<String> = flow {
        val mergedEnv = LinkedHashMap(System.getenv())
        mergedEnv.putAll(environmentProvider())
        val wrappedCommand = ShellCommandBootstrap.apply(command, mergedEnv)
        val pb = ProcessBuilder(shellPathProvider(), "-c", wrappedCommand)
            .redirectErrorStream(true)
            .directory(java.io.File(workingDir))
        val processEnv = pb.environment()
        processEnv.clear()
        processEnv.putAll(mergedEnv)

        val proc = pb.start()
        reserveProcess(proc)

        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        try {
            var line = reader.readLine()
            while (line != null && coroutineContext.isActive) {
                emit(line)
                line = reader.readLine()
            }
        } finally {
            runCatching { reader.close() }
            if (!coroutineContext.isActive && proc.isAlive) {
                proc.destroyForcibly()
            }
            runCatching { proc.waitFor() }
            clearProcess(proc)
        }
    }.flowOn(Dispatchers.IO)

    fun kill() {
        synchronized(processLock) {
            process?.destroyForcibly()
            process = null
        }
    }

    fun isRunning(): Boolean = synchronized(processLock) {
        process?.isAlive == true
    }

    @Throws(IOException::class)
    private fun reserveProcess(proc: Process) {
        synchronized(processLock) {
            if (process?.isAlive == true) {
                proc.destroyForcibly()
                throw IOException("Another command is already running")
            }
            process = proc
        }
    }

    private fun clearProcess(proc: Process) {
        synchronized(processLock) {
            if (process == proc) {
                process = null
            }
        }
    }

    companion object {
        private fun detectShellPath(): String {
            val shell = System.getenv("SHELL")
                ?.takeIf { it.isNotBlank() }
                ?.let { java.io.File(it) }
                ?.takeIf { it.exists() && it.canExecute() }
            if (shell != null) {
                return shell.absolutePath
            }
            val androidSh = java.io.File("/system/bin/sh")
            return if (androidSh.exists()) "/system/bin/sh" else "/bin/sh"
        }

        private fun detectDefaultWorkingDir(): String {
            return System.getProperty("user.home")
                ?: System.getProperty("user.dir")
                ?: "/tmp"
        }
    }
}
