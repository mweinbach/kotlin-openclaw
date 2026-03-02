package ai.openclaw.core.session

import ai.openclaw.core.model.*
import kotlin.test.Test
import kotlin.test.assertEquals

class SendPolicyTest {

    @Test
    fun `allow by default when no policy configured`() {
        val config = OpenClawConfig()
        assertEquals(SendPolicyDecision.ALLOW, resolveSendPolicy(config))
    }

    @Test
    fun `session-level override wins`() {
        val config = OpenClawConfig(
            session = SessionConfig(
                sendPolicy = SessionSendPolicyConfig(default = SendPolicyDecision.ALLOW)
            )
        )
        val entry = SessionEntry(
            sessionId = "test",
            updatedAt = 0L,
            sendPolicy = SendPolicyDecision.DENY,
        )
        assertEquals(SendPolicyDecision.DENY, resolveSendPolicy(config, entry))
    }

    @Test
    fun `deny rule matches by channel`() {
        val config = OpenClawConfig(
            session = SessionConfig(
                sendPolicy = SessionSendPolicyConfig(
                    rules = listOf(
                        SessionSendPolicyRule(
                            action = SendPolicyDecision.DENY,
                            match = SessionSendPolicyMatch(channel = "telegram"),
                        )
                    )
                )
            )
        )
        assertEquals(
            SendPolicyDecision.DENY,
            resolveSendPolicy(config, channel = "telegram")
        )
        assertEquals(
            SendPolicyDecision.ALLOW,
            resolveSendPolicy(config, channel = "discord")
        )
    }

    @Test
    fun `policy default fallback`() {
        val config = OpenClawConfig(
            session = SessionConfig(
                sendPolicy = SessionSendPolicyConfig(
                    default = SendPolicyDecision.DENY,
                    rules = listOf(
                        SessionSendPolicyRule(
                            action = SendPolicyDecision.ALLOW,
                            match = SessionSendPolicyMatch(channel = "telegram"),
                        )
                    )
                )
            )
        )
        assertEquals(
            SendPolicyDecision.ALLOW,
            resolveSendPolicy(config, channel = "telegram")
        )
        assertEquals(
            SendPolicyDecision.DENY,
            resolveSendPolicy(config, channel = "discord")
        )
    }

    @Test
    fun `normalize send policy strings`() {
        assertEquals(SendPolicyDecision.ALLOW, normalizeSendPolicy("allow"))
        assertEquals(SendPolicyDecision.ALLOW, normalizeSendPolicy(" Allow "))
        assertEquals(SendPolicyDecision.DENY, normalizeSendPolicy("deny"))
        assertEquals(null, normalizeSendPolicy("invalid"))
        assertEquals(null, normalizeSendPolicy(null))
    }
}
