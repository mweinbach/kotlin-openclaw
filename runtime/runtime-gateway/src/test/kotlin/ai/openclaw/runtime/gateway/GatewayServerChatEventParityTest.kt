package ai.openclaw.runtime.gateway

import ai.openclaw.core.model.AcpRuntimeEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GatewayServerChatEventParityTest {
    private fun adminContext(server: GatewayServer): RpcContext {
        return RpcContext(
            connectionId = "conn-1",
            authContext = AuthContext(
                connectionId = "conn-1",
                authenticated = true,
                authMode = AuthMode.NONE,
                scopes = setOf("admin"),
            ),
            gateway = server,
        )
    }

    private fun chatSendRequest(
        id: String,
        runId: String,
        sessionKey: String = "s1",
        message: String = "hello",
    ): GatewayRequestFrame {
        return GatewayRequestFrame(
            id = id,
            method = "chat.send",
            params = buildJsonObject {
                put("sessionKey", sessionKey)
                put("message", message)
                put("idempotencyKey", runId)
            },
        )
    }

    private fun GatewayServer.privateCollectionSize(fieldName: String): Int {
        val field = GatewayServer::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return when (val value = field.get(this)) {
            is Map<*, *> -> value.size
            is Collection<*> -> value.size
            else -> error("Unsupported field type for $fieldName: ${value?.javaClass?.name}")
        }
    }

    private suspend fun awaitRealtime(signal: CompletableDeferred<Unit>) {
        withContext(Dispatchers.IO) {
            withTimeout(2_000) { signal.await() }
        }
    }

    @Test
    fun `chat text delta payload uses state delta schema`() {
        val server = GatewayServer()
        val payload = server.buildChatEventPayload(
            event = AcpRuntimeEvent.TextDelta(text = "hello"),
            sessionKey = "session-1",
            runId = "req-1",
            seq = 1,
        )!!

        assertEquals("req-1", payload["runId"]?.jsonPrimitive?.content)
        assertEquals("session-1", payload["sessionKey"]?.jsonPrimitive?.content)
        assertEquals(1, payload["seq"]?.jsonPrimitive?.int)
        assertEquals("delta", payload["state"]?.jsonPrimitive?.content)
        val message = payload["message"]?.jsonObject
        assertNotNull(message)
        assertEquals("assistant", message["role"]?.jsonPrimitive?.content)
        val content = message["content"]?.jsonArray?.firstOrNull()?.jsonObject
        assertNotNull(content)
        assertEquals("text", content["type"]?.jsonPrimitive?.content)
        assertEquals("hello", content["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `chat done payload uses state final schema`() {
        val server = GatewayServer()
        val payload = server.buildChatEventPayload(
            event = AcpRuntimeEvent.Done(stopReason = "end_turn"),
            sessionKey = "session-1",
            runId = "req-1",
            seq = 2,
            bufferedAssistantText = "final text",
        )!!

        assertEquals("final", payload["state"]?.jsonPrimitive?.content)
        assertEquals("end_turn", payload["stopReason"]?.jsonPrimitive?.content)
        val message = payload["message"]?.jsonObject
        assertNotNull(message)
        val content = message["content"]?.jsonArray?.firstOrNull()?.jsonObject
        assertNotNull(content)
        assertEquals("final text", content["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `agent event payload preserves envelope fields`() {
        val server = GatewayServer()
        val payload = server.buildAgentEventPayload(
            runId = "req-1",
            sessionKey = "session-1",
            seq = 3,
            stream = "tool",
            data = kotlinx.serialization.json.buildJsonObject {
                put("phase", JsonPrimitive("start"))
                put("toolCallId", JsonPrimitive("call_1"))
            },
        )

        assertEquals("req-1", payload["runId"]?.jsonPrimitive?.content)
        assertEquals("session-1", payload["sessionKey"]?.jsonPrimitive?.content)
        assertEquals(3, payload["seq"]?.jsonPrimitive?.int)
        assertEquals("tool", payload["stream"]?.jsonPrimitive?.content)
        assertNotNull(payload["ts"]?.jsonPrimitive?.longOrNull)
        assertEquals("start", payload["data"]?.jsonObject?.get("phase")?.jsonPrimitive?.content)
    }

    @Test
    fun `tool update with output maps to result phase`() {
        val server = GatewayServer()
        val mapped = server.buildAgentStreamPayload(
            AcpRuntimeEvent.ToolCall(
                text = "Completed read",
                tag = "tool_call_update",
                toolCallId = "call_1",
                status = "completed",
                title = "read",
                rawOutput = """{"status":"ok"}""",
            ),
        )

        assertNotNull(mapped)
        assertEquals("tool", mapped.stream)
        assertEquals("result", mapped.data["phase"]?.jsonPrimitive?.content)
        assertEquals("call_1", mapped.data["toolCallId"]?.jsonPrimitive?.content)
        assertEquals(false, mapped.data["isError"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("ok", mapped.data["result"]?.jsonObject?.get("status")?.jsonPrimitive?.content)
    }

    @Test
    fun `tool payload can redact result fields`() {
        val server = GatewayServer()
        val mapped = server.buildAgentStreamPayload(
            event = AcpRuntimeEvent.ToolCall(
                text = "Completed read",
                tag = "tool_call_update",
                toolCallId = "call_1",
                status = "completed",
                title = "read",
                rawOutput = """{"status":"ok"}""",
            ),
            includeToolResults = false,
        )

        assertNotNull(mapped)
        assertEquals("result", mapped.data["phase"]?.jsonPrimitive?.content)
        assertEquals(null, mapped.data["result"])
        assertEquals(false, mapped.data["isError"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `lifecycle end payload includes pending tool calls`() {
        val server = GatewayServer()
        val mapped = server.buildAgentStreamPayload(
            AcpRuntimeEvent.Done(
                stopReason = "tool_calls",
                pendingToolCalls = listOf(
                    ai.openclaw.core.model.AcpPendingToolCall(
                        id = "call_1",
                        name = "browser_click",
                        arguments = """{"selector":"#submit"}""",
                    ),
                ),
            ),
        )

        assertNotNull(mapped)
        assertEquals("lifecycle", mapped.stream)
        assertEquals("end", mapped.data["phase"]?.jsonPrimitive?.content)
        val pending = mapped.data["pendingToolCalls"]?.jsonArray
        assertNotNull(pending)
        assertEquals("call_1", pending.first().jsonObject["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `chat send requires idempotency key`() = runTest {
        val server = GatewayServer()
        server.onChatSend = { flow { emit(AcpRuntimeEvent.Done(stopReason = "end_turn")) } }

        val response = server.dispatcher.dispatch(
            GatewayRequestFrame(
                id = "req-1",
                method = "chat.send",
                params = kotlinx.serialization.json.buildJsonObject {
                    put("sessionKey", "s1")
                    put("message", "hello")
                },
            ),
            adminContext(server),
        )

        assertEquals(false, response.ok)
        assertEquals("invalid_params", response.error?.code)
    }

    @Test
    fun `chat send duplicate idempotency key returns in flight`() = runTest {
        val server = GatewayServer()
        server.onChatSend = {
            flow {
                emit(AcpRuntimeEvent.TextDelta(text = "hi"))
                delay(250)
                emit(AcpRuntimeEvent.Done(stopReason = "end_turn"))
            }
        }
        val request = GatewayRequestFrame(
            id = "req-1",
            method = "chat.send",
            params = kotlinx.serialization.json.buildJsonObject {
                put("sessionKey", "s1")
                put("message", "hello")
                put("idempotencyKey", "run-1")
            },
        )

        val first = server.dispatcher.dispatch(
            request,
            adminContext(server),
        )
        val second = server.dispatcher.dispatch(
            request.copy(id = "req-2"),
            adminContext(server),
        )

        assertEquals(true, first.ok)
        assertEquals("run-1", first.payload?.jsonObject?.get("runId")?.jsonPrimitive?.content)
        assertEquals("started", first.payload?.jsonObject?.get("status")?.jsonPrimitive?.content)

        assertEquals(true, second.ok)
        assertEquals("run-1", second.payload?.jsonObject?.get("runId")?.jsonPrimitive?.content)
        assertEquals("in_flight", second.payload?.jsonObject?.get("status")?.jsonPrimitive?.content)
        server.stop()
    }

    @Test
    fun `stop clears chat state and later chat runs still start`() = runTest {
        val server = GatewayServer()
        val firstBlocked = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val invocations = AtomicInteger(0)
        server.onChatSend = {
            flow {
                when (invocations.incrementAndGet()) {
                    1 -> {
                        emit(AcpRuntimeEvent.TextDelta(text = "partial"))
                        withContext(NonCancellable) {
                            firstBlocked.complete(Unit)
                            releaseFirst.await()
                        }
                    }

                    2 -> {
                        secondStarted.complete(Unit)
                        emit(AcpRuntimeEvent.Done(stopReason = "end_turn"))
                    }

                    else -> error("unexpected chat invocation")
                }
            }
        }

        try {
            val first = server.dispatcher.dispatch(
                chatSendRequest(
                    id = "req-1",
                    runId = "run-1",
                ),
                adminContext(server),
            )

            assertEquals(true, first.ok)
            assertEquals("run-1", first.payload?.jsonObject?.get("runId")?.jsonPrimitive?.content)
            assertEquals("started", first.payload?.jsonObject?.get("status")?.jsonPrimitive?.content)
            awaitRealtime(firstBlocked)

            assertTrue(server.privateCollectionSize("activeChatJobs") > 0)
            assertTrue(server.privateCollectionSize("activeChatSessions") > 0)
            assertTrue(server.privateCollectionSize("activeChatConnections") > 0)
            assertTrue(server.privateCollectionSize("chatBuffers") > 0)
            assertTrue(server.privateCollectionSize("chatSeqByRun") > 0)

            server.stop()

            assertEquals(0, server.privateCollectionSize("activeChatJobs"))
            assertEquals(0, server.privateCollectionSize("activeChatSessions"))
            assertEquals(0, server.privateCollectionSize("activeChatConnections"))
            assertEquals(0, server.privateCollectionSize("chatBuffers"))
            assertEquals(0, server.privateCollectionSize("chatSeqByRun"))

            val second = server.dispatcher.dispatch(
                chatSendRequest(
                    id = "req-2",
                    runId = "run-2",
                ),
                adminContext(server),
            )

            assertEquals(true, second.ok)
            assertEquals("run-2", second.payload?.jsonObject?.get("runId")?.jsonPrimitive?.content)
            assertEquals("started", second.payload?.jsonObject?.get("status")?.jsonPrimitive?.content)
            awaitRealtime(secondStarted)
        } finally {
            releaseFirst.complete(Unit)
            server.stop()
        }
    }

    @Test
    fun `chat abort response uses ok aborted and runIds contract`() = runTest {
        val server = GatewayServer()
        val response = server.dispatcher.dispatch(
            GatewayRequestFrame(
                id = "req-1",
                method = "chat.abort",
                params = kotlinx.serialization.json.buildJsonObject {
                    put("sessionKey", "s1")
                },
            ),
            adminContext(server),
        )

        assertEquals(true, response.ok)
        assertEquals(true, response.payload?.jsonObject?.get("ok")?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, response.payload?.jsonObject?.get("aborted")?.jsonPrimitive?.booleanOrNull)
        assertTrue(response.payload?.jsonObject?.get("runIds")?.jsonArray?.isEmpty() == true)
    }

    @Test
    fun `chat aborted payload includes abort origin`() {
        val server = GatewayServer()
        val payload = server.buildChatAbortedPayload(
            runId = "run-1",
            sessionKey = "session-1",
            seq = 3,
            text = "partial text",
            stopReason = "aborted",
            origin = "rpc",
        )
        assertEquals("aborted", payload["state"]?.jsonPrimitive?.content)
        assertEquals("rpc", payload["origin"]?.jsonPrimitive?.content)
    }

    @Test
    fun `chat abort rejects runId session mismatch`() = runTest {
        val server = GatewayServer()
        server.onChatSend = {
            flow {
                delay(500)
                emit(AcpRuntimeEvent.Done(stopReason = "end_turn"))
            }
        }

        server.dispatcher.dispatch(
            GatewayRequestFrame(
                id = "req-send",
                method = "chat.send",
                params = kotlinx.serialization.json.buildJsonObject {
                    put("sessionKey", "s1")
                    put("message", "hello")
                    put("idempotencyKey", "run-1")
                },
            ),
            adminContext(server),
        )

        val abort = server.dispatcher.dispatch(
            GatewayRequestFrame(
                id = "req-abort",
                method = "chat.abort",
                params = kotlinx.serialization.json.buildJsonObject {
                    put("sessionKey", "s2")
                    put("runId", "run-1")
                },
            ),
            adminContext(server),
        )

        assertEquals(false, abort.ok)
        assertEquals("invalid_request", abort.error?.code)
        server.stop()
    }
}
