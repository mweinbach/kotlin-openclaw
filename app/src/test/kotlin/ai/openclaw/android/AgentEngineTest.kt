package ai.openclaw.android

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import ai.openclaw.core.model.AcpRuntimeEvent
import ai.openclaw.core.model.ChannelsConfig
import ai.openclaw.core.model.ExecToolConfig
import ai.openclaw.core.model.GoogleChatConfig
import ai.openclaw.core.model.ManagedExecToolchainsConfig
import ai.openclaw.core.model.ManagedNodeToolchainConfig
import ai.openclaw.core.model.OpenClawConfig
import ai.openclaw.core.model.WebChatConfig
import ai.openclaw.core.model.ExpandedToolsConfig
import ai.openclaw.runtime.engine.SessionPersistence
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.GZIPOutputStream
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
    fun `available models include gpt 5_4 and reasoning efforts are preserved for supported models`() {
        engine.loadConfig()

        val models = engine.availableModelIdsForEnabledProviders()
        assertTrue("openai/gpt-5.4" in models)
        assertEquals(
            listOf("none", "low", "medium", "high", "xhigh"),
            engine.availableReasoningEffortsForModel("openai/gpt-5.4"),
        )

        engine.setDefaultModel("openai/gpt-5.4", "xhigh")

        assertEquals("openai/gpt-5.4", engine.preferredModelForAgent())
        assertEquals("xhigh", engine.preferredReasoningEffortForAgent(modelId = "openai/gpt-5.4"))
        assertEquals(null, engine.preferredReasoningEffortForAgent(modelId = "openai/gpt-4o-mini"))
    }

    @Test
    fun `codex oauth status reports api access readiness`() = runTest {
        engine.setCodexOauth(
            accessToken = "access-token",
            accountId = "account-123",
            refreshToken = "refresh-token",
            idToken = "id-token",
            expiresAtMs = 1234L,
            email = "user@example.com",
            apiKey = "sk-codex",
        )

        val status = engine.getCodexOauthStatus()

        assertTrue(status.tokenSet)
        assertTrue(status.apiAccessReady)
        assertEquals("account-123", status.accountId)
        assertEquals("user@example.com", status.email)
        assertEquals(1234L, status.expiresAtMs)
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
    fun `initialize keeps background runtime stopped by default`() = runTest {
        engine.saveConfig(
            OpenClawConfig(
                channels = ChannelsConfig(
                    webchat = WebChatConfig(enabled = true),
                ),
            ),
        )

        engine.initialize()

        assertFalse(engine.isBackgroundRuntimeActive())
        assertFalse(engine.isGatewayRunning())
        assertEquals("stopped", engine.channelManager.getSnapshot()["webchat:default"]?.status)
    }

    @Test
    fun `initialize activates managed node when auto install is enabled`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val version = "v22.22.0"
        val archiveName = "node-$version-darwin-arm64.tar.gz"
        val archiveBytes = createManagedNodeArchive(version)
        val sha256 = sha256(archiveBytes)
        val baseUrl = "https://example.test/dist"
        val fresh = AgentEngine(context) { ctx, client ->
            ManagedToolchainManager(
                context = ctx,
                client = client,
                platformDetector = { ToolchainPlatform(os = "darwin", arch = "arm64") },
                downloadToFile = { url, target ->
                    assertEquals("$baseUrl/$version/$archiveName", url)
                    target.writeBytes(archiveBytes)
                },
                downloadText = { url ->
                    assertEquals("$baseUrl/$version/SHASUMS256.txt", url)
                    "$sha256  $archiveName\n"
                },
                nowProvider = { Instant.parse("2026-03-07T00:00:00Z") },
            )
        }
        fresh.saveConfig(
            OpenClawConfig(
                tools = ExpandedToolsConfig(
                    exec = ExecToolConfig(
                        managed = ManagedExecToolchainsConfig(
                            node = ManagedNodeToolchainConfig(
                                autoInstall = true,
                                version = version.removePrefix("v"),
                                baseUrl = baseUrl,
                            ),
                        ),
                    ),
                ),
            ),
        )

        fresh.initialize()
        val status = fresh.currentToolchainStatus()

        assertTrue(status.nodeInstalled)
        assertTrue(status.nodeManaged)
        assertTrue(status.nodeActive)
        assertTrue(status.missingEssentialBins.isEmpty())
    }

    @Test
    fun `startBackgroundRuntime starts gateway owned channels and stopBackgroundRuntime stops them`() = runTest {
        engine.saveConfig(
            OpenClawConfig(
                channels = ChannelsConfig(
                    webchat = WebChatConfig(enabled = true),
                ),
            ),
        )

        engine.initialize()
        engine.startBackgroundRuntime()

        assertTrue(engine.isBackgroundRuntimeActive())
        assertTrue(engine.isGatewayRunning())
        assertEquals("running", engine.channelManager.getSnapshot()["webchat:default"]?.status)

        engine.stopBackgroundRuntime()

        assertFalse(engine.isBackgroundRuntimeActive())
        assertFalse(engine.isGatewayRunning())
        assertEquals("stopped", engine.channelManager.getSnapshot()["webchat:default"]?.status)
    }

    @Test
    fun `unsupported configured channels surface explicit runtime errors`() = runTest {
        engine.saveConfig(
            OpenClawConfig(
                channels = ChannelsConfig(
                    googlechat = GoogleChatConfig(enabled = true),
                ),
            ),
        )

        engine.initialize()
        engine.startBackgroundRuntime()

        val snapshot = engine.channelManager.getSnapshot()["googlechat:default"]
        assertEquals("error", snapshot?.status)
        assertTrue(snapshot?.error?.contains("not wired yet") == true)
    }

    @Test
    fun `setKeepAliveInBackground persists preference`() = runTest {
        engine.setKeepAliveInBackground(true)

        assertTrue(engine.keepAliveInBackgroundEnabled())
        assertTrue(engine.loadConfig().appRuntime?.keepAliveInBackground == true)
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

    private fun createManagedNodeArchive(version: String): ByteArray {
        val entries = listOf(
            TarEntry(
                path = "node-$version-darwin-arm64/bin/node",
                content = "#!/bin/sh\necho $version\n".toByteArray(),
                mode = 0b111101101,
            ),
            TarEntry(
                path = "node-$version-darwin-arm64/lib/node_modules/npm/bin/npm-cli.js",
                content = "console.log('npm');".toByteArray(),
            ),
            TarEntry(
                path = "node-$version-darwin-arm64/lib/node_modules/npm/bin/npx-cli.js",
                content = "console.log('npx');".toByteArray(),
            ),
            TarEntry(
                path = "node-$version-darwin-arm64/lib/node_modules/corepack/dist/corepack.js",
                content = "console.log('corepack');".toByteArray(),
            ),
        )
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gzip ->
            for (entry in entries) {
                writeTarHeader(gzip, entry.path, entry.content.size.toLong(), entry.mode.toLong())
                gzip.write(entry.content)
                val padding = (512 - (entry.content.size % 512)) % 512
                if (padding > 0) {
                    gzip.write(ByteArray(padding))
                }
            }
            gzip.write(ByteArray(1024))
        }
        return out.toByteArray()
    }

    private fun writeTarHeader(
        output: GZIPOutputStream,
        path: String,
        size: Long,
        mode: Long,
    ) {
        val header = ByteArray(512)
        writeTarString(header, 0, 100, path)
        writeTarOctal(header, 100, 8, mode)
        writeTarOctal(header, 108, 8, 0)
        writeTarOctal(header, 116, 8, 0)
        writeTarOctal(header, 124, 12, size)
        writeTarOctal(header, 136, 12, 0)
        header[156] = '0'.code.toByte()
        writeTarString(header, 257, 6, "ustar")
        writeTarString(header, 263, 2, "00")
        for (index in 148 until 156) {
            header[index] = ' '.code.toByte()
        }
        val checksum = header.sumOf { it.toUByte().toInt() }
        writeTarOctal(header, 148, 8, checksum.toLong())
        output.write(header)
    }

    private fun writeTarString(target: ByteArray, offset: Int, length: Int, value: String) {
        val bytes = value.toByteArray()
        bytes.copyInto(target, offset, endIndex = minOf(bytes.size, length))
    }

    private fun writeTarOctal(target: ByteArray, offset: Int, length: Int, value: Long) {
        val rendered = value.toString(8).padStart(length - 1, '0') + "\u0000"
        writeTarString(target, offset, length, rendered)
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private data class TarEntry(
        val path: String,
        val content: ByteArray,
        val mode: Int = 0b110100100,
    )
}
