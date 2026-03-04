package ai.openclaw.runtime.gateway

import ai.openclaw.core.model.GatewayAuthConfig
import ai.openclaw.core.model.GatewayAuthMode
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Authentication context for a connected client.
 */
data class AuthContext(
    val connectionId: String,
    val authenticated: Boolean,
    val authMode: AuthMode,
    val scopes: Set<String> = emptySet(),
    val deviceId: String? = null,
    val role: String? = null,
    val ip: String? = null,
    val trustedProxyUser: String? = null,
) {
    fun hasScope(scope: String): Boolean {
        if ("admin" in scopes) return true
        if (scope in scopes) return true
        val segments = scope.split('.')
        if (segments.size > 1) {
            val wildcard = segments.dropLast(1).joinToString(".") + ".*"
            if (wildcard in scopes) return true
        }
        return false
    }
}

enum class AuthMode {
    NONE,
    TOKEN,
    PASSWORD,
    DEVICE,
    TRUSTED_PROXY,
    LOCAL,
}

data class DevicePairingRecord(
    val token: String,
    val deviceId: String,
    val role: String?,
    val scopes: Set<String>,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long? = null,
)

class DevicePairingStore {
    private val records = ConcurrentHashMap<String, DevicePairingRecord>()

    fun issueToken(
        deviceId: String,
        role: String? = null,
        scopes: Set<String> = setOf("operator.*"),
        ttlMs: Long? = null,
    ): DevicePairingRecord {
        val token = "dvt_${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val record = DevicePairingRecord(
            token = token,
            deviceId = deviceId,
            role = role,
            scopes = scopes,
            createdAt = now,
            expiresAt = ttlMs?.let { now + it },
        )
        records[token] = record
        return record
    }

    fun verify(token: String): DevicePairingRecord? {
        val record = records[token] ?: return null
        val expiresAt = record.expiresAt
        if (expiresAt != null && expiresAt <= System.currentTimeMillis()) {
            records.remove(token)
            return null
        }
        return record
    }

    fun revoke(token: String): Boolean = records.remove(token) != null
}

/**
 * Authenticator for gateway connections.
 */
class GatewayAuthenticator(
    private val config: GatewayAuthConfig? = null,
    private val pairingStore: DevicePairingStore = DevicePairingStore(),
) {
    private val rateLimiter = AuthRateLimiter(
        maxAttempts = config?.rateLimit?.maxAttempts ?: 10,
        windowMs = config?.rateLimit?.windowMs ?: 60_000,
        lockoutMs = config?.rateLimit?.lockoutMs ?: 300_000,
    )

    fun mode(): AuthMode = resolveAuthMode().mode

    fun authenticate(params: ConnectParams): AuthResult {
        val resolved = resolveAuthMode()
        if (resolved.misconfiguredReason != null) {
            return AuthResult.Denied(resolved.misconfiguredReason, resolved.mode)
        }

        val ip = params.ip ?: "unknown"
        val exemptLoopback = config?.rateLimit?.exemptLoopback ?: false
        val shouldRateLimit = !(exemptLoopback && isLoopback(ip))
        if (shouldRateLimit && rateLimiter.isLimited(ip)) {
            return AuthResult.Denied("Rate limited", resolved.mode)
        }

        params.deviceToken?.trim()?.takeIf { it.isNotEmpty() }?.let { token ->
            val record = pairingStore.verify(token)
            if (record != null) {
                return AuthResult.Allowed(
                    AuthContext(
                        connectionId = params.connectionId,
                        authenticated = true,
                        authMode = AuthMode.DEVICE,
                        scopes = record.scopes,
                        deviceId = record.deviceId,
                        role = record.role,
                        ip = ip,
                    ),
                )
            }
            if (shouldRateLimit) {
                rateLimiter.recordFailure(ip)
            }
            return AuthResult.Denied("Invalid device token", AuthMode.DEVICE)
        }

        return when (resolved.mode) {
            AuthMode.NONE -> AuthResult.Allowed(
                AuthContext(
                    connectionId = params.connectionId,
                    authenticated = true,
                    authMode = AuthMode.NONE,
                    scopes = setOf("admin"),
                    ip = ip,
                ),
            )

            AuthMode.TOKEN -> {
                val expected = config?.token.orEmpty()
                val provided = params.token.orEmpty()
                if (provided.isNotBlank() && safeCompare(provided, expected)) {
                    AuthResult.Allowed(
                        AuthContext(
                            connectionId = params.connectionId,
                            authenticated = true,
                            authMode = AuthMode.TOKEN,
                            scopes = normalizeRequestedScopes(params.scopes),
                            ip = ip,
                        ),
                    )
                } else {
                    if (shouldRateLimit) {
                        rateLimiter.recordFailure(ip)
                    }
                    AuthResult.Denied("Invalid token", AuthMode.TOKEN)
                }
            }

            AuthMode.PASSWORD -> {
                val expected = config?.password.orEmpty()
                val provided = params.password.orEmpty()
                if (provided.isNotBlank() && safeCompare(provided, expected)) {
                    AuthResult.Allowed(
                        AuthContext(
                            connectionId = params.connectionId,
                            authenticated = true,
                            authMode = AuthMode.PASSWORD,
                            scopes = normalizeRequestedScopes(params.scopes),
                            ip = ip,
                        ),
                    )
                } else {
                    if (shouldRateLimit) {
                        rateLimiter.recordFailure(ip)
                    }
                    AuthResult.Denied("Invalid password", AuthMode.PASSWORD)
                }
            }

            AuthMode.LOCAL -> {
                if (isLoopback(ip)) {
                    AuthResult.Allowed(
                        AuthContext(
                            connectionId = params.connectionId,
                            authenticated = true,
                            authMode = AuthMode.LOCAL,
                            scopes = setOf("admin"),
                            ip = ip,
                        ),
                    )
                } else {
                    AuthResult.Denied("Not a local connection", AuthMode.LOCAL)
                }
            }

            AuthMode.TRUSTED_PROXY -> {
                val trusted = authenticateTrustedProxy(params)
                if (trusted != null) {
                    AuthResult.Allowed(trusted)
                } else {
                    if (shouldRateLimit) {
                        rateLimiter.recordFailure(ip)
                    }
                    AuthResult.Denied("Trusted proxy headers missing or invalid", AuthMode.TRUSTED_PROXY)
                }
            }

            AuthMode.DEVICE -> AuthResult.Denied("Device auth requires a valid device token", AuthMode.DEVICE)
        }
    }

    fun issueDeviceToken(
        deviceId: String,
        role: String? = null,
        scopes: Set<String> = setOf("operator.*"),
        ttlMs: Long? = null,
    ): DevicePairingRecord {
        return pairingStore.issueToken(
            deviceId = deviceId,
            role = role,
            scopes = scopes,
            ttlMs = ttlMs,
        )
    }

    fun revokeDeviceToken(token: String): Boolean = pairingStore.revoke(token)

    private fun authenticateTrustedProxy(params: ConnectParams): AuthContext? {
        val trustedProxy = config?.trustedProxy ?: return null
        val headers = params.headers
        val userHeader = trustedProxy.userHeader.trim().ifEmpty { return null }
        val user = headers[userHeader]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val requiredHeaders = trustedProxy.requiredHeaders.orEmpty()
        val hasRequiredHeaders = requiredHeaders.all { headerName ->
            headers[headerName]?.trim()?.isNotEmpty() == true
        }
        if (!hasRequiredHeaders) {
            return null
        }
        val allowUsers = trustedProxy.allowUsers.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
        if (allowUsers.isNotEmpty() && user !in allowUsers) {
            return null
        }
        return AuthContext(
            connectionId = params.connectionId,
            authenticated = true,
            authMode = AuthMode.TRUSTED_PROXY,
            scopes = normalizeRequestedScopes(params.scopes),
            ip = params.ip,
            trustedProxyUser = user,
            role = params.role,
        )
    }

    private data class ResolvedAuthMode(
        val mode: AuthMode,
        val misconfiguredReason: String? = null,
    )

    private fun resolveAuthMode(): ResolvedAuthMode {
        return when (config?.mode) {
            GatewayAuthMode.TOKEN -> {
                if (config.token.isNullOrBlank()) {
                    ResolvedAuthMode(AuthMode.TOKEN, "Auth mode token requires gateway.auth.token")
                } else {
                    ResolvedAuthMode(AuthMode.TOKEN)
                }
            }

            GatewayAuthMode.PASSWORD -> {
                if (config.password.isNullOrBlank()) {
                    ResolvedAuthMode(AuthMode.PASSWORD, "Auth mode password requires gateway.auth.password")
                } else {
                    ResolvedAuthMode(AuthMode.PASSWORD)
                }
            }

            GatewayAuthMode.TRUSTED_PROXY -> {
                if (config.trustedProxy == null) {
                    ResolvedAuthMode(
                        AuthMode.TRUSTED_PROXY,
                        "Auth mode trusted-proxy requires gateway.auth.trustedProxy",
                    )
                } else {
                    ResolvedAuthMode(AuthMode.TRUSTED_PROXY)
                }
            }

            GatewayAuthMode.NONE, null -> {
                when {
                    !config?.token.isNullOrBlank() -> ResolvedAuthMode(AuthMode.TOKEN)
                    !config?.password.isNullOrBlank() -> ResolvedAuthMode(AuthMode.PASSWORD)
                    else -> ResolvedAuthMode(AuthMode.NONE)
                }
            }
        }
    }

    private fun normalizeRequestedScopes(scopes: Set<String>?): Set<String> {
        val normalized = scopes.orEmpty()
            .mapNotNull { scope ->
                val trimmed = scope.trim()
                if (trimmed.isEmpty()) null else trimmed
            }
            .toSet()
        return if (normalized.isEmpty()) setOf("operator.*") else normalized
    }

    private fun isLoopback(ip: String): Boolean {
        val normalized = ip.trim().lowercase()
        return normalized == "127.0.0.1" || normalized == "::1" || normalized == "localhost"
    }

    /**
     * Constant-time string comparison to prevent timing attacks.
     */
    private fun safeCompare(a: String, b: String): Boolean {
        val aHash = MessageDigest.getInstance("SHA-256").digest(a.toByteArray())
        val bHash = MessageDigest.getInstance("SHA-256").digest(b.toByteArray())
        return MessageDigest.isEqual(aHash, bHash)
    }
}

data class ConnectParams(
    val connectionId: String,
    val token: String? = null,
    val password: String? = null,
    val deviceId: String? = null,
    val deviceToken: String? = null,
    val scopes: Set<String>? = null,
    val ip: String? = null,
    val role: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

sealed class AuthResult {
    data class Allowed(val context: AuthContext) : AuthResult()
    data class Denied(val reason: String, val mode: AuthMode) : AuthResult()
}

/**
 * Per-IP rate limiter for authentication attempts.
 */
class AuthRateLimiter(
    private val maxAttempts: Int = 10,
    private val windowMs: Long = 60_000,
    private val lockoutMs: Long = 300_000,
) {
    private data class IpState(
        val failures: MutableList<Long> = mutableListOf(),
        @Volatile var lockedUntil: Long = 0,
    )

    private val ipStates = ConcurrentHashMap<String, IpState>()

    fun isLimited(ip: String): Boolean {
        val state = ipStates[ip] ?: return false
        val now = System.currentTimeMillis()
        if (state.lockedUntil > now) return true
        synchronized(state) {
            state.failures.removeIf { now - it > windowMs }
        }
        return false
    }

    fun recordFailure(ip: String) {
        val now = System.currentTimeMillis()
        val state = ipStates.getOrPut(ip) { IpState() }

        synchronized(state) {
            state.failures.add(now)
            state.failures.removeIf { now - it > windowMs }

            if (state.failures.size >= maxAttempts) {
                state.lockedUntil = now + lockoutMs
                state.failures.clear()
            }
        }
    }
}
