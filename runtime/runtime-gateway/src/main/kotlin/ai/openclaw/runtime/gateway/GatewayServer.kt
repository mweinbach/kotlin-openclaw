package ai.openclaw.runtime.gateway

import ai.openclaw.core.model.*
import ai.openclaw.core.security.AuditLog
import ai.openclaw.core.security.GatewayOriginPolicy
import ai.openclaw.core.security.GatewayOriginPolicyConfig
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.*
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
    private val pairingStore = DevicePairingStore()
    val authenticator = GatewayAuthenticator(config?.gateway?.auth, pairingStore)
    val channelManager = ChannelManager()
    val auditLog = AuditLog()
    val webhookManager = WebhookManager(config?.hooks, config, auditLog, json)
    private val originPolicy = GatewayOriginPolicy(
        GatewayOriginPolicyConfig(
            allowedOrigins = config?.gateway?.controlUi?.allowedOrigins.orEmpty(),
            allowHostHeaderFallback = config?.gateway?.controlUi?.dangerouslyAllowHostHeaderOriginFallback == true,
        ),
    )

    // Connected WebSocket sessions
    private val connections = ConcurrentHashMap<String, WsConnection>()
    private val eventSeq = AtomicLong(0L)
    private val activeChatJobs = ConcurrentHashMap<String, Job>()
    private val activeChatSessions = ConcurrentHashMap<String, String>()
    private val activeChatConnections = ConcurrentHashMap<String, String>()
    private val chatBuffers = ConcurrentHashMap<String, StringBuilder>()
    private val chatSeqByRun = ConcurrentHashMap<String, Int>()
    private val abortedChatRuns = ConcurrentHashMap.newKeySet<String>()
    private val persistedAbortRuns = ConcurrentHashMap.newKeySet<String>()

    data class WsConnection(
        val id: String,
        val session: DefaultWebSocketServerSession,
        val authContext: AuthContext?,
        val connectedAt: Long = System.currentTimeMillis(),
    )

    // --- Chat run callback ---

    var onChatSend: (suspend (ChatSendParams) -> Flow<AcpRuntimeEvent>)? = null
    var onAbortPersistPartial: (suspend (AbortPartialPersistParams) -> Unit)? = null

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
        val clientTools: List<ClientToolSpec> = emptyList(),
        val functionCallOutputs: List<FunctionCallOutput> = emptyList(),
    )

    data class ClientToolSpec(
        val name: String,
        val description: String = "Client-side hosted tool",
        val parameters: String = """{"type":"object","properties":{},"additionalProperties":true}""",
    )

    data class FunctionCallOutput(
        val callId: String,
        val output: String,
        val name: String? = null,
    )

    data class AbortPartialPersistParams(
        val sessionKey: String,
        val runId: String,
        val text: String,
        val origin: String,
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
            if (!authorizeHttpRequest(call)) return@post

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
            val text = when (val content = lastMessage?.get("content")) {
                is JsonPrimitive -> content.contentOrNull.orEmpty()
                is JsonArray -> content.joinToString(separator = "\n") { block ->
                    val obj = block.jsonObject
                    obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                }
                else -> ""
            }

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
            val sessionKey = resolveHttpSessionKey(call, request, requestId)
            val responseText = StringBuilder()
            var finishReason = "stop"

            try {
                chatHandler(
                    ChatSendParams(
                        sessionKey = sessionKey,
                        agentId = null,
                        text = text,
                        channel = "http",
                        accountId = request["user"]?.jsonPrimitive?.contentOrNull ?: "default",
                        to = null,
                        model = model,
                        deliver = false,
                        requestId = requestId,
                    ),
                ).collect { event ->
                    when (event) {
                        is AcpRuntimeEvent.TextDelta -> responseText.append(event.text)
                        is AcpRuntimeEvent.Done -> {
                            finishReason = event.stopReason ?: "stop"
                        }
                        else -> {}
                    }
                }

                call.respondText(
                    json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
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
                                    put("finish_reason", finishReason)
                                }
                            }
                        },
                    ),
                    ContentType.Application.Json,
                )
            } catch (e: Exception) {
                call.respondText(
                    json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            putJsonObject("error") {
                                put("message", e.message ?: "Internal error")
                                put("type", "server_error")
                            }
                        },
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError,
                )
            }
        }

        post("/v1/responses") {
            if (!authorizeHttpRequest(call)) return@post

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
            val model = request["model"]?.jsonPrimitive?.contentOrNull
            val input = request["input"]
            val inputText = extractResponsesInputText(input)
            val functionCallOutputs = extractFunctionCallOutputs(input)
            val clientTools = extractClientTools(request["tools"])
            val sessionKey = resolveHttpSessionKey(call, request, requestId)
            val responseText = StringBuilder()
            var stopReason: String? = null
            var errorMessage: String? = null
            var pendingToolCalls: List<AcpPendingToolCall> = emptyList()

            chatHandler(
                ChatSendParams(
                    sessionKey = sessionKey,
                    agentId = null,
                    text = inputText,
                    channel = "http",
                    accountId = request["user"]?.jsonPrimitive?.contentOrNull ?: "default",
                    to = null,
                    model = model,
                    deliver = false,
                    requestId = requestId,
                    clientTools = clientTools,
                    functionCallOutputs = functionCallOutputs,
                ),
            ).collect { event ->
                when (event) {
                    is AcpRuntimeEvent.TextDelta -> responseText.append(event.text)
                    is AcpRuntimeEvent.Done -> {
                        stopReason = event.stopReason
                        pendingToolCalls = event.pendingToolCalls.orEmpty()
                    }
                    is AcpRuntimeEvent.Error -> {
                        errorMessage = event.message
                    }
                    else -> {}
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                call.respondText(
                    json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            putJsonObject("error") {
                                put("message", errorMessage)
                                put("type", "server_error")
                            }
                        },
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError,
                )
                return@post
            }

            val status = if (pendingToolCalls.isNotEmpty()) "incomplete" else "completed"
            call.respondText(
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("id", "resp_$requestId")
                        put("object", "response")
                        put("status", status)
                        put("model", model ?: "default")
                        put("stop_reason", stopReason)
                        putJsonArray("output") {
                            if (pendingToolCalls.isNotEmpty()) {
                                pendingToolCalls.forEach { pending ->
                                    addJsonObject {
                                        put("type", "function_call")
                                        put("call_id", pending.id)
                                        put("name", pending.name)
                                        put("arguments", pending.arguments)
                                    }
                                }
                            } else {
                                addJsonObject {
                                    put("type", "message")
                                    putJsonArray("content") {
                                        addJsonObject {
                                            put("type", "output_text")
                                            put("text", responseText.toString())
                                        }
                                    }
                                }
                            }
                        }
                    },
                ),
                ContentType.Application.Json,
            )
        }
    }

    // --- WebSocket Route ---

    private fun Routing.configureWebSocketRoute() {
        webSocket("/ws") {
            val originAllowed = originPolicy.isAllowed(
                originHeader = call.request.headers["Origin"],
                hostHeader = call.request.headers["Host"],
                remoteHost = call.request.origin.remoteAddress,
            )
            if (!originAllowed) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Origin not allowed"))
                return@webSocket
            }
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

                    // All other methods require authentication (unless auth mode is NONE).
                    if (!authenticated && authenticator.mode() != AuthMode.NONE) {
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
        val authObj = obj?.get("auth")?.jsonObject
        val scopes = ((obj?.get("scopes") ?: authObj?.get("scopes")) as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
        val headers = session.call.request.headers.entries().associate { entry ->
            entry.key to entry.value.firstOrNull().orEmpty()
        }
        return authenticator.authenticate(
            ConnectParams(
                connectionId = connId,
                token = authObj?.get("token")?.jsonPrimitive?.contentOrNull
                    ?: obj?.get("token")?.jsonPrimitive?.contentOrNull,
                password = authObj?.get("password")?.jsonPrimitive?.contentOrNull
                    ?: obj?.get("password")?.jsonPrimitive?.contentOrNull,
                deviceId = obj?.get("deviceId")?.jsonPrimitive?.contentOrNull
                    ?: obj?.get("device")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull,
                deviceToken = authObj?.get("deviceToken")?.jsonPrimitive?.contentOrNull
                    ?: obj?.get("deviceToken")?.jsonPrimitive?.contentOrNull,
                role = obj?.get("role")?.jsonPrimitive?.contentOrNull,
                scopes = scopes,
                ip = session.call.request.origin.remoteAddress,
                headers = headers,
            ),
        )
    }

    private suspend fun authorizeHttpRequest(call: ApplicationCall): Boolean {
        if (authenticator.mode() == AuthMode.NONE) {
            return true
        }
        val authHeader = call.request.headers["Authorization"]?.trim().orEmpty()
        val bearerToken = if (authHeader.startsWith("Bearer ", ignoreCase = true)) {
            authHeader.removePrefix("Bearer ").trim()
        } else {
            ""
        }
        val headers = call.request.headers.entries().associate { entry ->
            entry.key to entry.value.firstOrNull().orEmpty()
        }
        val result = authenticator.authenticate(
            ConnectParams(
                connectionId = "http:${UUID.randomUUID()}",
                token = bearerToken.takeIf { it.isNotEmpty() },
                ip = call.request.origin.remoteAddress,
                headers = headers,
            ),
        )
        if (result is AuthResult.Allowed) {
            return true
        }
        val denied = result as AuthResult.Denied
        call.respondText(
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    putJsonObject("error") {
                        put("message", denied.reason)
                        put("type", "auth_required")
                    }
                },
            ),
            ContentType.Application.Json,
            HttpStatusCode.Unauthorized,
        )
        return false
    }

    private fun resolveHttpSessionKey(
        call: ApplicationCall,
        request: JsonObject,
        requestId: String,
    ): String {
        val headerSessionKey = call.request.headers["x-openclaw-session-key"]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: call.request.headers["x-openai-session-key"]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        if (headerSessionKey != null) {
            return "http:$headerSessionKey"
        }
        val requestSession = request["session"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        if (requestSession != null) {
            return "http:$requestSession"
        }
        val user = request["user"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        if (user != null) {
            return "http:user:$user"
        }
        return "http:$requestId"
    }

    private fun extractResponsesInputText(input: JsonElement?): String {
        if (input == null || input is JsonNull) return ""
        if (input is JsonPrimitive) {
            return input.contentOrNull.orEmpty()
        }
        if (input !is JsonArray) return ""
        val candidateTexts = mutableListOf<String>()
        for (item in input) {
            val obj = item as? JsonObject ?: continue
            val type = obj["type"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
            when (type) {
                "message" -> {
                    val content = obj["content"]
                    when (content) {
                        is JsonPrimitive -> candidateTexts += content.contentOrNull.orEmpty()
                        is JsonArray -> {
                            content.forEach { block ->
                                val blockObj = block as? JsonObject ?: return@forEach
                                val blockType = blockObj["type"]?.jsonPrimitive?.contentOrNull?.lowercase()
                                if (blockType == "input_text" || blockType == "text") {
                                    blockObj["text"]?.jsonPrimitive?.contentOrNull?.let { candidateTexts += it }
                                }
                            }
                        }
                        else -> {}
                    }
                }
                "input_text", "text" -> {
                    obj["text"]?.jsonPrimitive?.contentOrNull?.let { candidateTexts += it }
                }
            }
        }
        return candidateTexts.lastOrNull().orEmpty()
    }

    private fun extractFunctionCallOutputs(input: JsonElement?): List<FunctionCallOutput> {
        if (input !is JsonArray) return emptyList()
        val outputs = mutableListOf<FunctionCallOutput>()
        for (item in input) {
            val obj = item as? JsonObject ?: continue
            val type = obj["type"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            if (type != "function_call_output") continue
            val callId = obj["call_id"]?.jsonPrimitive?.contentOrNull
                ?: obj["callId"]?.jsonPrimitive?.contentOrNull
            val normalizedCallId = callId?.trim().orEmpty()
            if (normalizedCallId.isEmpty()) continue
            val output = when (val out = obj["output"]) {
                null, JsonNull -> ""
                is JsonPrimitive -> out.contentOrNull.orEmpty()
                else -> json.encodeToString(JsonElement.serializer(), out)
            }
            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            outputs += FunctionCallOutput(
                callId = normalizedCallId,
                output = output,
                name = name,
            )
        }
        return outputs
    }

    private fun extractClientTools(toolsElement: JsonElement?): List<ClientToolSpec> {
        val array = toolsElement as? JsonArray ?: return emptyList()
        val tools = mutableListOf<ClientToolSpec>()
        for (item in array) {
            val obj = item as? JsonObject ?: continue
            val type = obj["type"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
            val functionObj = obj["function"] as? JsonObject
            val rawName = when {
                functionObj != null -> functionObj["name"]?.jsonPrimitive?.contentOrNull
                else -> obj["name"]?.jsonPrimitive?.contentOrNull
            }
            val name = rawName?.trim().orEmpty()
            if (name.isEmpty()) continue
            val description = when {
                functionObj != null -> functionObj["description"]?.jsonPrimitive?.contentOrNull
                else -> obj["description"]?.jsonPrimitive?.contentOrNull
            }?.trim()?.takeIf { it.isNotEmpty() } ?: "Client-side hosted tool"
            val parametersElement = when {
                functionObj != null -> functionObj["parameters"]
                else -> obj["parameters"]
            }
            val parameters = if (parametersElement != null && parametersElement !is JsonNull) {
                json.encodeToString(JsonElement.serializer(), parametersElement)
            } else {
                """{"type":"object","properties":{},"additionalProperties":true}"""
            }
            if (type.isNotEmpty() && type != "function") {
                continue
            }
            tools += ClientToolSpec(
                name = name,
                description = description,
                parameters = parameters,
            )
        }
        return tools
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

        dispatcher.register("chat.send", requiredScope = "operator.chat.send") { params, context ->
            val obj = params?.jsonObject ?: throw RpcException("invalid_params", "Missing params")
            val text = obj["message"]?.jsonPrimitive?.contentOrNull
                ?: obj["text"]?.jsonPrimitive?.contentOrNull
                ?: throw RpcException("invalid_params", "Missing message text")
            val sessionKey = obj["sessionKey"]?.jsonPrimitive?.contentOrNull ?: "ws:${context.connectionId}"
            val stopCommand = text.trim().equals("/stop", ignoreCase = true)
            val runId = obj["idempotencyKey"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

            val chatHandler = onChatSend ?: throw RpcException("internal_error", "Agent not configured")

            if (stopCommand) {
                val abortedRunIds = mutableListOf<String>()
                if (runId != null) {
                    if (abortActiveChatRun(runId, sessionKey, origin = "stop-command")) {
                        abortedRunIds += runId
                    }
                } else {
                    val matchingRunIds = activeChatSessions.entries
                        .filter { (_, activeSessionKey) -> activeSessionKey == sessionKey }
                        .map { (activeRunId, _) -> activeRunId }
                    matchingRunIds.forEach { activeRunId ->
                        if (abortActiveChatRun(activeRunId, sessionKey, origin = "stop-command")) {
                            abortedRunIds += activeRunId
                        }
                    }
                }
                return@register buildJsonObject {
                    put("ok", true)
                    put("aborted", abortedRunIds.isNotEmpty())
                    putJsonArray("runIds") {
                        abortedRunIds.forEach { add(JsonPrimitive(it)) }
                    }
                }
            }

            val effectiveRunId = runId ?: throw RpcException("invalid_params", "Missing idempotencyKey")
            val agentId = obj["agentId"]?.jsonPrimitive?.contentOrNull
            val channel = obj["channel"]?.jsonPrimitive?.contentOrNull
            val accountId = obj["accountId"]?.jsonPrimitive?.contentOrNull
            val to = obj["to"]?.jsonPrimitive?.contentOrNull
            val model = obj["model"]?.jsonPrimitive?.contentOrNull
            val deliver = obj["deliver"]?.jsonPrimitive?.booleanOrNull ?: true
            val clientTools = extractClientTools(obj["tools"])
            val functionCallOutputs = extractFunctionCallOutputs(obj["input"])

            val existingJob = activeChatJobs[effectiveRunId]
            if (existingJob?.isActive == true) {
                return@register buildJsonObject {
                    put("runId", effectiveRunId)
                    put("status", "in_flight")
                }
            }

            // Stream events for this run id.
            chatBuffers[effectiveRunId] = StringBuilder()
            chatSeqByRun[effectiveRunId] = 0
            val chatJob = serverScope.launch {
                try {
                    val startSeq = nextChatSeq(effectiveRunId)
                    broadcast(
                        "agent",
                        buildAgentEventPayload(
                            runId = effectiveRunId,
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
                        requestId = effectiveRunId,
                        clientTools = clientTools,
                        functionCallOutputs = functionCallOutputs,
                    )).collect { event ->
                        buildAgentStreamPayload(event, includeToolResults = false)?.let { mapped ->
                            val seq = nextChatSeq(effectiveRunId)
                            val agentPayload = buildAgentEventPayload(
                                runId = effectiveRunId,
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
                                        val cumulative = chatBuffers[effectiveRunId]?.apply { append(delta) }?.toString().orEmpty()
                                        broadcast(
                                            "chat",
                                            buildChatDeltaPayload(
                                                runId = effectiveRunId,
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
                                        val finalText = chatBuffers[effectiveRunId]?.toString().orEmpty()
                                        broadcast(
                                            "chat",
                                            buildChatFinalPayload(
                                                runId = effectiveRunId,
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
                                                runId = effectiveRunId,
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
                    if (!abortedChatRuns.contains(effectiveRunId)) {
                        throw cancelled
                    }
                } catch (e: Exception) {
                    val seq = nextChatSeq(effectiveRunId)
                    broadcast(
                        "agent",
                        buildAgentEventPayload(
                            runId = effectiveRunId,
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
                            runId = effectiveRunId,
                            sessionKey = sessionKey,
                            seq = seq,
                            error = e.message ?: "Unknown error",
                        ),
                    )
                } finally {
                    activeChatJobs.remove(effectiveRunId)
                    activeChatSessions.remove(effectiveRunId)
                    activeChatConnections.remove(effectiveRunId)
                    chatBuffers.remove(effectiveRunId)
                    chatSeqByRun.remove(effectiveRunId)
                    abortedChatRuns.remove(effectiveRunId)
                    persistedAbortRuns.remove(effectiveRunId)
                }
            }
            activeChatJobs[effectiveRunId] = chatJob
            activeChatSessions[effectiveRunId] = sessionKey
            activeChatConnections[effectiveRunId] = context.connectionId

            buildJsonObject {
                put("runId", effectiveRunId)
                put("status", "started")
            }
        }

        dispatcher.register("chat.abort", requiredScope = "operator.chat.abort") { params, _ ->
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
                if (abortActiveChatRun(runId, sessionKey, origin = "rpc")) {
                    abortedRunIds += runId
                }
            } else {
                val matchingRunIds = activeChatSessions.entries
                    .filter { (_, activeSessionKey) -> activeSessionKey == sessionKey }
                    .map { (activeRunId, _) -> activeRunId }
                for (activeRunId in matchingRunIds) {
                    if (abortActiveChatRun(activeRunId, sessionKey, origin = "rpc")) {
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

        dispatcher.register("config.get", requiredScope = "operator.config.read") { params, _ ->
            // Return sanitized config (no secrets)
            buildJsonObject { put("status", "ok") }
        }

        dispatcher.register("sessions.list", requiredScope = "operator.sessions.read") { _, _ ->
            // TODO: Wire to session store
            buildJsonObject { putJsonArray("sessions") {} }
        }

        dispatcher.register("models.list", requiredScope = "operator.models.read") { _, _ ->
            // TODO: Wire to provider registry
            buildJsonObject { putJsonArray("models") {} }
        }

        dispatcher.register("agents.list", requiredScope = "operator.agents.read") { _, _ ->
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

        dispatcher.register("devices.pair.issue", requiredScope = "operator.devices.pair") { params, _ ->
            val obj = params?.jsonObject ?: throw RpcException("invalid_params", "Missing params")
            val deviceId = obj["deviceId"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw RpcException("invalid_params", "Missing deviceId")
            val role = obj["role"]?.jsonPrimitive?.contentOrNull
            val ttlMs = obj["ttlMs"]?.jsonPrimitive?.longOrNull
            val requestedScopes = obj["scopes"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?.takeIf { it.isNotEmpty() }
                ?: setOf("operator.*")
            val issued = authenticator.issueDeviceToken(
                deviceId = deviceId,
                role = role,
                scopes = requestedScopes,
                ttlMs = ttlMs,
            )
            buildJsonObject {
                put("token", issued.token)
                put("deviceId", issued.deviceId)
                put("role", issued.role)
                putJsonArray("scopes") {
                    issued.scopes.forEach { add(JsonPrimitive(it)) }
                }
                issued.expiresAt?.let { put("expiresAt", it) }
            }
        }

        dispatcher.register("devices.pair.revoke", requiredScope = "operator.devices.pair") { params, _ ->
            val obj = params?.jsonObject ?: throw RpcException("invalid_params", "Missing params")
            val token = obj["token"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw RpcException("invalid_params", "Missing token")
            val revoked = authenticator.revokeDeviceToken(token)
            buildJsonObject {
                put("ok", revoked)
            }
        }
    }

    private fun nextChatSeq(runId: String): Int {
        val next = (chatSeqByRun[runId] ?: 0) + 1
        chatSeqByRun[runId] = next
        return next
    }

    private suspend fun abortActiveChatRun(
        runId: String,
        sessionKey: String,
        origin: String,
    ): Boolean {
        val activeSessionKey = activeChatSessions[runId] ?: return false
        if (activeSessionKey != sessionKey) return false

        abortedChatRuns += runId
        val seq = nextChatSeq(runId)
        val partialText = chatBuffers[runId]?.toString().orEmpty().trim()
        if (partialText.isNotEmpty() && persistedAbortRuns.add(runId)) {
            runCatching {
                onAbortPersistPartial?.invoke(
                    AbortPartialPersistParams(
                        sessionKey = sessionKey,
                        runId = runId,
                        text = partialText,
                        origin = origin,
                    ),
                )
            }
        }

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
                origin = origin,
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
        origin: String? = null,
    ): JsonObject {
        return buildJsonObject {
            put("runId", runId)
            put("sessionKey", sessionKey)
            put("seq", seq)
            put("state", "aborted")
            stopReason?.let { put("stopReason", it) }
            origin?.let { put("origin", it) }
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
                    event.pendingToolCalls?.takeIf { it.isNotEmpty() }?.let { pending ->
                        putJsonArray("pendingToolCalls") {
                            pending.forEach { tool ->
                                addJsonObject {
                                    put("id", tool.id)
                                    put("name", tool.name)
                                    put("arguments", tool.arguments)
                                }
                            }
                        }
                    }
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
