package ai.openclaw.runtime.devices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [DeviceToolPermissions] per-tool permission lookup functions.
 */
class DeviceToolPermissionsTest {

    // --- requiredPermissionsForTool ---

    @Test
    fun `camera tool requires CAMERA permission`() {
        val perms = requiredPermissionsForTool("camera")
        assertEquals(listOf(DevicePermission.CAMERA), perms)
    }

    @Test
    fun `location tool requires FINE and COARSE location permissions`() {
        val perms = requiredPermissionsForTool("location")
        assertTrue(perms.contains(DevicePermission.FINE_LOCATION))
        assertTrue(perms.contains(DevicePermission.COARSE_LOCATION))
        assertEquals(2, perms.size)
    }

    @Test
    fun `notify tool requires POST_NOTIFICATIONS permission`() {
        val perms = requiredPermissionsForTool("notify")
        assertEquals(listOf(DevicePermission.POST_NOTIFICATIONS), perms)
    }

    @Test
    fun `voice_input tool requires RECORD_AUDIO permission`() {
        val perms = requiredPermissionsForTool("voice_input")
        assertEquals(listOf(DevicePermission.RECORD_AUDIO), perms)
    }

    @Test
    fun `tts tool requires no permissions`() {
        val perms = requiredPermissionsForTool("tts")
        assertTrue(perms.isEmpty())
    }

    @Test
    fun `screen_capture tool requires no manifest permissions`() {
        val perms = requiredPermissionsForTool("screen_capture")
        assertTrue(perms.isEmpty())
    }

    @Test
    fun `unknown tool returns empty permissions`() {
        val perms = requiredPermissionsForTool("nonexistent_tool")
        assertTrue(perms.isEmpty())
    }

    // --- requiredManifestPermissionsForTool ---

    @Test
    fun `camera manifest permission is android permission CAMERA`() {
        val perms = requiredManifestPermissionsForTool("camera")
        assertEquals(listOf("android.permission.CAMERA"), perms)
    }

    @Test
    fun `location manifest permissions include FINE and COARSE`() {
        val perms = requiredManifestPermissionsForTool("location")
        assertTrue(perms.contains("android.permission.ACCESS_FINE_LOCATION"))
        assertTrue(perms.contains("android.permission.ACCESS_COARSE_LOCATION"))
    }

    @Test
    fun `voice_input manifest permission is RECORD_AUDIO`() {
        val perms = requiredManifestPermissionsForTool("voice_input")
        assertEquals(listOf("android.permission.RECORD_AUDIO"), perms)
    }

    @Test
    fun `unknown tool returns empty manifest permissions`() {
        val perms = requiredManifestPermissionsForTool("foo_bar")
        assertTrue(perms.isEmpty())
    }

    // --- allDeviceToolNames ---

    @Test
    fun `allDeviceToolNames contains all six tool names`() {
        val names = allDeviceToolNames()
        assertEquals(
            setOf("camera", "location", "notify", "tts", "voice_input", "screen_capture"),
            names,
        )
    }

    @Test
    fun `allDeviceToolNames size is six`() {
        assertEquals(6, allDeviceToolNames().size)
    }

    // --- DevicePermission enum ---

    @Test
    fun `DevicePermission CAMERA has correct manifest string`() {
        assertEquals("android.permission.CAMERA", DevicePermission.CAMERA.manifestPermission)
    }

    @Test
    fun `DevicePermission FINE_LOCATION has correct manifest string`() {
        assertEquals("android.permission.ACCESS_FINE_LOCATION", DevicePermission.FINE_LOCATION.manifestPermission)
    }

    @Test
    fun `DevicePermission COARSE_LOCATION has correct manifest string`() {
        assertEquals("android.permission.ACCESS_COARSE_LOCATION", DevicePermission.COARSE_LOCATION.manifestPermission)
    }

    @Test
    fun `DevicePermission RECORD_AUDIO has correct manifest string`() {
        assertEquals("android.permission.RECORD_AUDIO", DevicePermission.RECORD_AUDIO.manifestPermission)
    }

    @Test
    fun `DevicePermission POST_NOTIFICATIONS has correct manifest string`() {
        assertEquals("android.permission.POST_NOTIFICATIONS", DevicePermission.POST_NOTIFICATIONS.manifestPermission)
    }

    @Test
    fun `DevicePermission enum has exactly five values`() {
        assertEquals(5, DevicePermission.entries.size)
    }
}
