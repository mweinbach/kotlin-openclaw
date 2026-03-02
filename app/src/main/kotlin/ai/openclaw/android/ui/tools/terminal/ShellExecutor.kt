package ai.openclaw.android.ui.tools.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.coroutines.coroutineContext

/**
 * Executes shell commands via ProcessBuilder on the Android device.
 */
class ShellExecutor {

    private var process: Process? = null
    var workingDir: String = defaultWorkingDir()

    companion object {
        private fun defaultWorkingDir(): String {
            val androidTmp = java.io.File("/data/local/tmp")
            return if (androidTmp.isDirectory) "/data/local/tmp" else System.getProperty("user.dir") ?: "/tmp"
        }
    }

    private val shellPath: String
        get() {
            // Use /system/bin/sh on Android, /bin/sh on JVM (for testing)
            val androidSh = java.io.File("/system/bin/sh")
            return if (androidSh.exists()) "/system/bin/sh" else "/bin/sh"
        }

    fun execute(command: String): Flow<String> = flow {
        val pb = ProcessBuilder(shellPath, "-c", command)
            .redirectErrorStream(true)
            .directory(java.io.File(workingDir))

        val proc = pb.start()
        process = proc

        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        try {
            var line = reader.readLine()
            while (line != null && coroutineContext.isActive) {
                emit(line)
                line = reader.readLine()
            }
        } finally {
            reader.close()
            proc.waitFor()
            process = null
        }
    }.flowOn(Dispatchers.IO)

    fun kill() {
        process?.destroyForcibly()
        process = null
    }

    fun isRunning(): Boolean = process?.isAlive == true
}
