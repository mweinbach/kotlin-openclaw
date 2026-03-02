package ai.openclaw.android.ui.dashboard

import ai.openclaw.android.AgentEngine
import ai.openclaw.runtime.gateway.ChannelManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DashboardViewModel(private val engine: AgentEngine) : ViewModel() {

    data class DashboardState(
        val gatewayRunning: Boolean = false,
        val gatewayPort: Int = 18789,
        val gatewayHost: String = "127.0.0.1",
        val channelCount: Int = 0,
        val connectedChannels: Int = 0,
        val errorChannels: Int = 0,
        val sessionCount: Int = 0,
        val memoryEntries: Int = 0,
        val cronJobCount: Int = 0,
        val pluginCount: Int = 0,
    )

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val snapshot = engine.channelManager.getSnapshot()
            val running = snapshot.values.count {
                it.status == ChannelManager.ChannelStatus.RUNNING.name.lowercase()
            }
            val errors = snapshot.values.count {
                it.status == ChannelManager.ChannelStatus.ERROR.name.lowercase()
            }
            val sessions = engine.sessionPersistence.listSessionKeys()
            val memorySize = engine.memoryManager.size()
            val cronJobs = engine.cronScheduler.list()
            val plugins = engine.pluginRegistry.allRecords()

            _state.value = DashboardState(
                gatewayRunning = engine.gatewayServer.isRunning,
                gatewayPort = engine.config.gateway?.port ?: 18789,
                gatewayHost = engine.config.gateway?.customBindHost ?: "127.0.0.1",
                channelCount = snapshot.size,
                connectedChannels = running,
                errorChannels = errors,
                sessionCount = sessions.size,
                memoryEntries = memorySize,
                cronJobCount = cronJobs.size,
                pluginCount = plugins.size,
            )
        }
    }

    fun restartGateway() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                engine.gatewayServer.stop()
                engine.gatewayServer.start()
            } catch (_: Exception) {
                // Best-effort restart
            }
            refresh()
        }
    }

    fun clearSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            val keys = engine.sessionPersistence.listSessionKeys()
            for (key in keys) {
                engine.sessionPersistence.delete(key)
            }
            refresh()
        }
    }

    fun reloadConfig() {
        viewModelScope.launch(Dispatchers.IO) {
            engine.reloadConfig()
            refresh()
        }
    }
}

class DashboardViewModelFactory(
    private val engine: AgentEngine,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return DashboardViewModel(engine) as T
    }
}
