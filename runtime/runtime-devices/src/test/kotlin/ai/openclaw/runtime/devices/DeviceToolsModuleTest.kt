package ai.openclaw.runtime.devices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceToolsModuleTest {

    @Test
    fun `requiredPermissions returns camera permission when camera enabled`() {
        val config = DeviceToolsConfig(camera = true, location = false, notifications = false, voice = false)
        val permissions = DeviceToolsModule.requiredPermissions(config)
        assertTrue(permissions.contains("android.permission.CAMERA"))
    }

    @Test
    fun `requiredPermissions returns location permissions when location enabled`() {
        val config = DeviceToolsConfig(camera = false, location = true, notifications = false, voice = false)
        val permissions = DeviceToolsModule.requiredPermissions(config)
        assertTrue(permissions.contains("android.permission.ACCESS_FINE_LOCATION"))
        assertTrue(permissions.contains("android.permission.ACCESS_COARSE_LOCATION"))
    }

    @Test
    fun `requiredPermissions returns audio permission when voice enabled`() {
        val config = DeviceToolsConfig(camera = false, location = false, notifications = false, voice = true)
        val permissions = DeviceToolsModule.requiredPermissions(config)
        assertTrue(permissions.contains("android.permission.RECORD_AUDIO"))
    }

    @Test
    fun `requiredPermissions returns notification permission when notifications enabled`() {
        val config = DeviceToolsConfig(camera = false, location = false, notifications = true, voice = false)
        val permissions = DeviceToolsModule.requiredPermissions(config)
        assertTrue(permissions.contains("android.permission.POST_NOTIFICATIONS"))
    }

    @Test
    fun `requiredPermissions returns empty when all disabled`() {
        val config = DeviceToolsConfig(
            camera = false,
            location = false,
            notifications = false,
            tts = false,
            voice = false,
            screenCapture = false,
        )
        val permissions = DeviceToolsModule.requiredPermissions(config)
        assertTrue(permissions.isEmpty())
    }

    @Test
    fun `requiredPermissions returns distinct values`() {
        val config = DeviceToolsConfig()
        val permissions = DeviceToolsModule.requiredPermissions(config)
        assertEquals(permissions.size, permissions.distinct().size)
    }

    @Test
    fun `default config enables all tools`() {
        val config = DeviceToolsConfig()
        assertTrue(config.camera)
        assertTrue(config.location)
        assertTrue(config.notifications)
        assertTrue(config.tts)
        assertTrue(config.voice)
        assertTrue(config.screenCapture)
    }
}
