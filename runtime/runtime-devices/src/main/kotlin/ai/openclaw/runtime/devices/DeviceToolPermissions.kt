package ai.openclaw.runtime.devices

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Permission requirements for device tools.
 * Each device tool declares which permissions it needs.
 */
enum class DevicePermission(val manifestPermission: String) {
    CAMERA(Manifest.permission.CAMERA),
    FINE_LOCATION(Manifest.permission.ACCESS_FINE_LOCATION),
    COARSE_LOCATION(Manifest.permission.ACCESS_COARSE_LOCATION),
    RECORD_AUDIO(Manifest.permission.RECORD_AUDIO),
    POST_NOTIFICATIONS(Manifest.permission.POST_NOTIFICATIONS),
}

/**
 * Checks whether all required permissions are granted.
 */
fun Context.hasPermissions(vararg permissions: DevicePermission): Boolean =
    permissions.all {
        ContextCompat.checkSelfPermission(this, it.manifestPermission) == PackageManager.PERMISSION_GRANTED
    }

/**
 * Returns the list of permissions that are NOT yet granted.
 */
fun Context.missingPermissions(vararg permissions: DevicePermission): List<DevicePermission> =
    permissions.filter {
        ContextCompat.checkSelfPermission(this, it.manifestPermission) != PackageManager.PERMISSION_GRANTED
    }

/**
 * Mapping of tool names to their required [DevicePermission] entries.
 * Tools not listed here have no runtime permission requirements
 * (e.g., screen capture uses a MediaProjection token instead).
 */
private val TOOL_PERMISSIONS: Map<String, List<DevicePermission>> = mapOf(
    "camera" to listOf(DevicePermission.CAMERA),
    "location" to listOf(DevicePermission.FINE_LOCATION, DevicePermission.COARSE_LOCATION),
    "notify" to listOf(DevicePermission.POST_NOTIFICATIONS),
    "tts" to emptyList(),
    "voice_input" to listOf(DevicePermission.RECORD_AUDIO),
    "screen_capture" to emptyList(),
)

/**
 * Returns the [DevicePermission] entries required by a given tool name.
 * Returns an empty list for unknown tools or tools with no permission requirements.
 */
fun requiredPermissionsForTool(toolName: String): List<DevicePermission> =
    TOOL_PERMISSIONS[toolName].orEmpty()

/**
 * Returns the Android manifest permission strings required by a given tool name.
 */
fun requiredManifestPermissionsForTool(toolName: String): List<String> =
    requiredPermissionsForTool(toolName).map { it.manifestPermission }

/**
 * Returns the set of all known device tool names that have permission mappings.
 */
fun allDeviceToolNames(): Set<String> = TOOL_PERMISSIONS.keys
