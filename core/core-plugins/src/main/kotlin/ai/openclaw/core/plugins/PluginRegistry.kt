package ai.openclaw.core.plugins

/**
 * Plugin lifecycle hooks.
 */
interface PluginHooks {
    suspend fun onStart() {}
    suspend fun onStop() {}
    suspend fun onConfigReload() {}
}

/**
 * Plugin interface for OpenClaw extensions.
 */
interface OpenClawPlugin {
    val id: String
    val name: String
    val version: String
    val hooks: PluginHooks
}

/**
 * Registry for managing plugins.
 */
class PluginRegistry {
    private val plugins = mutableMapOf<String, OpenClawPlugin>()

    fun register(plugin: OpenClawPlugin) {
        plugins[plugin.id] = plugin
    }

    fun unregister(pluginId: String) {
        plugins.remove(pluginId)
    }

    fun get(pluginId: String): OpenClawPlugin? = plugins[pluginId]

    fun all(): List<OpenClawPlugin> = plugins.values.toList()

    suspend fun startAll() {
        plugins.values.forEach { it.hooks.onStart() }
    }

    suspend fun stopAll() {
        plugins.values.forEach { it.hooks.onStop() }
    }

    suspend fun notifyConfigReload() {
        plugins.values.forEach { it.hooks.onConfigReload() }
    }
}
