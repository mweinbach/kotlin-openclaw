package ai.openclaw.core.model

import kotlinx.serialization.Serializable

@Serializable
data class GatewayConnectAuthPayload(
    val token: String? = null,
    val password: String? = null,
    val deviceToken: String? = null,
    val scopes: List<String>? = null,
)

@Serializable
data class GatewayConnectDevicePayload(
    val id: String? = null,
    val publicKey: String? = null,
    val signature: String? = null,
    val signedAt: Long? = null,
)

@Serializable
data class GatewayConnectPayload(
    val nonce: String? = null,
    val minProtocol: Int? = null,
    val maxProtocol: Int? = null,
    val role: String? = null,
    val auth: GatewayConnectAuthPayload? = null,
    val device: GatewayConnectDevicePayload? = null,
)

@Serializable
data class GatewayPairingRequest(
    val deviceId: String,
    val role: String? = null,
    val scopes: List<String>? = null,
    val ttlMs: Long? = null,
)

@Serializable
data class GatewayPairingToken(
    val token: String,
    val deviceId: String,
    val role: String? = null,
    val scopes: List<String> = emptyList(),
    val expiresAt: Long? = null,
)
