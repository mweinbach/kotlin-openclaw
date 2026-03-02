package ai.openclaw.android.ui.tools.terminal

import ai.openclaw.android.AgentEngine
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class TerminalViewModel(
    private val engine: AgentEngine? = null,
) : ViewModel() {

    val output = mutableStateListOf<String>()
    val commandHistory = mutableStateListOf<String>()
    var historyIndex by mutableIntStateOf(-1)
        private set

    private val executor = ShellExecutor(
        defaultWorkingDir = engine?.terminalWorkingDirectory() ?: System.getProperty("user.dir") ?: "/tmp",
    )
    private var currentJob: Job? = null

    fun executeCommand(command: String) {
        if (command.isBlank()) return

        if (currentJob?.isActive == true || executor.isRunning()) {
            executor.kill()
            currentJob?.cancel()
            currentJob = null
            appendOutput("^C")
        }

        commandHistory.add(0, command)
        historyIndex = -1
        appendOutput("$ $command")

        currentJob = viewModelScope.launch {
            try {
                val denialReason = engine?.authorizeToolInvocation(
                    toolName = "shell",
                    input = command,
                    agentId = engine.defaultAgentId(),
                    sessionKey = "terminal",
                )
                if (denialReason != null) {
                    appendOutput(denialReason)
                    return@launch
                }

                executor.execute(command).collect { line ->
                    appendOutput(line)
                }
            } catch (e: java.io.IOException) {
                appendOutput("Error: ${e.message}")
            } finally {
                currentJob = null
            }
        }
    }

    fun clear() {
        output.clear()
    }

    fun kill() {
        executor.kill()
        currentJob?.cancel()
        currentJob = null
        appendOutput("^C")
    }

    fun previousCommand(): String? {
        if (commandHistory.isEmpty()) return null
        val newIndex = (historyIndex + 1).coerceAtMost(commandHistory.size - 1)
        historyIndex = newIndex
        return commandHistory[newIndex]
    }

    fun nextCommand(): String? {
        if (historyIndex <= 0) {
            historyIndex = -1
            return ""
        }
        historyIndex--
        return commandHistory[historyIndex]
    }

    private fun appendOutput(line: String) {
        output.add(line)
        if (output.size > MAX_OUTPUT_LINES) {
            val toDrop = output.size - MAX_OUTPUT_LINES
            repeat(toDrop) { output.removeAt(0) }
        }
    }

    override fun onCleared() {
        kill()
        super.onCleared()
    }

    companion object {
        private const val MAX_OUTPUT_LINES = 2000
    }
}

class TerminalViewModelFactory(
    private val engine: AgentEngine,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return TerminalViewModel(engine) as T
    }
}
