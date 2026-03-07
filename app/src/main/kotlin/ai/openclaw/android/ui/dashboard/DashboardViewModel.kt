package ai.openclaw.android.ui.dashboard

import ai.openclaw.android.AgentEngine
import ai.openclaw.android.AgentForegroundService
import android.content.Context
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
        val backgroundRuntimeActive: Boolean = false,
        val keepAliveInBackground: Boolean = false,
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
        val nodeSupported: Boolean = false,
        val nodeInstalled: Boolean = false,
        val nodeActive: Boolean = false,
        val nodeManaged: Boolean = false,
        val nodeVersion: String? = null,
        val nodeMessage: String? = null,
        val availableBins: List<String> = emptyList(),
        val missingEssentialBins: List<String> = emptyList(),
        val missingRecommendedBins: List<String> = emptyList(),
        val customNodeDownloadUrl: String? = null,
        val customNodeSha256: String? = null,
        val toolchainActionInProgress: Boolean = false,
        val lastError: String? = null,
    )

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
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
                val toolchainStatus = engine.currentToolchainStatus()
                val managedNodeConfig = engine.currentManagedNodeToolchainConfig()

                DashboardState(
                    backgroundRuntimeActive = engine.isBackgroundRuntimeActive(),
                    keepAliveInBackground = engine.keepAliveInBackgroundEnabled(),
                    gatewayRunning = engine.isGatewayRunning(),
                    gatewayPort = engine.config.gateway?.port ?: 18789,
                    gatewayHost = engine.config.gateway?.customBindHost ?: "127.0.0.1",
                    channelCount = snapshot.size,
                    connectedChannels = running,
                    errorChannels = errors,
                    sessionCount = sessions.size,
                    memoryEntries = memorySize,
                    cronJobCount = cronJobs.size,
                    pluginCount = plugins.size,
                    nodeSupported = toolchainStatus.nodeSupported,
                    nodeInstalled = toolchainStatus.nodeInstalled,
                    nodeActive = toolchainStatus.nodeActive,
                    nodeManaged = toolchainStatus.nodeManaged,
                    nodeVersion = toolchainStatus.nodeVersion,
                    nodeMessage = toolchainStatus.nodeMessage,
                    availableBins = toolchainStatus.availableBins,
                    missingEssentialBins = toolchainStatus.missingEssentialBins,
                    missingRecommendedBins = toolchainStatus.missingRecommendedBins,
                    customNodeDownloadUrl = managedNodeConfig.downloadUrl,
                    customNodeSha256 = managedNodeConfig.sha256,
                    toolchainActionInProgress = _state.value.toolchainActionInProgress,
                    lastError = null,
                )
            }.onSuccess { freshState ->
                _state.value = freshState
            }.onFailure { error ->
                _state.value = _state.value.copy(lastError = error.message ?: "Failed to refresh dashboard")
            }
        }
    }

    fun installManagedNode() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(toolchainActionInProgress = true, lastError = null)
            runCatching {
                engine.installManagedNode()
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    toolchainActionInProgress = false,
                    lastError = error.message ?: "Managed Node install failed",
                )
            }.onSuccess {
                _state.value = _state.value.copy(toolchainActionInProgress = false)
                refresh()
            }
        }
    }

    fun saveCustomManagedNodeBundle(
        downloadUrl: String,
        sha256: String,
        installAfterSave: Boolean,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(toolchainActionInProgress = true, lastError = null)
            runCatching {
                engine.configureManagedNodeCustomBundle(
                    downloadUrl = downloadUrl,
                    sha256 = sha256,
                    installAfterSave = installAfterSave,
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    toolchainActionInProgress = false,
                    lastError = error.message ?: "Failed to save custom Node bundle",
                )
            }.onSuccess {
                _state.value = _state.value.copy(toolchainActionInProgress = false)
                refresh()
            }
        }
    }

    fun clearCustomManagedNodeBundle() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(toolchainActionInProgress = true, lastError = null)
            runCatching {
                engine.clearManagedNodeCustomBundle()
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    toolchainActionInProgress = false,
                    lastError = error.message ?: "Failed to clear custom Node bundle",
                )
            }.onSuccess {
                _state.value = _state.value.copy(toolchainActionInProgress = false)
                refresh()
            }
        }
    }

    fun startBackgroundRuntime(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            AgentForegroundService.start(context.applicationContext)
            refresh()
        }
    }

    fun stopBackgroundRuntime(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            AgentForegroundService.stop(context.applicationContext)
            refresh()
        }
    }

    fun setKeepAliveInBackground(context: Context, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            engine.setKeepAliveInBackground(enabled)
            if (enabled) {
                AgentForegroundService.start(context.applicationContext)
            } else {
                AgentForegroundService.stop(context.applicationContext)
            }
            refresh()
        }
    }

    fun restartGateway() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                engine.startBackgroundRuntime()
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
