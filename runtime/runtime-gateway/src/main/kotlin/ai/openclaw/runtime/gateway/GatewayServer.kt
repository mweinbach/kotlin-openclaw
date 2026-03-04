package ai.openclaw.runtime.gateway

import ai.openclaw.core.model.*
import ai.openclaw.core.security.AuditLog
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Embedded Ktor-based gateway server.
 * Provides WebSocket + REST endpoints for agent communication.
 *
 * Ported from src/gateway/server.impl.ts
 */
class GatewayServer(
    private val port: Int = 18789,
    private val host: String = "127.0.0.1",
    private val config: OpenClawConfig? = null,
) {
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        isLenient = true
    }

    val dispatcher = RpcDispatcher()
    val authenticator = GatewayAuthenticator(config?.gateway?.auth)
    val channelManager = ChannelManager()
    val auditLog = AuditLog()
    val webhookManager = WebhookManager(config?.hooks, config, auditLog, json)

    // Connected WebSocket sessions
    private val connections = ConcurrentHashMap<String, WsConnection>()

    data class WsConnection(
        val id: String,
        val session: DefaultWebSocketServerSession,
        val authContext: AuthContext?,
        val connectedAt: Long = System.currentTimeMillis(),
    )

    // --- Chat run callback ---

    var onChatSend: (suspend (ChatSendParams) -> Flow<AcpRuntimeEvent>)? = null

    data class ChatSendParams(
        val sessionKey: String,
        val agentId: String?,
        val text: String,
        val channel: String?,
        val accountId: String?,
        val to: String?,
        val model: String?,
        val deliver: Boolean,
        val requestId: String,
    )

    // Managed coroutine scope for background work (chat streaming, etc.)
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        registerCoreMethods()
    }

    // --- Lifecycle ---

    fun start() {
        server = embeddedServer(Netty, port = port, host = host) {
            install(ContentNegotiation) {
                json(json)
            }
            install(WebSockets)

            routing {
                configureRestRoutes()
                configureWebSocketRoute()
                with(webhookManager) { configureWebhookRoutes() }
            }
        }
        server?.start(wait = false)
    }

    fun stop() {
        serverScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        connections.clear()
        server?.stop(1000, 5000)
        server = null
    }

    val isRunning: Boolean get() = server != null

    // --- REST Routes ---

    private fun Routing.configureRestRoutes() {
        get("/health") {
            call.respondText(
                json.encodeToString(JsonObject.serializer(), buildJsonObject {
                    put("status", "ok")
                    put("uptime", System.currentTimeMillis())
                    put("connections", connections.size)
                }),
                ContentType.Application.Json,
            )
        }

        get("/status") {
            call.respondText(
                json.encodeToString(JsonObject.serializer(), buildJsonObject {
                    put("status", "ok")
                    put("channels", json.encodeToJsonElement(channelManager.getSnapshot().map { (k, v) ->
                        buildJsonObject {
                            put("key", k)
                            put("channelId", v.channelId)
                            put("status", v.status)
                        }
                    }))
                    put("connections", connections.size)
                }),
                ContentType.Application.Json,
            )
        }

        // OpenAI-compatible chat completions endpoint
        post("/v1/chat/completions") {
            val body = call.receiveText()
            val request = try {
                json.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                call.respondText(
                    """{"error":{"message":"Invalid JSON","type":"invalid_request_error"}}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }

            val messages = request["messages"]?.jsonArray
            val model = request["model"]?.jsonPrimitive?.contentOrNull
            val lastMessage = messages?.lastOrNull()?.jsonObject
            val text = lastMessage?.get("content")?.jsonPrimitive?.contentOrNull ?: ""

            val chatHandler = onChatSend
            if (chatHandler == null) {
                call.respondText(
                    """{"error":{"message":"Agent not configured","type":"server_error"}}""",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError,
                )
                return@post
            }

            val requestId = UUID.randomUUID().toString()
            val sessionKey = "http:$requestId"
            val responseText = StringBuilder()

            try {
                chatHandler(ChatSendParams(
                    sessionKey = sessionKey,
                    agentId = null,
                    text = text,
                    channel = "http",
                    accountId = "default",
                    to = null,
                    model = model,
                    deliver = false,
                    requestId = requestId,
                )).collect { event ->
                    when (event) {
                        is AcpRuntimeEvent.TextDelta -> responseText.append(event.text)
                        else -> {} // Ignore non-text events for REST
                    }
                }

                call.respondText(
                    json.encodeToString(JsonObject.serializer(), buildJsonObject {
                        put("id", "chatcmpl-$requestId")
                        put("object", "chat.completion")
                        put("model", model ?: "default")
                        putJsonArray("choices") {
                            addJsonObject {
                                put("index", 0)
                                putJsonObject("message") {
                                    put("role", "assistant")
                                    put("content", responseText.toString())
                                }
                                put("finish_reason", "stop")
                            }
                        }
                    }),
                    ContentType.Application.Json,
                )
            } catch (e: Exception) {
                call.respondText(
                    json.encodeToString(JsonObject.serializer(), buildJsonObject {
                        putJsonObject("error") {
                            put("message", e.message ?: "Internal error")
                            put("type", "server_error")
                        }
                    }),
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError,
                )
            }
        }
    }

    // --- WebSocket Route ---

    private fun Routing.configureWebSocketRoute() {
        webSocket("/ws") {
            val connId = UUID.randomUUID().toString()
            var authContext: AuthContext? = null
            var authenticated = false

            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val text = frame.readText()

                    val request = try {
                        json.decodeFromString(JsonRpcRequest.serializer(), text)
                    } catch (e: Exception) {
                        send(Frame.Text(json.encodeToString(JsonRpcResponse.serializer(), JsonRpcResponse(
                            error = JsonRpcError(JsonRpcError.PARSE_ERROR, "Invalid JSON-RPC"),
                        ))))
                        continue
                    }

                    // Handle connect/auth as a special case
                    if (request.method == "connect") {
                        val result = handleConnect(connId, request.params, this)
                        authContext = when (result) {
                            is AuthResult.Allowed -> {
                                authenticated = true
                                connections[connId] = WsConnection(connId, this, result.context)
                                result.context
                            }
                            is AuthResult.Denied -> null
                        }

                        val response = when (result) {
                            is AuthResult.Allowed -> JsonRpcResponse(
                                id = request.id,
                                result = buildJsonObject {
                                    put("status", "connected")
                                    put("connectionId", connId)
                                },
                            )
                            is AuthResult.Denied -> JsonRpcResponse(
                                id = request.id,
                                error = JsonRpcError(JsonRpcError.AUTH_REQUIRED, result.reason),
                            )
                        }
                        send(Frame.Text(json.encodeToString(JsonRpcResponse.serializer(), response)))
                        continue
                    }

                    // All other methods require authentication (unless auth mode is NONE)
                    if (!authenticated && authenticator.authenticate(ConnectParams(connId)).let { it is AuthResult.Denied }) {
                        send(Frame.Text(json.encodeToString(JsonRpcResponse.serializer(), JsonRpcResponse(
                            id = request.id,
                            error = JsonRpcError(JsonRpcError.AUTH_REQUIRED, "Authentication required"),
                        ))))
                        continue
                    }

                    // Dispatch to registered method handler
                    val context = RpcContext(
                        connectionId = connId,
                        authContext = authContext,
                        gateway = this@GatewayServer,
                    )
                    val response = dispatcher.dispatch(request, context)
                    send(Frame.Text(json.encodeToString(JsonRpcResponse.serializer(), response)))
                }
            } finally {
                connections.remove(connId)
            }
        }
    }

    private fun handleConnect(
        connId: String,
        params: JsonElement?,
        session: DefaultWebSocketServerSession,
    ): AuthResult {
        val obj = params?.jsonObject
        return authenticator.authenticate(ConnectParams(
            connectionId = connId,
            token = obj?.get("token")?.jsonPrimitive?.contentOrNull,
            password = obj?.get("password")?.jsonPrimitive?.contentOrNull,
            deviceId = obj?.get("deviceId")?.jsonPrimitive?.contentOrNull,
            role = obj?.get("role")?.jsonPrimitive?.contentOrNull,
        ))
    }

    // --- Broadcasting ---

    suspend fun broadcast(method: String, params: JsonElement?) {
        val notification = json.encodeToString(
            JsonRpcNotification.serializer(),
            JsonRpcNotification(method = method, params = params),
        )
        connections.values.forEach { conn ->
            try {
                conn.session.send(Frame.Text(notification))
            } catch (_: Exception) {
                // Client disconnected
                connections.remove(conn.id)
            }
        }
    }

    suspend fun sendTo(connectionId: String, method: String, params: JsonElement?) {
        val conn = connections[connectionId] ?: return
        val notification = json.encodeToString(
            JsonRpcNotification.serializer(),
            JsonRpcNotification(method = method, params = params),
        )
        try {
            conn.session.send(Frame.Text(notification))
        } catch (_: Exception) {
            connections.remove(connectionId)
        }
    }

    // --- Core RPC Methods ---

    private fun registerCoreMethods() {
        dispatcher.register("health") { _, _ ->
            buildJsonObject {
                put("status", "ok")
                put("uptime", System.currentTimeMillis())
            }
        }

        dispatcher.register("status") { _, _ ->
            buildJsonObject {
                put("connections", connections.size)
                put("channels", JsonPrimitive(channelManager.getSnapshot().size))
            }
        }

        dispatcher.register("channels.status") { _, _ ->
            val snapshot = channelManager.getSnapshot()
            buildJsonObject {
                for ((key, ch) in snapshot) {
                    putJsonObject(key) {
                        put("channelId", ch.channelId)
                        put("accountId", ch.accountId)
                        put("status", ch.status)
                        ch.error?.let { put("error", it) }
                        ch.startedAt?.let { put("startedAt", it) }
                    }
                }
            }
        }

        dispatcher.register("chat.send") { params, context ->
            val obj = params?.jsonObject ?: throw RpcException(
                JsonRpcError.INVALID_PARAMS, "Missing params",
            )
            val text = obj["message"]?.jsonPrimitive?.contentOrNull
                ?: obj["text"]?.jsonPrimitive?.contentOrNull
                ?: throw RpcException(JsonRpcError.INVALID_PARAMS, "Missing message text")

            val chatHandler = onChatSend ?: throw RpcException(
                JsonRpcError.INTERNAL_ERROR, "Agent not configured",
            )

            val requestId = UUID.randomUUID().toString()
            val sessionKey = obj["sessionKey"]?.jsonPrimitive?.contentOrNull ?: "ws:${context.connectionId}"
            val agentId = obj["agentId"]?.jsonPrimitive?.contentOrNull
            val channel = obj["channel"]?.jsonPrimitive?.contentOrNull
            val accountId = obj["accountId"]?.jsonPrimitive?.contentOrNull
            val to = obj["to"]?.jsonPrimitive?.contentOrNull
            val model = obj["model"]?.jsonPrimitive?.contentOrNull
            val deliver = obj["deliver"]?.jsonPrimitive?.booleanOrNull ?: true

            // Stream events to the requesting connection
            serverScope.launch {
                var agentSeq = 0
                suspend fun sendAgentEvent(stream: String, data: JsonObject) {
                    agentSeq += 1
                    val payload = buildAgentEventPayload(
                        runId = requestId,
                        sessionKey = sessionKey,
                        seq = agentSeq,
                        stream = stream,
                        data = data,
                    )
                    sendTo(context.connectionId, "agent.event", payload)
                }
                try {
                    sendAgentEvent(
                        stream = "lifecycle",
                        data = buildJsonObject { put("phase", "start") },
                    )
                    chatHandler(ChatSendParams(
                        sessionKey = sessionKey,
                        agentId = agentId,
                        text = text,
                        channel = channel,
                        accountId = accountId,
                        to = to,
                        model = model,
                        deliver = deliver,
                        requestId = requestId,
                    )).collect { event ->
                        val eventJson = buildChatEventPayload(
                            event = event,
                            sessionKey = sessionKey,
                            requestId = requestId,
                        )
                        sendTo(context.connectionId, "chat.event", eventJson)
                        buildAgentStreamPayload(event)?.let { mapped ->
                            sendAgentEvent(stream = mapped.stream, data = mapped.data)
                        }
                    }
                } catch (e: Exception) {
                    sendTo(context.connectionId, "chat.event", buildJsonObject {
                        put("type", "error")
                        put("message", e.message ?: "Unknown error")
                        put("sessionKey", sessionKey)
                        put("requestId", requestId)
                    })
                    sendAgentEvent(
                        stream = "lifecycle",
                        data = buildJsonObject {
                            put("phase", "error")
                            put("error", e.message ?: "Unknown error")
                        },
                    )
                }
            }

            buildJsonObject {
                put("requestId", requestId)
                put("sessionKey", sessionKey)
                put("status", "accepted")
            }
        }

        dispatcher.register("chat.abort") { params, _ ->
            // TODO: Wire to session manager abort
            buildJsonObject { put("status", "ok") }
        }

        dispatcher.register("config.get") { params, _ ->
            // Return sanitized config (no secrets)
            buildJsonObject { put("status", "ok") }
        }

        dispatcher.register("sessions.list") { _, _ ->
            // TODO: Wire to session store
            buildJsonObject { putJsonArray("sessions") {} }
        }

        dispatcher.register("models.list") { _, _ ->
            // TODO: Wire to provider registry
            buildJsonObject { putJsonArray("models") {} }
        }

        dispatcher.register("agents.list") { _, _ ->
            val agents = config?.agents?.list ?: emptyList()
            buildJsonObject {
                putJsonArray("agents") {
                    for (agent in agents) {
                        addJsonObject {
                            put("id", agent.id)
                            agent.identity?.name?.let { put("name", it) }
                        }
                    }
                }
            }
        }
    }

    internal fun buildChatEventPayload(
        event: AcpRuntimeEvent,
        sessionKey: String,
        requestId: String,
    ): JsonObject {
        return when (event) {
            is AcpRuntimeEvent.TextDelta -> buildJsonObject {
                put("type", "text_delta")
                put("text", event.text)
                event.stream?.let { put("stream", it.name.lowercase()) }
                event.tag?.let { put("tag", it) }
                put("sessionKey", sessionKey)
                put("requestId", requestId)
            }
            is AcpRuntimeEvent.ToolCall -> buildJsonObject {
                put("type", "tool_call")
                put("text", event.text)
                event.title?.let { put("title", it) }
                event.tag?.let { put("tag", it) }
                event.toolCallId?.let { put("toolCallId", it) }
                normalizeToolStatus(event.status)?.let { put("status", it) }
                buildToolDetail(event)?.let { put("detail", it) }
                event.kind?.let { put("kind", it) }
                event.rawInput?.let { put("rawInput", it) }
                event.rawOutput?.let { put("rawOutput", it) }
                put("sessionKey", sessionKey)
                put("requestId", requestId)
            }
            is AcpRuntimeEvent.Done -> buildJsonObject {
                put("type", "done")
                put("sessionKey", sessionKey)
                put("requestId", requestId)
                event.stopReason?.let { put("stopReason", it) }
            }
            is AcpRuntimeEvent.Error -> buildJsonObject {
                put("type", "error")
                put("message", event.message)
                event.code?.let { put("code", it) }
                event.retryable?.let { put("retryable", it) }
                put("sessionKey", sessionKey)
                put("requestId", requestId)
            }
            is AcpRuntimeEvent.Status -> buildJsonObject {
                put("type", "status")
                put("text", event.text)
                event.tag?.let { put("tag", it) }
                event.used?.let { put("used", it) }
                event.size?.let { put("size", it) }
                put("sessionKey", sessionKey)
                put("requestId", requestId)
            }
        }
    }

    private fun normalizeToolStatus(status: String?): String? {
        val normalized = status?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return when (normalized) {
            "started", "running", "inprogress" -> "in_progress"
            "done", "success", "succeeded", "complete" -> "completed"
            "denied", "blocked", "timeout", "timed_out", "failure", "error" -> "failed"
            else -> normalized
        }
    }

    private fun buildToolDetail(event: AcpRuntimeEvent.ToolCall): String? {
        event.detail?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val parts = mutableListOf<String>()
        event.title?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
        normalizeToolStatus(event.status)?.let { parts += "status=$it" }
        val text = event.text.trim()
        if (text.isNotEmpty() && parts.none { it.equals(text, ignoreCase = true) }) {
            parts += text
        }
        return parts.joinToString(" | ").takeIf { it.isNotEmpty() }
    }
}
