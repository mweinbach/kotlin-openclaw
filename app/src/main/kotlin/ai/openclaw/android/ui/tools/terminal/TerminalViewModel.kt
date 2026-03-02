package ai.openclaw.android.ui.tools.terminal

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class TerminalViewModel : ViewModel() {

    val output = mutableStateListOf<String>()
    val commandHistory = mutableStateListOf<String>()
    var historyIndex by mutableIntStateOf(-1)
        private set

    private val executor = ShellExecutor()
    private var currentJob: Job? = null

    fun executeCommand(command: String) {
        if (command.isBlank()) return

        commandHistory.add(0, command)
        historyIndex = -1
        output.add("$ $command")

        currentJob = viewModelScope.launch {
            try {
                executor.execute(command).collect { line ->
                    output.add(line)
                }
            } catch (e: java.io.IOException) {
                output.add("Error: ${e.message}")
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
        output.add("^C")
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
}
