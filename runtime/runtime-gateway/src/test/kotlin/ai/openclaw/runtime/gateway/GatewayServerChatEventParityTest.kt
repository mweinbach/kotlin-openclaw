package ai.openclaw.runtime.gateway

import ai.openclaw.core.model.AcpRuntimeEvent
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals

class GatewayServerChatEventParityTest {
    @Test
    fun `tool call payload includes lifecycle metadata`() {
        val server = GatewayServer()
        val payload = server.buildChatEventPayload(
            event = AcpRuntimeEvent.ToolCall(
                text = "Completed read",
                tag = "tool_call_update",
                toolCallId = "call_1",
                status = "completed",
                title = "read",
            ),
            sessionKey = "session-1",
            requestId = "req-1",
        )

        assertEquals("tool_call", payload["type"]?.jsonPrimitive?.content)
        assertEquals("tool_call_update", payload["tag"]?.jsonPrimitive?.content)
        assertEquals("call_1", payload["toolCallId"]?.jsonPrimitive?.content)
        assertEquals("completed", payload["status"]?.jsonPrimitive?.content)
        assertEquals("read", payload["title"]?.jsonPrimitive?.content)
        assertEquals("read | status=completed | Completed read", payload["detail"]?.jsonPrimitive?.content)
    }

    @Test
    fun `status payload preserves status tag and token metadata`() {
        val server = GatewayServer()
        val payload = server.buildChatEventPayload(
            event = AcpRuntimeEvent.Status(
                text = "Tokens: 10+5",
                tag = "usage_update",
                used = 15,
                size = 128,
            ),
            sessionKey = "session-1",
            requestId = "req-1",
        )

        assertEquals("status", payload["type"]?.jsonPrimitive?.content)
        assertEquals("usage_update", payload["tag"]?.jsonPrimitive?.content)
        assertEquals(15, payload["used"]?.jsonPrimitive?.int)
        assertEquals(128, payload["size"]?.jsonPrimitive?.int)
    }
}
