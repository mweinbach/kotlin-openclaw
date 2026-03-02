package ai.openclaw.runtime.discovery

import ai.openclaw.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Mock NSD platform for testing without Android framework.
 */
class MockNsdPlatform : NsdPlatform {
    var lastRegisteredServiceType: String? = null
    var lastRegisteredServiceName: String? = null
    var lastRegisteredPort: Int? = null
    var registrationCallback: NsdPlatform.RegistrationCallback? = null
    var discoveryCallback: NsdPlatform.DiscoveryCallback? = null

    override fun registerService(
        serviceType: String,
        serviceName: String,
        port: Int,
        txtRecords: Map<String, String>,
        callback: NsdPlatform.RegistrationCallback,
    ): NsdPlatform.RegistrationHandle {
        lastRegisteredServiceType = serviceType
        lastRegisteredServiceName = serviceName
        lastRegisteredPort = port
        registrationCallback = callback

        // Simulate successful registration
        callback.onRegistered(serviceName)

        return object : NsdPlatform.RegistrationHandle {
            override fun unregister() {
                callback.onUnregistered()
            }
        }
    }

    override fun discoverServices(
        serviceType: String,
        callback: NsdPlatform.DiscoveryCallback,
    ): NsdPlatform.DiscoveryHandle {
        discoveryCallback = callback
        callback.onDiscoveryStarted()

        return object : NsdPlatform.DiscoveryHandle {
            override fun stop() {
                callback.onDiscoveryStopped()
            }
        }
    }

    fun simulateServiceFound(name: String, host: String, port: Int, txtRecords: Map<String, String> = emptyMap()) {
        discoveryCallback?.onServiceFound(NsdPlatform.ServiceInfo(
            serviceName = name,
            serviceType = DiscoveryManager.SERVICE_TYPE,
            host = host,
            port = port,
            txtRecords = txtRecords,
        ))
    }

    fun simulateServiceLost(name: String) {
        discoveryCallback?.onServiceLost(name)
    }
}

class DiscoveryManagerTest {

    private fun fullDiscoveryConfig() = DiscoveryConfig(
        mdns = MdnsDiscoveryConfig(mode = MdnsDiscoveryMode.FULL),
    )

    private fun minimalDiscoveryConfig() = DiscoveryConfig(
        mdns = MdnsDiscoveryConfig(mode = MdnsDiscoveryMode.MINIMAL),
    )

    private fun offDiscoveryConfig() = DiscoveryConfig(
        mdns = MdnsDiscoveryConfig(mode = MdnsDiscoveryMode.OFF),
    )

    @Test
    fun `does not start when mode is OFF`() {
        val platform = MockNsdPlatform()
        val manager = DiscoveryManager(offDiscoveryConfig(), platform, instanceId = "test-id")

        manager.start("openclaw", 18789)

        assertFalse(manager.isRunning)
        assertEquals(null, platform.lastRegisteredServiceType)
    }

    @Test
    fun `registers service in MINIMAL mode`() {
        val platform = MockNsdPlatform()
        val manager = DiscoveryManager(minimalDiscoveryConfig(), platform, instanceId = "test-id")

        manager.start("openclaw", 18789)

        assertTrue(manager.isRunning)
        assertEquals(DiscoveryManager.SERVICE_TYPE, platform.lastRegisteredServiceType)
        assertEquals("openclaw", platform.lastRegisteredServiceName)
        assertEquals(18789, platform.lastRegisteredPort)
        // In MINIMAL mode, no discovery should be started
        assertEquals(null, platform.discoveryCallback)
    }

    @Test
    fun `registers and discovers in FULL mode`() {
        val platform = MockNsdPlatform()
        val manager = DiscoveryManager(fullDiscoveryConfig(), platform, instanceId = "test-id")

        manager.start("openclaw", 18789)

        assertTrue(manager.isRunning)
        assertEquals(DiscoveryManager.SERVICE_TYPE, platform.lastRegisteredServiceType)
        // Discovery callback should be set
        assertTrue(platform.discoveryCallback != null)
    }

    @Test
    fun `discovers peers and updates state`() = runTest {
        val platform = MockNsdPlatform()
        val manager = DiscoveryManager(fullDiscoveryConfig(), platform, instanceId = "my-id")

        manager.start("openclaw", 18789)

        // Simulate finding a peer
        platform.simulateServiceFound(
            name = "peer-openclaw",
            host = "192.168.1.100",
            port = 18789,
            txtRecords = mapOf("instanceId" to "peer-id"),
        )

        // Give coroutine time to process
        delay(50)

        val peers = manager.discoveredPeers.value
        assertEquals(1, peers.size)
        val peer = peers["peer-id"]!!
        assertEquals("peer-openclaw", peer.name)
        assertEquals("192.168.1.100", peer.host)
        assertEquals(18789, peer.port)
    }

    @Test
    fun `removes peer when service lost`() = runTest {
        val platform = MockNsdPlatform()
        val manager = DiscoveryManager(fullDiscoveryConfig(), platform, instanceId = "my-id")

        manager.start("openclaw", 18789)

        platform.simulateServiceFound(
            name = "peer-openclaw",
            host = "192.168.1.100",
            port = 18789,
            txtRecords = mapOf("instanceId" to "peer-id"),
        )
        delay(50)
        assertEquals(1, manager.discoveredPeers.value.size)

        platform.simulateServiceLost("peer-openclaw")
        delay(50)
        assertEquals(0, manager.discoveredPeers.value.size)
    }

    @Test
    fun `ignores own service in discovery`() = runTest {
        val platform = MockNsdPlatform()
        val manager = DiscoveryManager(fullDiscoveryConfig(), platform, instanceId = "my-id")

        manager.start("openclaw", 18789)

        // Simulate finding our own instance
        platform.simulateServiceFound(
            name = "my-openclaw",
            host = "127.0.0.1",
            port = 18789,
            txtRecords = mapOf("instanceId" to "my-id"),
        )

        delay(50)
        assertEquals(0, manager.discoveredPeers.value.size)
    }

    @Test
    fun `stop clears state`() {
        val platform = MockNsdPlatform()
        val manager = DiscoveryManager(fullDiscoveryConfig(), platform, instanceId = "test-id")

        manager.start("openclaw", 18789)
        assertTrue(manager.isRunning)

        manager.stop()
        assertFalse(manager.isRunning)
        assertTrue(manager.discoveredPeers.value.isEmpty())
    }

    // --- Pairing Tests ---

    @Test
    fun `generatePairingToken returns 64 hex chars`() {
        val platform = MockNsdPlatform()
        val manager = DiscoveryManager(fullDiscoveryConfig(), platform)
        val token = manager.generatePairingToken()
        assertEquals(64, token.length)
        assertTrue(token.all { it in "0123456789abcdef" })
    }

    @Test
    fun `device pairing lifecycle`() {
        val platform = MockNsdPlatform()
        val manager = DiscoveryManager(fullDiscoveryConfig(), platform)

        assertFalse(manager.isPaired("peer-1"))

        val token = manager.generatePairingToken()
        manager.registerPairedDevice("peer-1", "Peer One", token)

        assertTrue(manager.isPaired("peer-1"))
        assertTrue(manager.validatePairingToken("peer-1", token))
        assertFalse(manager.validatePairingToken("peer-1", "wrong-token"))

        val devices = manager.getPairedDevices()
        assertEquals(1, devices.size)
        assertEquals("Peer One", devices[0].name)

        manager.unpairDevice("peer-1")
        assertFalse(manager.isPaired("peer-1"))
    }
}
