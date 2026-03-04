package ai.openclaw.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class GatewayProtocolTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Test
    fun `serialize gateway connect payload`() {
        val payload = GatewayConnectPayload(
            nonce = "nonce-1",
            minProtocol = 7,
            maxProtocol = 7,
            role = "operator",
            auth = GatewayConnectAuthPayload(
                deviceToken = "device-token",
                scopes = listOf("operator.chat.send"),
            ),
            device = GatewayConnectDevicePayload(
                id = "device-1",
                publicKey = "pub",
                signature = "sig",
            ),
        )
        val encoded = json.encodeToString(payload)
        val decoded = json.decodeFromString<GatewayConnectPayload>(encoded)
        assertEquals(payload, decoded)
    }
}
