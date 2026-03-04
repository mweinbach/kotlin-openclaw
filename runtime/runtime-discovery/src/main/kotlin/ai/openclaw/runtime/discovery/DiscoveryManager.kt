package ai.openclaw.runtime.discovery

import ai.openclaw.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * mDNS-based service discovery for OpenClaw instances on the local network.
 * Uses Android NsdManager (Network Service Discovery) to register and discover
 * `_openclaw._tcp` services.
 *
 * This class is platform-abstracted: it delegates to a [NsdPlatform] interface
 * so it can be tested without Android framework classes.
 *
 * Ported from TypeScript discovery patterns in the gateway module.
 */
class DiscoveryManager(
    private val config: DiscoveryConfig?,
    private val platform: NsdPlatform,
    private val instanceId: String = generateInstanceId(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _discoveredPeers = MutableStateFlow<Map<String, DiscoveredPeer>>(emptyMap())
    val discoveredPeers: StateFlow<Map<String, DiscoveredPeer>> = _discoveredPeers.asStateFlow()

    private val _events = MutableSharedFlow<DiscoveryEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<DiscoveryEvent> = _events.asSharedFlow()

    private val pairedDevices = ConcurrentHashMap<String, PairedDevice>()

    private var registrationHandle: NsdPlatform.RegistrationHandle? = null
    private var discoveryHandle: NsdPlatform.DiscoveryHandle? = null
    private var running = false

    val isRunning: Boolean get() = running

    /**
     * Start registration and discovery based on config.
     */
    fun start(serviceName: String, port: Int, metadata: Map<String, String> = emptyMap()) {
        if (running) return
        val mdnsMode = config?.mdns?.mode ?: MdnsDiscoveryMode.OFF
        if (mdnsMode == MdnsDiscoveryMode.OFF) return

        running = true

        // Register this instance
        val fullMeta = metadata.toMutableMap().apply {
            put("instanceId", instanceId)
            put("mode", mdnsMode.name.lowercase())
        }

        registrationHandle = platform.registerService(
            serviceType = SERVICE_TYPE,
            serviceName = serviceName,
            port = port,
            txtRecords = fullMeta,
            callback = object : NsdPlatform.RegistrationCallback {
                override fun onRegistered(actualName: String) {
                    scope.launch {
                        _events.emit(DiscoveryEvent.Registered(actualName, port))
                    }
                }

                override fun onRegistrationFailed(errorCode: Int) {
                    scope.launch {
                        _events.emit(DiscoveryEvent.Error("Registration failed: code $errorCode"))
                    }
                }

                override fun onUnregistered() {
                    scope.launch {
                        _events.emit(DiscoveryEvent.Unregistered)
                    }
                }
            },
        )

        // Start discovery if mode is FULL
        if (mdnsMode == MdnsDiscoveryMode.FULL) {
            startDiscovery()
        }
    }

    /**
     * Start discovering other OpenClaw instances.
     */
    private fun startDiscovery() {
        discoveryHandle = platform.discoverServices(
            serviceType = SERVICE_TYPE,
            callback = object : NsdPlatform.DiscoveryCallback {
                override fun onServiceFound(info: NsdPlatform.ServiceInfo) {
                    // Skip our own instance
                    val peerId = info.txtRecords["instanceId"] ?: info.serviceName
                    if (peerId == instanceId) return

                    val peer = DiscoveredPeer(
                        id = peerId,
                        name = info.serviceName,
                        host = info.host,
                        port = info.port,
                        metadata = info.txtRecords,
                        discoveredAt = System.currentTimeMillis(),
                    )
                    _discoveredPeers.value = _discoveredPeers.value + (peerId to peer)
                    scope.launch {
                        _events.emit(DiscoveryEvent.PeerFound(peer))
                    }
                }

                override fun onServiceLost(serviceName: String) {
                    val peers = _discoveredPeers.value.toMutableMap()
                    val removed = peers.entries.firstOrNull { it.value.name == serviceName }
                    if (removed != null) {
                        peers.remove(removed.key)
                        _discoveredPeers.value = peers
                        scope.launch {
                            _events.emit(DiscoveryEvent.PeerLost(removed.value))
                        }
                    }
                }

                override fun onDiscoveryStarted() {
                    scope.launch {
                        _events.emit(DiscoveryEvent.DiscoveryStarted)
                    }
                }

                override fun onDiscoveryStopped() {
                    scope.launch {
                        _events.emit(DiscoveryEvent.DiscoveryStopped)
                    }
                }

                override fun onDiscoveryFailed(errorCode: Int) {
                    scope.launch {
                        _events.emit(DiscoveryEvent.Error("Discovery failed: code $errorCode"))
                    }
                }
            },
        )
    }

    /**
     * Stop all discovery and registration.
     */
    fun stop() {
        if (!running) return
        running = false

        registrationHandle?.unregister()
        registrationHandle = null

        discoveryHandle?.stop()
        discoveryHandle = null

        _discoveredPeers.value = emptyMap()
    }

    // --- Device Pairing ---

    /**
     * Generate a pairing token that can be exchanged with a discovered peer.
     */
    fun generatePairingToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Register a paired device after token exchange.
     */
    fun registerPairedDevice(peerId: String, peerName: String, sharedToken: String) {
        pairedDevices[peerId] = PairedDevice(
            id = peerId,
            name = peerName,
            token = sharedToken,
            pairedAt = System.currentTimeMillis(),
        )
        scope.launch {
            _events.emit(DiscoveryEvent.DevicePaired(peerId, peerName))
        }
    }

    /**
     * Check if a device is paired.
     */
    fun isPaired(peerId: String): Boolean = pairedDevices.containsKey(peerId)

    /**
     * Validate a pairing token for a peer.
     */
    fun validatePairingToken(peerId: String, token: String): Boolean {
        val paired = pairedDevices[peerId] ?: return false
        return paired.token == token
    }

    /**
     * Get all paired devices.
     */
    fun getPairedDevices(): List<PairedDevice> = pairedDevices.values.toList()

    /**
     * Remove a paired device.
     */
    fun unpairDevice(peerId: String) {
        pairedDevices.remove(peerId)
    }

    companion object {
        const val SERVICE_TYPE = "_openclaw._tcp."

        private fun generateInstanceId(): String {
            val bytes = ByteArray(8)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * A discovered peer on the local network.
 */
data class DiscoveredPeer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val metadata: Map<String, String> = emptyMap(),
    val discoveredAt: Long = System.currentTimeMillis(),
)

/**
 * A device that has been paired via token exchange.
 */
data class PairedDevice(
    val id: String,
    val name: String,
    val token: String,
    val pairedAt: Long = System.currentTimeMillis(),
)

/**
 * Events emitted by the discovery manager.
 */
sealed class DiscoveryEvent {
    data class Registered(val name: String, val port: Int) : DiscoveryEvent()
    data object Unregistered : DiscoveryEvent()
    data object DiscoveryStarted : DiscoveryEvent()
    data object DiscoveryStopped : DiscoveryEvent()
    data class PeerFound(val peer: DiscoveredPeer) : DiscoveryEvent()
    data class PeerLost(val peer: DiscoveredPeer) : DiscoveryEvent()
    data class DevicePaired(val peerId: String, val peerName: String) : DiscoveryEvent()
    data class Error(val message: String) : DiscoveryEvent()
}

/**
 * Platform abstraction for NSD (Network Service Discovery).
 * On Android, implement using NsdManager.
 * For testing, use a mock implementation.
 */
interface NsdPlatform {
    data class ServiceInfo(
        val serviceName: String,
        val serviceType: String,
        val host: String,
        val port: Int,
        val txtRecords: Map<String, String> = emptyMap(),
    )

    interface RegistrationCallback {
        fun onRegistered(actualName: String)
        fun onRegistrationFailed(errorCode: Int)
        fun onUnregistered()
    }

    interface DiscoveryCallback {
        fun onServiceFound(info: ServiceInfo)
        fun onServiceLost(serviceName: String)
        fun onDiscoveryStarted()
        fun onDiscoveryStopped()
        fun onDiscoveryFailed(errorCode: Int)
    }

    interface RegistrationHandle {
        fun unregister()
    }

    interface DiscoveryHandle {
        fun stop()
    }

    fun registerService(
        serviceType: String,
        serviceName: String,
        port: Int,
        txtRecords: Map<String, String>,
        callback: RegistrationCallback,
    ): RegistrationHandle

    fun discoverServices(
        serviceType: String,
        callback: DiscoveryCallback,
    ): DiscoveryHandle
}
