package ai.openclaw.android

import ai.openclaw.core.model.GatewayConfig
import ai.openclaw.core.model.OpenClawConfig
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConfigManagerTest {

    private lateinit var configManager: ConfigManager

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        // Clean up any existing config
        context.filesDir.resolve("config").deleteRecursively()
        configManager = ConfigManager(context)
    }

    @Test
    fun `load returns default config when no file exists`() {
        val config = configManager.load()
        assertNotNull(config)
    }

    @Test
    fun `save creates config file`() {
        val config = OpenClawConfig()
        configManager.save(config)

        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = context.filesDir.resolve("config/openclaw.json")
        assertTrue(file.exists(), "Config file should exist after save")
    }

    @Test
    fun `save and load round-trip preserves gateway port`() {
        val config = OpenClawConfig(gateway = GatewayConfig(port = 9999))
        configManager.save(config)

        val loaded = configManager.load()
        assertEquals(9999, loaded.gateway?.port)
    }

    @Test
    fun `observe emits updated config`() = runTest {
        val flow = configManager.observe()

        configManager.save(OpenClawConfig(gateway = GatewayConfig(port = 4444)))
        val latest = flow.first()
        assertEquals(4444, latest.gateway?.port)
    }

    @Test
    fun `update applies transformation`() {
        configManager.load()
        configManager.update {
            copy(gateway = GatewayConfig(port = 7777))
        }
        val latest = configManager.config.value
        assertEquals(7777, latest.gateway?.port)
    }

    @Test
    fun `config StateFlow has initial value`() {
        val value = configManager.config.value
        assertNotNull(value)
    }

    @Test
    fun `multiple saves overwrite correctly`() {
        configManager.save(OpenClawConfig(gateway = GatewayConfig(port = 1111)))
        configManager.save(OpenClawConfig(gateway = GatewayConfig(port = 2222)))

        val loaded = configManager.load()
        assertEquals(2222, loaded.gateway?.port)
    }
}
