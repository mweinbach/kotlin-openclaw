package ai.openclaw.core.security

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GatewayOriginPolicyTest {
    @Test
    fun `allows explicit configured origin`() {
        val policy = GatewayOriginPolicy(
            GatewayOriginPolicyConfig(
                allowedOrigins = listOf("https://app.example.com"),
            ),
        )
        assertTrue(
            policy.isAllowed(
                originHeader = "https://app.example.com",
                hostHeader = "gateway.example.com",
                remoteHost = "203.0.113.2",
            ),
        )
    }

    @Test
    fun `rejects unknown non-loopback origin`() {
        val policy = GatewayOriginPolicy(
            GatewayOriginPolicyConfig(
                allowedOrigins = listOf("https://app.example.com"),
            ),
        )
        assertFalse(
            policy.isAllowed(
                originHeader = "https://evil.example.com",
                hostHeader = "gateway.example.com",
                remoteHost = "203.0.113.2",
            ),
        )
    }
}
