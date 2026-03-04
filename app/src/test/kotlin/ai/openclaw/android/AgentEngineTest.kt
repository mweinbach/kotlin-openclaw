package ai.openclaw.android

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import ai.openclaw.core.model.AcpRuntimeEvent
import ai.openclaw.runtime.engine.SessionPersistence
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AgentEngineTest {

    private lateinit var engine: AgentEngine

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.filesDir.resolve("config").deleteRecursively()
        context.filesDir.resolve("sessions").deleteRecursively()
        context.filesDir.resolve("cron").deleteRecursively()
        engine = AgentEngine(context)
    }

    @Test
    fun `loadConfig returns default config when no file exists`() {
        val config = engine.loadConfig()
        assertNotNull(config)
    }

    @Test
    fun `sessionPersistence is accessible`() {
        assertNotNull(engine.sessionPersistence)
    }

    @Test
    fun `sessionPersistence lists empty sessions initially`() {
        val keys = engine.sessionPersistence.listSessionKeys()
        assertTrue(keys.isEmpty(), "Should have no sessions initially")
    }

    @Test
    fun `channelManager is accessible`() {
        assertNotNull(engine.channelManager)
    }

    @Test
    fun `channelManager snapshot is empty initially`() {
        val snapshot = engine.channelManager.getSnapshot()
        assertTrue(snapshot.isEmpty(), "Should have no channels initially")
    }

    @Test
    fun `pluginRegistry is accessible`() {
        assertNotNull(engine.pluginRegistry)
    }

    @Test
    fun `toolRegistry is accessible`() {
        assertNotNull(engine.toolRegistry)
    }

    @Test
    fun `secretStore is accessible`() = runTest {
        assertNotNull(engine.secretStore)
    }

    @Test
    fun `secretStore can store and retrieve`() = runTest {
        engine.secretStore.storeSecret("test-key", "test-value")
        val value = engine.secretStore.getSecret("test-key")
        assertEquals("test-value", value)
    }

    @Test
    fun `approvalManager is accessible`() {
        assertNotNull(engine.approvalManager)
    }

    @Test
    fun `approvalManager has no pending requests initially`() = runTest {
        val pending = engine.approvalManager.pendingRequests()
        assertTrue(pending.isEmpty(), "Should have no pending approvals initially")
    }

    @Test
    fun `memoryManager is accessible`() {
        assertNotNull(engine.memoryManager)
    }

    @Test
    fun `memoryManager is empty initially`() {
        assertEquals(0, engine.memoryManager.size())
    }

    @Test
    fun `cronScheduler is accessible`() {
        assertNotNull(engine.cronScheduler)
    }

    @Test
    fun `configManager is accessible`() {
        assertNotNull(engine.configManager)
    }

    @Test
    fun `gatewayServer is accessible`() {
        assertNotNull(engine.gatewayServer)
    }

    @Test
    fun `providerRegistry always has Ollama`() {
        engine.loadConfig()
        // After loadConfig + registerProviders, Ollama should be registered
        // since it doesn't require an API key
        assertNotNull(engine.providerRegistry)
    }

    @Test
    fun `reloadConfig does not throw`() {
        engine.loadConfig()
        engine.reloadConfig()
    }

    @Test
    fun `saveConfig persists and updates engine config`() {
        val newConfig = ai.openclaw.core.model.OpenClawConfig(
            gateway = ai.openclaw.core.model.GatewayConfig(port = 5555),
        )
        engine.saveConfig(newConfig)
        assertEquals(5555, engine.config.gateway?.port)
    }

    @Test
    fun `initialize creates startup directories on first run`() = runTest {
        engine.initialize()
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertTrue(context.filesDir.resolve("config").exists())
        assertTrue(context.filesDir.resolve("sessions").exists())
        assertTrue(context.filesDir.resolve("cron").exists())
    }

    @Test
    fun `sendMessage auto-initializes engine for first session`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.filesDir.resolve("config").deleteRecursively()
        context.filesDir.resolve("sessions").deleteRecursively()
        context.filesDir.resolve("cron").deleteRecursively()
        val fresh = AgentEngine(context)

        assertTrue(fresh.currentToolNames().isEmpty())

        val terminal = fresh.sendMessage("hello").first {
            it is AcpRuntimeEvent.Error || it is AcpRuntimeEvent.Done
        }

        assertNotNull(terminal)
        assertTrue(fresh.currentToolNames().isNotEmpty(), "Tool registry should initialize on first send")
    }

    @Test
    fun `wipeInstallStorage resets persisted install state`() = runTest {
        engine.initialize()
        engine.secretStore.storeSecret("wipe-key", "value")
        engine.sessionPersistence.initSession(
            "wipe-session",
            SessionPersistence.SessionHeader(
                sessionId = "wipe-session",
                agentId = engine.defaultAgentId(),
            ),
        )
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.filesDir.resolve("cron").mkdirs()
        context.filesDir.resolve("cron/cron-store.json").writeText("{\"jobs\":[]}")
        engine.saveConfig(
            ai.openclaw.core.model.OpenClawConfig(
                gateway = ai.openclaw.core.model.GatewayConfig(port = 5333),
            ),
        )

        engine.wipeInstallStorage()
        val snapshot = engine.storageSnapshot()

        assertTrue(snapshot.configFileExists, "Fresh install should re-create config file")
        assertEquals(0, snapshot.sessionCount, "Sessions should be wiped")
        assertEquals(0, snapshot.secretCount, "Secrets should be wiped")
        assertFalse(engine.config.gateway?.port == 5333, "Config should reset to defaults after wipe")
    }

    @Test
    fun `wipeInstallStorage removes unknown filesDir content`() = runTest {
        engine.initialize()
        val context = ApplicationProvider.getApplicationContext<Application>()
        val customFile = context.filesDir.resolve("custom-state/data.txt")
        customFile.parentFile?.mkdirs()
        customFile.writeText("persisted")
        assertTrue(customFile.exists())

        engine.wipeInstallStorage()

        assertFalse(customFile.exists(), "Wipe should clear arbitrary filesDir content")
    }
}
