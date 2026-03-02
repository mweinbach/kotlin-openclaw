package ai.openclaw.core.plugins

/**
 * Plugin lifecycle hooks.
 */
interface PluginLifecycleHooks {
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
    val hooks: PluginLifecycleHooks
}

/**
 * A typed hook registration linking a plugin to a hook handler.
 */
data class PluginHookRegistration<T>(
    val pluginId: String,
    val hookName: PluginHookName,
    val handler: T,
    val priority: Int = 0,
    val source: String = "",
)

/**
 * Record describing a loaded plugin's capabilities.
 */
data class PluginRecord(
    val id: String,
    val name: String,
    val version: String? = null,
    val description: String? = null,
    val kind: String? = null,
    val source: String = "",
    val origin: PluginOrigin = PluginOrigin.WORKSPACE,
    val enabled: Boolean = true,
    val status: String = "loaded",
    val error: String? = null,
    val toolNames: MutableList<String> = mutableListOf(),
    val hookNames: MutableList<String> = mutableListOf(),
    val channelIds: MutableList<String> = mutableListOf(),
    val providerIds: MutableList<String> = mutableListOf(),
    val gatewayMethods: MutableList<String> = mutableListOf(),
    val cliCommands: MutableList<String> = mutableListOf(),
    val services: MutableList<String> = mutableListOf(),
    val commands: MutableList<String> = mutableListOf(),
    val httpHandlers: Int = 0,
    val hookCount: Int = 0,
)

/**
 * Registry for managing plugins.
 */
class PluginRegistry {
    private val plugins = java.util.concurrent.ConcurrentHashMap<String, OpenClawPlugin>()
    private val records = java.util.concurrent.CopyOnWriteArrayList<PluginRecord>()
    private val hookRegistrations = java.util.concurrent.CopyOnWriteArrayList<PluginHookRegistration<*>>()
    private val diagnostics = java.util.concurrent.CopyOnWriteArrayList<PluginDiagnostic>()

    fun register(plugin: OpenClawPlugin) {
        plugins[plugin.id] = plugin
    }

    fun unregister(pluginId: String) {
        plugins.remove(pluginId)
        hookRegistrations.removeAll { it.pluginId == pluginId }
    }

    fun get(pluginId: String): OpenClawPlugin? = plugins[pluginId]

    fun all(): List<OpenClawPlugin> = plugins.values.toList()

    fun allRecords(): List<PluginRecord> = records.toList()

    /**
     * Find all plugins that have registered a handler for the given hook name.
     */
    fun getByHook(hookName: PluginHookName): List<OpenClawPlugin> {
        val pluginIds = hookRegistrations
            .filter { it.hookName == hookName }
            .map { it.pluginId }
            .toSet()
        return pluginIds.mapNotNull { plugins[it] }
    }

    /**
     * Register a typed hook handler for a specific plugin.
     */
    fun <T> registerHook(registration: PluginHookRegistration<T>) {
        hookRegistrations.add(registration)
    }

    /**
     * Get all hook registrations for a given hook name, sorted by priority (higher first).
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getHooksForName(hookName: PluginHookName): List<PluginHookRegistration<T>> {
        return hookRegistrations
            .filter { it.hookName == hookName }
            .sortedByDescending { it.priority }
            .map { it as PluginHookRegistration<T> }
    }

    /**
     * Check if any hooks are registered for a given hook name.
     */
    fun hasHooks(hookName: PluginHookName): Boolean {
        return hookRegistrations.any { it.hookName == hookName }
    }

    /**
     * Get count of registered hooks for a given hook name.
     */
    fun getHookCount(hookName: PluginHookName): Int {
        return hookRegistrations.count { it.hookName == hookName }
    }

    fun addDiagnostic(diagnostic: PluginDiagnostic) {
        diagnostics.add(diagnostic)
    }

    fun getDiagnostics(): List<PluginDiagnostic> = diagnostics.toList()

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
