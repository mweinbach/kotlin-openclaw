package ai.openclaw.runtime.gateway

import ai.openclaw.core.model.*
import ai.openclaw.core.security.AuditLog
import ai.openclaw.core.security.AuditEntry
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Webhook manager for receiving external HTTP triggers that start agent sessions.
 * Ported from src/gateway/hooks.ts + hooks-mapping.ts
 */
class WebhookManager(
    private val config: HooksConfig?,
    private val globalConfig: OpenClawConfig? = null,
    private val auditLog: AuditLog = AuditLog(),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = false },
) {
    /** Callback invoked when a webhook triggers an agent session. */
    var onAgentDispatch: (suspend (WebhookDispatchParams) -> Flow<AcpRuntimeEvent>)? = null

    data class WebhookDispatchParams(
        val sessionKey: String,
        val agentId: String?,
        val message: String,
        val name: String,
        val channel: String?,
        val to: String?,
        val model: String?,
        val deliver: Boolean,
        val requestId: String,
    )

    // Resolved config
    private val enabled: Boolean = config?.enabled == true
    private val basePath: String
    private val token: String?
    private val maxBodyBytes: Long
    private val defaultSessionKey: String?
    private val allowRequestSessionKey: Boolean
    private val allowedSessionKeyPrefixes: List<String>?
    private val allowedAgentIds: Set<String>?
    private val mappings: List<ResolvedMapping>

    // Rate limiting per-IP
    private val rateLimitState = ConcurrentHashMap<String, RateLimitBucket>()

    private data class RateLimitBucket(
        val count: AtomicLong = AtomicLong(0),
        @Volatile var windowStart: Long = System.currentTimeMillis(),
    )

    data class ResolvedMapping(
        val id: String,
        val matchPath: String?,
        val matchSource: String?,
        val action: String, // "wake" or "agent"
        val name: String?,
        val agentId: String?,
        val sessionKey: String?,
        val messageTemplate: String?,
        val textTemplate: String?,
        val deliver: Boolean?,
        val channel: String?,
        val to: String?,
        val model: String?,
        val timeoutSeconds: Int?,
    )

    init {
        val rawPath = config?.path?.trim() ?: DEFAULT_HOOKS_PATH
        val withSlash = if (rawPath.startsWith("/")) rawPath else "/$rawPath"
        basePath = if (withSlash.length > 1) withSlash.trimEnd('/') else withSlash
        token = config?.token?.trim()
        maxBodyBytes = config?.maxBodyBytes?.takeIf { it > 0 } ?: DEFAULT_MAX_BODY_BYTES
        defaultSessionKey = config?.defaultSessionKey?.trim()?.ifEmpty { null }
        allowRequestSessionKey = config?.allowRequestSessionKey == true
        allowedSessionKeyPrefixes = config?.allowedSessionKeyPrefixes
            ?.mapNotNull { it.trim().lowercase().ifEmpty { null } }
            ?.takeIf { it.isNotEmpty() }
        allowedAgentIds = config?.allowedAgentIds
            ?.mapNotNull { it.trim().ifEmpty { null } }
            ?.takeIf { it.isNotEmpty() }
            ?.toSet()

        mappings = resolveMappings(config?.mappings)
    }

    val isEnabled: Boolean get() = enabled

    /**
     * Install webhook routes into a Ktor routing block.
     */
    fun Routing.configureWebhookRoutes() {
        if (!enabled) return

        // POST /hooks/agent - generic agent dispatch
        post("$basePath/agent") {
            handleAgentHook(call)
        }

        // POST /hooks/{mappingId} - mapping-based dispatch
        post("$basePath/{mappingId}") {
            val mappingId = call.parameters["mappingId"]
            if (mappingId == "agent") {
                handleAgentHook(call)
            } else {
                handleMappingHook(call, mappingId ?: "")
            }
        }

        // GET /hooks - list registered mappings
        get(basePath) {
            if (!authenticateRequest(call)) return@get
            call.respondText(
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("enabled", true)
                        put("basePath", basePath)
                        putJsonArray("mappings") {
                            for (m in mappings) {
                                addJsonObject {
                                    put("id", m.id)
                                    m.matchPath?.let { put("matchPath", it) }
                                    put("action", m.action)
                                }
                            }
                        }
                    },
                ),
                ContentType.Application.Json,
            )
        }
    }

    private suspend fun handleAgentHook(call: ApplicationCall) {
        // Rate limit
        val clientIp = call.request.origin.remoteAddress
        if (isRateLimited(clientIp)) {
            auditLog.log(AuditEntry(event = "webhook_rate_limited", details = "ip=$clientIp"))
            call.respondText(
                """{"error":"rate limited"}""",
                ContentType.Application.Json,
                HttpStatusCode.TooManyRequests,
            )
            return
        }

        // Authenticate
        if (!authenticateRequest(call)) return

        // Read body
        val bodyResult = readBody(call)
        if (bodyResult == null) return

        val payload = bodyResult.jsonObject

        // Validate message
        val message = payload["message"]?.jsonPrimitive?.contentOrNull?.trim()
        if (message.isNullOrEmpty()) {
            call.respondText(
                """{"error":"message required"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return
        }

        val name = payload["name"]?.jsonPrimitive?.contentOrNull?.trim() ?: "Hook"
        val agentId = payload["agentId"]?.jsonPrimitive?.contentOrNull?.trim()
        val deliver = payload["deliver"]?.jsonPrimitive?.booleanOrNull != false
        val channel = payload["channel"]?.jsonPrimitive?.contentOrNull?.trim()
        val to = payload["to"]?.jsonPrimitive?.contentOrNull?.trim()
        val model = payload["model"]?.jsonPrimitive?.contentOrNull?.trim()
        val requestedSessionKey = payload["sessionKey"]?.jsonPrimitive?.contentOrNull?.trim()

        // Check agent policy
        if (agentId != null && allowedAgentIds != null && agentId !in allowedAgentIds) {
            call.respondText(
                """{"error":"agentId is not allowed by hooks.allowedAgentIds"}""",
                ContentType.Application.Json,
                HttpStatusCode.Forbidden,
            )
            return
        }

        // Resolve session key
        val sessionKey = resolveSessionKey(requestedSessionKey, "request")
        if (sessionKey == null) {
            call.respondText(
                """{"error":"sessionKey policy violation"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return
        }

        val requestId = UUID.randomUUID().toString()

        auditLog.log(AuditEntry(
            event = "webhook_agent_dispatch",
            agentId = agentId,
            sessionKey = sessionKey,
            details = "name=$name channel=$channel",
        ))

        val handler = onAgentDispatch
        if (handler == null) {
            call.respondText(
                """{"error":"agent dispatch not configured"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
            return
        }

        val responseText = StringBuilder()
        try {
            handler(WebhookDispatchParams(
                sessionKey = sessionKey,
                agentId = agentId,
                message = message,
                name = name,
                channel = channel,
                to = to,
                model = model,
                deliver = deliver,
                requestId = requestId,
            )).collect { event ->
                when (event) {
                    is AcpRuntimeEvent.TextDelta -> responseText.append(event.text)
                    else -> {} // non-text events ignored for webhook response
                }
            }

            call.respondText(
                json.encodeToString(JsonObject.serializer(), buildJsonObject {
                    put("ok", true)
                    put("requestId", requestId)
                    put("sessionKey", sessionKey)
                    put("response", responseText.toString())
                }),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            call.respondText(
                json.encodeToString(JsonObject.serializer(), buildJsonObject {
                    put("ok", false)
                    put("requestId", requestId)
                    put("error", e.message ?: "Internal error")
                }),
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    private suspend fun handleMappingHook(call: ApplicationCall, mappingId: String) {
        val clientIp = call.request.origin.remoteAddress
        if (isRateLimited(clientIp)) {
            auditLog.log(AuditEntry(event = "webhook_rate_limited", details = "ip=$clientIp mapping=$mappingId"))
            call.respondText(
                """{"error":"rate limited"}""",
                ContentType.Application.Json,
                HttpStatusCode.TooManyRequests,
            )
            return
        }

        if (!authenticateRequest(call)) return

        // Find matching mapping
        val mapping = mappings.firstOrNull { it.id == mappingId || it.matchPath == mappingId }
        if (mapping == null) {
            call.respondText(
                """{"error":"unknown mapping: $mappingId"}""",
                ContentType.Application.Json,
                HttpStatusCode.NotFound,
            )
            return
        }

        val bodyResult = readBody(call)
        if (bodyResult == null) return

        val payload = bodyResult.jsonObject

        // Apply template rendering
        val messageTemplate = mapping.messageTemplate ?: "{{message}}"
        val message = renderTemplate(messageTemplate, payload)
        if (message.isBlank()) {
            call.respondText(
                """{"error":"rendered message is empty"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return
        }

        val name = mapping.name ?: "Hook"
        val agentId = mapping.agentId
        val deliver = mapping.deliver != false
        val channel = mapping.channel
        val to = mapping.to
        val model = mapping.model

        val sessionKey = resolveSessionKey(mapping.sessionKey?.let { renderTemplate(it, payload) }, "mapping")
            ?: resolveSessionKey(null, "mapping")
        if (sessionKey == null) {
            call.respondText(
                """{"error":"sessionKey policy violation"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return
        }

        val requestId = UUID.randomUUID().toString()

        auditLog.log(AuditEntry(
            event = "webhook_mapping_dispatch",
            agentId = agentId,
            sessionKey = sessionKey,
            details = "mapping=${mapping.id} name=$name",
        ))

        val handler = onAgentDispatch
        if (handler == null) {
            call.respondText(
                """{"error":"agent dispatch not configured"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
            return
        }

        val responseText = StringBuilder()
        try {
            handler(WebhookDispatchParams(
                sessionKey = sessionKey,
                agentId = agentId,
                message = message,
                name = name,
                channel = channel,
                to = to,
                model = model,
                deliver = deliver,
                requestId = requestId,
            )).collect { event ->
                when (event) {
                    is AcpRuntimeEvent.TextDelta -> responseText.append(event.text)
                    else -> {}
                }
            }

            call.respondText(
                json.encodeToString(JsonObject.serializer(), buildJsonObject {
                    put("ok", true)
                    put("requestId", requestId)
                    put("sessionKey", sessionKey)
                    put("mappingId", mapping.id)
                    put("response", responseText.toString())
                }),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            call.respondText(
                json.encodeToString(JsonObject.serializer(), buildJsonObject {
                    put("ok", false)
                    put("requestId", requestId)
                    put("error", e.message ?: "Internal error")
                }),
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // --- Authentication ---

    private suspend fun authenticateRequest(call: ApplicationCall): Boolean {
        if (token == null) return true

        val authHeader = call.request.header("Authorization")
        val bearerToken = if (authHeader != null && authHeader.lowercase().startsWith("bearer ")) {
            authHeader.substring(7).trim()
        } else {
            null
        }
        val headerToken = call.request.header("X-OpenClaw-Token")?.trim()
        val providedToken = bearerToken ?: headerToken

        if (providedToken == null || !safeCompare(providedToken, token)) {
            auditLog.log(AuditEntry(
                event = "webhook_auth_failed",
                details = "ip=${call.request.origin.remoteAddress}",
            ))
            call.respondText(
                """{"error":"unauthorized"}""",
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized,
            )
            return false
        }
        return true
    }

    private fun safeCompare(a: String, b: String): Boolean {
        val aHash = MessageDigest.getInstance("SHA-256").digest(a.toByteArray())
        val bHash = MessageDigest.getInstance("SHA-256").digest(b.toByteArray())
        return MessageDigest.isEqual(aHash, bHash)
    }

    // --- Rate Limiting ---

    private fun isRateLimited(ip: String): Boolean {
        val now = System.currentTimeMillis()
        val bucket = rateLimitState.getOrPut(ip) { RateLimitBucket() }

        if (now - bucket.windowStart > RATE_LIMIT_WINDOW_MS) {
            bucket.count.set(0)
            bucket.windowStart = now
        }

        return bucket.count.incrementAndGet() > RATE_LIMIT_MAX_REQUESTS
    }

    // --- Session Key Resolution ---

    private fun resolveSessionKey(requested: String?, source: String): String? {
        if (!requested.isNullOrBlank()) {
            if (source == "request" && !allowRequestSessionKey) {
                return null
            }
            if (allowedSessionKeyPrefixes != null) {
                val lower = requested.lowercase()
                if (allowedSessionKeyPrefixes.none { lower.startsWith(it) }) {
                    return null
                }
            }
            return requested
        }

        if (defaultSessionKey != null) return defaultSessionKey

        val generated = "hook:${UUID.randomUUID()}"
        if (allowedSessionKeyPrefixes != null) {
            val lower = generated.lowercase()
            if (allowedSessionKeyPrefixes.none { lower.startsWith(it) }) {
                return null
            }
        }
        return generated
    }

    // --- Body Reading ---

    private suspend fun readBody(call: ApplicationCall): JsonElement? {
        val bodyText = try {
            val text = call.receiveText()
            if (text.length > maxBodyBytes) {
                call.respondText(
                    """{"error":"payload too large"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.PayloadTooLarge,
                )
                return null
            }
            text
        } catch (e: Exception) {
            call.respondText(
                """{"error":"failed to read body"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return null
        }

        if (bodyText.isBlank()) return buildJsonObject {}

        return try {
            json.parseToJsonElement(bodyText)
        } catch (e: Exception) {
            call.respondText(
                """{"error":"invalid JSON"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            null
        }
    }

    // --- Mapping Resolution ---

    private fun resolveMappings(rawMappings: List<HookMappingConfig>?): List<ResolvedMapping> {
        return rawMappings?.mapIndexed { index, mapping ->
            ResolvedMapping(
                id = mapping.id?.trim() ?: "mapping-${index + 1}",
                matchPath = mapping.name?.trim(),
                matchSource = null,
                action = mapping.action ?: "agent",
                name = mapping.name,
                agentId = mapping.agentId?.trim(),
                sessionKey = mapping.sessionKey,
                messageTemplate = mapping.messageTemplate,
                textTemplate = mapping.textTemplate,
                deliver = mapping.deliver,
                channel = mapping.channel,
                to = mapping.to,
                model = mapping.model,
                timeoutSeconds = mapping.timeoutSeconds,
            )
        } ?: emptyList()
    }

    // --- Template Rendering ---

    companion object {
        private const val DEFAULT_HOOKS_PATH = "/hooks"
        private const val DEFAULT_MAX_BODY_BYTES = 256L * 1024
        private const val RATE_LIMIT_WINDOW_MS = 60_000L
        private const val RATE_LIMIT_MAX_REQUESTS = 60L

        /**
         * Render a Mustache-style template against a JSON payload.
         * Supports `{{field}}` and `{{nested.field}}` and `{{array[0].field}}`.
         */
        fun renderTemplate(template: String, payload: JsonElement): String {
            if (template.isBlank()) return ""
            return TEMPLATE_REGEX.replace(template) { match ->
                val expr = match.groupValues[1].trim()
                resolveJsonPath(payload, expr)?.let { element ->
                    when (element) {
                        is JsonPrimitive -> element.contentOrNull ?: element.toString()
                        else -> element.toString()
                    }
                } ?: ""
            }
        }

        private val TEMPLATE_REGEX = Regex("""\{\{\s*([^}]+)\s*}}""")

        private val BLOCKED_PATH_KEYS = setOf("__proto__", "prototype", "constructor")

        private fun resolveJsonPath(root: JsonElement, pathExpr: String): JsonElement? {
            if (pathExpr.isBlank()) return null
            val parts = parsePathExpr(pathExpr)
            var current: JsonElement? = root
            for (part in parts) {
                if (current == null) return null
                when (part) {
                    is PathPart.Key -> {
                        if (part.key in BLOCKED_PATH_KEYS) return null
                        current = (current as? JsonObject)?.get(part.key)
                    }
                    is PathPart.Index -> {
                        current = (current as? JsonArray)?.getOrNull(part.index)
                    }
                }
            }
            return current
        }

        private sealed class PathPart {
            data class Key(val key: String) : PathPart()
            data class Index(val index: Int) : PathPart()
        }

        private val PATH_PART_REGEX = Regex("""([^.\[\]]+)|\[(\d+)]""")

        private fun parsePathExpr(expr: String): List<PathPart> {
            return PATH_PART_REGEX.findAll(expr).map { m ->
                val key = m.groupValues[1]
                val idx = m.groupValues[2]
                if (idx.isNotEmpty()) PathPart.Index(idx.toInt())
                else PathPart.Key(key)
            }.toList()
        }
    }
}
