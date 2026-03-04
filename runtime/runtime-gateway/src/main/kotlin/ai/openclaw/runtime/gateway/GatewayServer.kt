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
import java.util.concurrent.atomic.AtomicLong

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
    private companion object {
        private const val PROTOCOL_VERSION = 7
        private const val GATEWAY_SERVER_VERSION = "kotlin-openclaw"
        private const val MAX_PAYLOAD_BYTES = 4_194_304
        private const val MAX_BUFFERED_BYTES = 1_048_576
        private const val TICK_INTERVAL_MS = 1_000
    }

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
    private val eventSeq = AtomicLong(0L)
    private val activeChatJobs = ConcurrentHashMap<String, Job>()
    private val activeChatSessions = ConcurrentHashMap<String, String>()
    private val activeChatConnections = ConcurrentHashMap<String, String>()
    private val chatBuffers = ConcurrentHashMap<String, StringBuilder>()
    private val chatSeqByRun = ConcurrentHashMap<String, Int>()
    private val abortedChatRuns = ConcurrentHashMap.newKeySet<String>()

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
            val connectNonce = UUID.randomUUID().toString()

            send(
                Frame.Text(
                    json.encodeToString(
                        GatewayEventFrame.serializer(),
                        GatewayEventFrame(
                            event = "connect.challenge",
                            payload = buildJsonObject {
                                put("nonce", connectNonce)
                                put("ts", System.currentTimeMillis())
                            },
                            seq = null,
                        ),
                    ),
                ),
            )

            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val text = frame.readText()

                    val request = parseRequestFrame(text)
                    if (request == null) {
                        send(
                            Frame.Text(
                                json.encodeToString(
                                    GatewayResponseFrame.serializer(),
                                    GatewayResponseFrame(
                                        id = "unknown",
                                        ok = false,
                                        error = GatewayErrorFrame(
                                            code = "parse_error",
                                            message = "Invalid request frame",
                                        ),
                                    ),
                                ),
                            ),
                        )
                        continue
                    }

                    // Handle connect/auth as a special case
                    if (request.method == "connect") {
                        val protocolValidationError = validateConnectProtocol(request.params)
                        if (protocolValidationError != null) {
                            val protocolResponse = GatewayResponseFrame(
                                id = request.id,
                                ok = false,
                                error = GatewayErrorFrame(
                                    code = "invalid_request",
                                    message = protocolValidationError,
                                ),
                            )
                            send(Frame.Text(json.encodeToString(GatewayResponseFrame.serializer(), protocolResponse)))
                            continue
                        }

                        val result = handleConnect(connId, request.params, this, connectNonce)
                        authContext = when (result) {
                            is AuthResult.Allowed -> {
                                authenticated = true
                                connections[connId] = WsConnection(connId, this, result.context)
                                result.context
                            }
                            is AuthResult.Denied -> null
                        }

                        val response = when (result) {
                            is AuthResult.Allowed -> GatewayResponseFrame(
                                id = request.id,
                                ok = true,
                                payload = buildHelloOkPayload(connId),
                            )
                            is AuthResult.Denied -> GatewayResponseFrame(
                                id = request.id,
                                ok = false,
                                error = GatewayErrorFrame(code = "auth_required", message = result.reason),
                            )
                        }
                        send(Frame.Text(json.encodeToString(GatewayResponseFrame.serializer(), response)))
                        continue
                    }

                    // All other methods require authentication (unless auth mode is NONE)
                    if (!authenticated && authenticator.authenticate(ConnectParams(connId)).let { it is AuthResult.Denied }) {
                        send(
                            Frame.Text(
                                json.encodeToString(
                                    GatewayResponseFrame.serializer(),
                                    GatewayResponseFrame(
                                        id = request.id,
                                        ok = false,
                                        error = GatewayErrorFrame(
                                            code = "auth_required",
                                            message = "Authentication required",
                                        ),
                                    ),
                                ),
                            ),
                        )
                        continue
                    }

                    // Dispatch to registered method handler
                    val context = RpcContext(
                        connectionId = connId,
                        authContext = authContext,
                        gateway = this@GatewayServer,
                    )
                    val response = dispatcher.dispatch(request, context)
                    send(Frame.Text(json.encodeToString(GatewayResponseFrame.serializer(), response)))
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
        connectNonce: String,
    ): AuthResult {
        val obj = params?.jsonObject
        val nonce = obj?.get("nonce")?.jsonPrimitive?.contentOrNull
        if (!nonce.isNullOrBlank() && nonce != connectNonce) {
            return AuthResult.Denied("Invalid connect challenge nonce", AuthMode.NONE)
        }
        return authenticator.authenticate(ConnectParams(
            connectionId = connId,
            token = obj?.get("token")?.jsonPrimitive?.contentOrNull,
            password = obj?.get("password")?.jsonPrimitive?.contentOrNull,
            deviceId = obj?.get("deviceId")?.jsonPrimitive?.contentOrNull,
            role = obj?.get("role")?.jsonPrimitive?.contentOrNull,
        ))
    }

    private fun parseRequestFrame(raw: String): GatewayRequestFrame? {
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
        val type = element["type"]?.jsonPrimitive?.contentOrNull ?: return null
        if (type != "req") return null
        val id = element["id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val method = element["method"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return GatewayRequestFrame(
            id = id,
            method = method,
            params = element["params"],
        )
    }

    private fun validateConnectProtocol(params: JsonElement?): String? {
        val obj = params?.jsonObject
        val minProtocol = obj?.get("minProtocol")?.jsonPrimitive?.intOrNull ?: PROTOCOL_VERSION
        val maxProtocol = obj?.get("maxProtocol")?.jsonPrimitive?.intOrNull ?: PROTOCOL_VERSION
        if (minProtocol > maxProtocol) {
            return "invalid protocol bounds: minProtocol must be <= maxProtocol"
        }
        if (maxProtocol < PROTOCOL_VERSION || minProtocol > PROTOCOL_VERSION) {
            return "protocol mismatch (expected $PROTOCOL_VERSION)"
        }
        return null
    }

    private fun buildHelloOkPayload(connId: String): JsonObject {
        return buildJsonObject {
            put("type", "hello-ok")
            put("protocol", PROTOCOL_VERSION)
            putJsonObject("server") {
                put("version", GATEWAY_SERVER_VERSION)
                put("connId", connId)
            }
            putJsonObject("features") {
                putJsonArray("methods") {
                    dispatcher.methodNames().forEach { method ->
                        add(JsonPrimitive(method))
                    }
                }
                putJsonArray("events") {
                    listOf("connect.challenge", "agent", "chat").forEach { event ->
                        add(JsonPrimitive(event))
                    }
                }
            }
            putJsonObject("snapshot") {
                put("connections", connections.size)
            }
            putJsonObject("policy") {
                put("maxPayload", MAX_PAYLOAD_BYTES)
                put("maxBufferedBytes", MAX_BUFFERED_BYTES)
                put("tickIntervalMs", TICK_INTERVAL_MS)
            }
        }
    }

    private fun nextEventSeq(): Long = eventSeq.incrementAndGet()

    // --- Broadcasting ---

    suspend fun broadcast(method: String, params: JsonElement?) {
        val frame = json.encodeToString(
            GatewayEventFrame.serializer(),
            GatewayEventFrame(
                event = method,
                payload = params,
                seq = nextEventSeq(),
            ),
        )
        connections.values.forEach { conn ->
            try {
                conn.session.send(Frame.Text(frame))
            } catch (_: Exception) {
                // Client disconnected
                connections.remove(conn.id)
            }
        }
    }

    suspend fun sendTo(connectionId: String, method: String, params: JsonElement?) {
        val conn = connections[connectionId] ?: return
        val frame = json.encodeToString(
            GatewayEventFrame.serializer(),
            GatewayEventFrame(
                event = method,
                payload = params,
                seq = null,
            ),
        )
        try {
            conn.session.send(Frame.Text(frame))
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
            val obj = params?.jsonObject ?: throw RpcException("invalid_params", "Missing params")
            val text = obj["message"]?.jsonPrimitive?.contentOrNull
                ?: obj["text"]?.jsonPrimitive?.contentOrNull
                ?: throw RpcException("invalid_params", "Missing message text")

            val chatHandler = onChatSend ?: throw RpcException("internal_error", "Agent not configured")

            val runId = obj["idempotencyKey"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw RpcException("invalid_params", "Missing idempotencyKey")
            val sessionKey = obj["sessionKey"]?.jsonPrimitive?.contentOrNull ?: "ws:${context.connectionId}"
            val agentId = obj["agentId"]?.jsonPrimitive?.contentOrNull
            val channel = obj["channel"]?.jsonPrimitive?.contentOrNull
            val accountId = obj["accountId"]?.jsonPrimitive?.contentOrNull
            val to = obj["to"]?.jsonPrimitive?.contentOrNull
            val model = obj["model"]?.jsonPrimitive?.contentOrNull
            val deliver = obj["deliver"]?.jsonPrimitive?.booleanOrNull ?: true

            val existingJob = activeChatJobs[runId]
            if (existingJob?.isActive == true) {
                return@register buildJsonObject {
                    put("runId", runId)
                    put("status", "in_flight")
                }
            }

            // Stream events for this run id.
            chatBuffers[runId] = StringBuilder()
            chatSeqByRun[runId] = 0
            val chatJob = serverScope.launch {
                try {
                    val startSeq = nextChatSeq(runId)
                    broadcast(
                        "agent",
                        buildAgentEventPayload(
                            runId = runId,
                            sessionKey = sessionKey,
                            seq = startSeq,
                            stream = "lifecycle",
                            data = buildJsonObject { put("phase", "start") },
                        ),
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
                        requestId = runId,
                    )).collect { event ->
                        buildAgentStreamPayload(event, includeToolResults = false)?.let { mapped ->
                            val seq = nextChatSeq(runId)
                            val agentPayload = buildAgentEventPayload(
                                runId = runId,
                                sessionKey = sessionKey,
                                seq = seq,
                                stream = mapped.stream,
                                data = mapped.data,
                            )
                            if (mapped.stream == "tool") {
                                // Tool events are requester-scoped by default to avoid leaking tool output.
                                sendTo(context.connectionId, "agent", agentPayload)
                            } else {
                                broadcast("agent", agentPayload)
                            }
                            when (mapped.stream) {
                                "assistant" -> {
                                    val delta = mapped.data["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    if (delta.isNotEmpty()) {
                                        val cumulative = chatBuffers[runId]?.apply { append(delta) }?.toString().orEmpty()
                                        broadcast(
                                            "chat",
                                            buildChatDeltaPayload(
                                                runId = runId,
                                                sessionKey = sessionKey,
                                                seq = seq,
                                                text = cumulative,
                                            ),
                                        )
                                    }
                                }

                                "lifecycle" -> {
                                    val phase = mapped.data["phase"]?.jsonPrimitive?.contentOrNull
                                    if (phase == "end") {
                                        val finalText = chatBuffers[runId]?.toString().orEmpty()
                                        broadcast(
                                            "chat",
                                            buildChatFinalPayload(
                                                runId = runId,
                                                sessionKey = sessionKey,
                                                seq = seq,
                                                text = finalText,
                                                stopReason = mapped.data["stopReason"]?.jsonPrimitive?.contentOrNull,
                                            ),
                                        )
                                    } else if (phase == "error") {
                                        broadcast(
                                            "chat",
                                            buildChatErrorPayload(
                                                runId = runId,
                                                sessionKey = sessionKey,
                                                seq = seq,
                                                error = mapped.data["error"]?.jsonPrimitive?.contentOrNull
                                                    ?: "Unknown error",
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    if (!abortedChatRuns.contains(runId)) {
                        throw cancelled
                    }
                } catch (e: Exception) {
                    val seq = nextChatSeq(runId)
                    broadcast(
                        "agent",
                        buildAgentEventPayload(
                            runId = runId,
                            sessionKey = sessionKey,
                            seq = seq,
                            stream = "lifecycle",
                            data = buildJsonObject {
                                put("phase", "error")
                                put("error", e.message ?: "Unknown error")
                            },
                        ),
                    )
                    broadcast(
                        "chat",
                        buildChatErrorPayload(
                            runId = runId,
                            sessionKey = sessionKey,
                            seq = seq,
                            error = e.message ?: "Unknown error",
                        ),
                    )
                } finally {
                    activeChatJobs.remove(runId)
                    activeChatSessions.remove(runId)
                    activeChatConnections.remove(runId)
                    chatBuffers.remove(runId)
                    chatSeqByRun.remove(runId)
                    abortedChatRuns.remove(runId)
                }
            }
            activeChatJobs[runId] = chatJob
            activeChatSessions[runId] = sessionKey
            activeChatConnections[runId] = context.connectionId

            buildJsonObject {
                put("runId", runId)
                put("status", "started")
            }
        }

        dispatcher.register("chat.abort") { params, _ ->
            val obj = params?.jsonObject ?: throw RpcException("invalid_params", "Missing params")
            val sessionKey = obj["sessionKey"]?.jsonPrimitive?.contentOrNull
                ?: throw RpcException("invalid_params", "Missing sessionKey")
            val runId = obj["runId"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val abortedRunIds = mutableListOf<String>()
            if (runId != null) {
                val activeSessionKey = activeChatSessions[runId]
                if (activeSessionKey != null && activeSessionKey != sessionKey) {
                    throw RpcException("invalid_request", "runId does not match sessionKey")
                }
                if (abortActiveChatRun(runId, sessionKey)) {
                    abortedRunIds += runId
                }
            } else {
                val matchingRunIds = activeChatSessions.entries
                    .filter { (_, activeSessionKey) -> activeSessionKey == sessionKey }
                    .map { (activeRunId, _) -> activeRunId }
                for (activeRunId in matchingRunIds) {
                    if (abortActiveChatRun(activeRunId, sessionKey)) {
                        abortedRunIds += activeRunId
                    }
                }
            }
            buildJsonObject {
                put("ok", true)
                put("aborted", abortedRunIds.isNotEmpty())
                putJsonArray("runIds") {
                    abortedRunIds.forEach { add(JsonPrimitive(it)) }
                }
            }
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

    private fun nextChatSeq(runId: String): Int {
        val next = (chatSeqByRun[runId] ?: 0) + 1
        chatSeqByRun[runId] = next
        return next
    }

    private suspend fun abortActiveChatRun(runId: String, sessionKey: String): Boolean {
        val activeSessionKey = activeChatSessions[runId] ?: return false
        if (activeSessionKey != sessionKey) return false

        abortedChatRuns += runId
        val seq = nextChatSeq(runId)
        val partialText = chatBuffers[runId]?.toString().orEmpty()

        activeChatJobs[runId]?.cancel(CancellationException("chat.abort"))

        broadcast(
            "agent",
            buildAgentEventPayload(
                runId = runId,
                sessionKey = sessionKey,
                seq = seq,
                stream = "lifecycle",
                data = buildJsonObject {
                    put("phase", "end")
                    put("stopReason", "aborted")
                },
            ),
        )
        broadcast(
            "chat",
            buildChatAbortedPayload(
                runId = runId,
                sessionKey = sessionKey,
                seq = seq,
                text = partialText,
                stopReason = "aborted",
            ),
        )
        return true
    }

    internal fun buildChatDeltaPayload(
        runId: String,
        sessionKey: String,
        seq: Int,
        text: String,
    ): JsonObject {
        return buildJsonObject {
            put("runId", runId)
            put("sessionKey", sessionKey)
            put("seq", seq)
            put("state", "delta")
            putJsonObject("message") {
                put("role", "assistant")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", text)
                    }
                }
                put("timestamp", System.currentTimeMillis())
            }
        }
    }

    internal fun buildChatFinalPayload(
        runId: String,
        sessionKey: String,
        seq: Int,
        text: String,
        stopReason: String? = null,
    ): JsonObject {
        return buildJsonObject {
            put("runId", runId)
            put("sessionKey", sessionKey)
            put("seq", seq)
            put("state", "final")
            stopReason?.let { put("stopReason", it) }
            val trimmed = text.trim()
            if (trimmed.isNotEmpty()) {
                putJsonObject("message") {
                    put("role", "assistant")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", trimmed)
                        }
                    }
                    put("timestamp", System.currentTimeMillis())
                }
            }
        }
    }

    internal fun buildChatErrorPayload(
        runId: String,
        sessionKey: String,
        seq: Int,
        error: String,
    ): JsonObject {
        return buildJsonObject {
            put("runId", runId)
            put("sessionKey", sessionKey)
            put("seq", seq)
            put("state", "error")
            put("errorMessage", error)
        }
    }

    internal fun buildChatAbortedPayload(
        runId: String,
        sessionKey: String,
        seq: Int,
        text: String,
        stopReason: String? = null,
    ): JsonObject {
        return buildJsonObject {
            put("runId", runId)
            put("sessionKey", sessionKey)
            put("seq", seq)
            put("state", "aborted")
            stopReason?.let { put("stopReason", it) }
            val trimmed = text.trim()
            if (trimmed.isNotEmpty()) {
                putJsonObject("message") {
                    put("role", "assistant")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", trimmed)
                        }
                    }
                    put("timestamp", System.currentTimeMillis())
                }
            }
        }
    }

    internal fun buildChatEventPayload(
        event: AcpRuntimeEvent,
        sessionKey: String,
        runId: String,
        seq: Int,
        bufferedAssistantText: String = "",
    ): JsonObject? {
        return when (event) {
            is AcpRuntimeEvent.TextDelta -> buildChatDeltaPayload(
                runId = runId,
                sessionKey = sessionKey,
                seq = seq,
                text = event.text,
            )
            is AcpRuntimeEvent.Done -> buildChatFinalPayload(
                runId = runId,
                sessionKey = sessionKey,
                seq = seq,
                text = bufferedAssistantText,
                stopReason = event.stopReason,
            )
            is AcpRuntimeEvent.Error -> buildChatErrorPayload(
                runId = runId,
                sessionKey = sessionKey,
                seq = seq,
                error = event.message,
            )
            else -> null
        }
    }

    internal data class AgentStreamPayload(
        val stream: String,
        val data: JsonObject,
    )

    internal fun buildAgentEventPayload(
        runId: String,
        sessionKey: String,
        seq: Int,
        stream: String,
        data: JsonObject,
    ): JsonObject {
        return buildJsonObject {
            put("runId", runId)
            put("sessionKey", sessionKey)
            put("seq", seq)
            put("stream", stream)
            put("ts", System.currentTimeMillis())
            put("data", data)
        }
    }

    internal fun buildAgentStreamPayload(
        event: AcpRuntimeEvent,
        includeToolResults: Boolean = true,
    ): AgentStreamPayload? {
        return when (event) {
            is AcpRuntimeEvent.TextDelta -> AgentStreamPayload(
                stream = "assistant",
                data = buildJsonObject {
                    put("text", event.text)
                    event.stream?.let { put("substream", it.name.lowercase()) }
                    event.tag?.let { put("tag", it) }
                },
            )
            is AcpRuntimeEvent.ToolCall -> {
                val normalizedStatus = normalizeToolStatus(event.status)
                val phase = when {
                    event.tag == "tool_call" -> "start"
                    normalizedStatus == "completed" || normalizedStatus == "failed" -> "result"
                    !event.rawOutput.isNullOrBlank() -> "result"
                    else -> "update"
                }
                val toolName = resolveToolName(event)
                val data = buildJsonObject {
                    put("phase", phase)
                    toolName?.let { put("name", it) }
                    event.toolCallId?.let { put("toolCallId", it) }
                    if (phase == "start") {
                        parseJsonOrString(event.rawInput)?.let { put("args", it) }
                    } else if (includeToolResults) {
                        parseJsonOrString(event.rawOutput)?.let {
                            if (phase == "update") {
                                put("partialResult", it)
                            } else {
                                put("result", it)
                            }
                        }
                    }
                    if (phase == "result") {
                        val errored = normalizedStatus == "failed" || inferToolError(event.rawOutput)
                        put("isError", errored)
                    }
                    event.kind?.let { put("kind", it) }
                    event.detail?.let { put("meta", it) }
                }
                AgentStreamPayload(stream = "tool", data = data)
            }
            is AcpRuntimeEvent.Done -> AgentStreamPayload(
                stream = "lifecycle",
                data = buildJsonObject {
                    put("phase", "end")
                    event.stopReason?.let { put("stopReason", it) }
                },
            )
            is AcpRuntimeEvent.Error -> AgentStreamPayload(
                stream = "lifecycle",
                data = buildJsonObject {
                    put("phase", "error")
                    put("error", event.message)
                    event.code?.let { put("code", it) }
                },
            )
            is AcpRuntimeEvent.Status -> AgentStreamPayload(
                stream = "lifecycle",
                data = buildJsonObject {
                    put("phase", "status")
                    put("text", event.text)
                    event.tag?.let { put("tag", it) }
                    event.used?.let { put("used", it) }
                    event.size?.let { put("size", it) }
                },
            )
        }
    }

    private fun resolveToolName(event: AcpRuntimeEvent.ToolCall): String? {
        val fromTitle = event.title
            ?.substringBefore(":")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (fromTitle != null) return fromTitle
        val text = event.text.trim()
        if (text.isEmpty()) return null
        val knownPrefixes = listOf("calling ", "completed ", "blocked ", "tool ")
        for (prefix in knownPrefixes) {
            if (text.lowercase().startsWith(prefix)) {
                return text.substring(prefix.length).substringBefore(" ").trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun parseJsonOrString(raw: String?): JsonElement? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { json.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }
    }

    private fun inferToolError(rawOutput: String?): Boolean {
        val text = rawOutput?.trim()?.lowercase().orEmpty()
        if (text.isEmpty()) return false
        if (text.startsWith("error:")) return true
        if (text.contains("\"status\":\"error\"")) return true
        if (text.contains("\"error\"")) return true
        return false
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
