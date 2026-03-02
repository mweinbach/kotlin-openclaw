package ai.openclaw.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class AcpTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Test
    fun `serialize and deserialize AcpRuntimeEvent TextDelta`() {
        val event: AcpRuntimeEvent = AcpRuntimeEvent.TextDelta(text = "Hello", stream = AcpRuntimeEvent.StreamType.OUTPUT)
        val encoded = json.encodeToString(event)
        val decoded = json.decodeFromString<AcpRuntimeEvent>(encoded)
        assertEquals(event, decoded)
    }

    @Test
    fun `serialize and deserialize AcpRuntimeEvent Done`() {
        val event: AcpRuntimeEvent = AcpRuntimeEvent.Done(stopReason = "end_turn")
        val encoded = json.encodeToString(event)
        val decoded = json.decodeFromString<AcpRuntimeEvent>(encoded)
        assertEquals(event, decoded)
    }

    @Test
    fun `serialize and deserialize AcpRuntimeEvent Error`() {
        val event: AcpRuntimeEvent = AcpRuntimeEvent.Error(message = "Rate limited", code = "429", retryable = true)
        val encoded = json.encodeToString(event)
        val decoded = json.decodeFromString<AcpRuntimeEvent>(encoded)
        assertEquals(event, decoded)
    }

    @Test
    fun `serialize and deserialize AcpRuntimeHandle`() {
        val handle = AcpRuntimeHandle(
            sessionKey = "agent:default:test",
            backend = "acpx",
            runtimeSessionName = "test-session",
            cwd = "/tmp",
        )
        val encoded = json.encodeToString(handle)
        val decoded = json.decodeFromString<AcpRuntimeHandle>(encoded)
        assertEquals(handle, decoded)
    }

    @Test
    fun `serialize and deserialize AcpRuntimeEnsureInput`() {
        val input = AcpRuntimeEnsureInput(
            sessionKey = "agent:default:test",
            agent = "default",
            mode = AcpRuntimeSessionMode.PERSISTENT,
            env = mapOf("API_KEY" to "test"),
        )
        val encoded = json.encodeToString(input)
        val decoded = json.decodeFromString<AcpRuntimeEnsureInput>(encoded)
        assertEquals(input, decoded)
    }
}
