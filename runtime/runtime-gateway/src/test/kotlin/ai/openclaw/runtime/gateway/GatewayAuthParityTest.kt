package ai.openclaw.runtime.gateway

import ai.openclaw.core.model.GatewayAuthConfig
import ai.openclaw.core.model.GatewayAuthMode
import ai.openclaw.core.model.GatewayTrustedProxyConfig
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GatewayAuthParityTest {
    @Test
    fun `token mode without token is denied as misconfigured`() {
        val authenticator = GatewayAuthenticator(
            GatewayAuthConfig(
                mode = GatewayAuthMode.TOKEN,
                token = null,
            ),
        )
        val result = authenticator.authenticate(
            ConnectParams(
                connectionId = "conn-1",
                token = "anything",
                ip = "127.0.0.1",
            ),
        )
        assertTrue(result is AuthResult.Denied)
        result as AuthResult.Denied
        assertEquals(AuthMode.TOKEN, result.mode)
        assertTrue(result.reason.contains("requires gateway.auth.token"))
    }

    @Test
    fun `trusted proxy auth validates headers and user allowlist`() {
        val authenticator = GatewayAuthenticator(
            GatewayAuthConfig(
                mode = GatewayAuthMode.TRUSTED_PROXY,
                trustedProxy = GatewayTrustedProxyConfig(
                    userHeader = "x-user",
                    requiredHeaders = listOf("x-forwarded-for"),
                    allowUsers = listOf("alice"),
                ),
            ),
        )
        val allowed = authenticator.authenticate(
            ConnectParams(
                connectionId = "conn-1",
                ip = "10.1.2.3",
                headers = mapOf(
                    "x-user" to "alice",
                    "x-forwarded-for" to "203.0.113.10",
                ),
            ),
        )
        assertTrue(allowed is AuthResult.Allowed)
        val denied = authenticator.authenticate(
            ConnectParams(
                connectionId = "conn-2",
                ip = "10.1.2.3",
                headers = mapOf(
                    "x-user" to "mallory",
                    "x-forwarded-for" to "203.0.113.10",
                ),
            ),
        )
        assertTrue(denied is AuthResult.Denied)
    }

    @Test
    fun `device token can be issued and used for auth`() {
        val authenticator = GatewayAuthenticator(
            GatewayAuthConfig(mode = GatewayAuthMode.NONE),
        )
        val issued = authenticator.issueDeviceToken(
            deviceId = "android-test",
            role = "operator",
            scopes = setOf("operator.chat.send"),
        )
        val result = authenticator.authenticate(
            ConnectParams(
                connectionId = "conn-1",
                deviceToken = issued.token,
                ip = "198.51.100.5",
            ),
        )
        assertTrue(result is AuthResult.Allowed)
        result as AuthResult.Allowed
        assertEquals(AuthMode.DEVICE, result.context.authMode)
        assertTrue(result.context.hasScope("operator.chat.send"))
        assertFalse(result.context.hasScope("operator.config.read"))
    }
}
