package ai.openclaw.android

import android.content.Context
import ai.openclaw.core.acp.AcpSessionManager
import ai.openclaw.core.config.ConfigLoader
import ai.openclaw.core.model.OpenClawConfig
import ai.openclaw.core.plugins.PluginRegistry
import ai.openclaw.runtime.providers.ProviderRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Central engine wiring all OpenClaw subsystems together.
 */
class AgentEngine(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val configLoader = ConfigLoader()
    val providerRegistry = ProviderRegistry()
    val pluginRegistry = PluginRegistry()
    val sessionManager = AcpSessionManager()

    var config: OpenClawConfig = OpenClawConfig()
        private set

    /**
     * Load configuration from the app's files directory.
     */
    fun loadConfig(): OpenClawConfig {
        val configDir = context.filesDir.resolve("config")
        val configFile = configDir.resolve("openclaw.json")
        config = if (configFile.exists()) {
            configLoader.parse(configFile.readText())
        } else {
            OpenClawConfig()
        }
        return config
    }

    /**
     * Initialize the engine with loaded config.
     */
    suspend fun initialize() {
        loadConfig()
        pluginRegistry.startAll()
    }

    /**
     * Shut down the engine gracefully.
     */
    suspend fun shutdown() {
        pluginRegistry.stopAll()
    }
}
