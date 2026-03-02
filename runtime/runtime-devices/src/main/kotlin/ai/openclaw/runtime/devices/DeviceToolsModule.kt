package ai.openclaw.runtime.devices

import android.content.Context
import ai.openclaw.core.agent.AgentTool
import ai.openclaw.core.model.TtsConfig
import ai.openclaw.runtime.engine.ToolRegistry

/**
 * Configuration for which device tools to enable.
 */
data class DeviceToolsConfig(
    val camera: Boolean = true,
    val location: Boolean = true,
    val notifications: Boolean = true,
    val tts: Boolean = true,
    val voice: Boolean = true,
    val screenCapture: Boolean = true,
    val ttsConfig: TtsConfig? = null,
    /** Activity class to launch when notification is tapped. */
    val launchActivityClass: Class<*>? = null,
)

/**
 * Module that creates and registers all device tools.
 * Call [registerAll] to add device tools to a [ToolRegistry].
 */
object DeviceToolsModule {

    /**
     * Create all enabled device tools for the given Android context.
     */
    fun createTools(context: Context, config: DeviceToolsConfig = DeviceToolsConfig()): List<AgentTool> {
        val tools = mutableListOf<AgentTool>()

        if (config.camera) {
            tools.add(CameraTool(context))
        }
        if (config.location) {
            tools.add(LocationTool(context))
        }
        if (config.notifications) {
            tools.add(NotificationTool(context, config.launchActivityClass))
        }
        if (config.tts) {
            tools.add(TtsTool(context, config.ttsConfig))
        }
        if (config.voice) {
            tools.add(VoiceTool(context))
        }
        if (config.screenCapture) {
            tools.add(ScreenCaptureTool(context))
        }

        return tools
    }

    /**
     * Register all enabled device tools into the given [ToolRegistry].
     */
    fun registerAll(
        registry: ToolRegistry,
        context: Context,
        config: DeviceToolsConfig = DeviceToolsConfig(),
    ) {
        createTools(context, config).forEach { registry.register(it) }
    }

    /**
     * Get the list of all Android permissions required by enabled device tools.
     */
    fun requiredPermissions(config: DeviceToolsConfig = DeviceToolsConfig()): List<String> {
        val permissions = mutableListOf<String>()
        if (config.camera) {
            permissions.add(DevicePermission.CAMERA.manifestPermission)
        }
        if (config.location) {
            permissions.add(DevicePermission.FINE_LOCATION.manifestPermission)
            permissions.add(DevicePermission.COARSE_LOCATION.manifestPermission)
        }
        if (config.notifications) {
            permissions.add(DevicePermission.POST_NOTIFICATIONS.manifestPermission)
        }
        if (config.voice) {
            permissions.add(DevicePermission.RECORD_AUDIO.manifestPermission)
        }
        return permissions.distinct()
    }
}
