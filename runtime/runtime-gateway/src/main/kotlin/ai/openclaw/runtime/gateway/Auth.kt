package ai.openclaw.runtime.gateway

import ai.openclaw.core.model.GatewayAuthConfig
import ai.openclaw.core.model.GatewayAuthMode
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Authentication context for a connected client.
 * Ported from src/gateway/server/ws-connection/auth-context.ts
 */
data class AuthContext(
    val connectionId: String,
    val authenticated: Boolean,
    val authMode: AuthMode,
    val scopes: Set<String> = emptySet(),
    val deviceId: String? = null,
    val role: String? = null,
    val ip: String? = null,
) {
    fun hasScope(scope: String): Boolean =
        "admin" in scopes || scope in scopes
}

enum class AuthMode {
    NONE,
    TOKEN,
    PASSWORD,
    DEVICE,
    TAILSCALE,
    LOCAL,
}

/**
 * Authenticator for gateway connections.
 * Ported from src/gateway/auth.ts
 */
class GatewayAuthenticator(
    private val config: GatewayAuthConfig? = null,
) {
    private val rateLimiter = AuthRateLimiter()

    fun authenticate(params: ConnectParams): AuthResult {
        val mode = resolveAuthMode()

        // Rate limit check
        val ip = params.ip ?: "unknown"
        if (rateLimiter.isLimited(ip)) {
            return AuthResult.Denied("Rate limited", AuthMode.NONE)
        }

        return when (mode) {
            AuthMode.NONE -> AuthResult.Allowed(
                AuthContext(
                    connectionId = params.connectionId,
                    authenticated = true,
                    authMode = AuthMode.NONE,
                    scopes = setOf("admin"),
                )
            )

            AuthMode.TOKEN -> {
                val expected = config?.token
                if (expected != null && safeCompare(params.token ?: "", expected)) {
                    AuthResult.Allowed(
                        AuthContext(
                            connectionId = params.connectionId,
                            authenticated = true,
                            authMode = AuthMode.TOKEN,
                            scopes = params.scopes ?: setOf("admin"),
                            ip = ip,
                        )
                    )
                } else {
                    rateLimiter.recordFailure(ip)
                    AuthResult.Denied("Invalid token", AuthMode.TOKEN)
                }
            }

            AuthMode.PASSWORD -> {
                val expected = config?.password
                if (expected != null && safeCompare(params.password ?: "", expected)) {
                    AuthResult.Allowed(
                        AuthContext(
                            connectionId = params.connectionId,
                            authenticated = true,
                            authMode = AuthMode.PASSWORD,
                            scopes = params.scopes ?: setOf("admin"),
                            ip = ip,
                        )
                    )
                } else {
                    rateLimiter.recordFailure(ip)
                    AuthResult.Denied("Invalid password", AuthMode.PASSWORD)
                }
            }

            AuthMode.LOCAL -> {
                val isLocal = ip == "127.0.0.1" || ip == "::1" || ip == "localhost"
                if (isLocal) {
                    AuthResult.Allowed(
                        AuthContext(
                            connectionId = params.connectionId,
                            authenticated = true,
                            authMode = AuthMode.LOCAL,
                            scopes = setOf("admin"),
                            ip = ip,
                        )
                    )
                } else {
                    AuthResult.Denied("Not a local connection", AuthMode.LOCAL)
                }
            }

            else -> AuthResult.Denied("Unsupported auth mode: $mode", mode)
        }
    }

    private fun resolveAuthMode(): AuthMode {
        val mode = config?.mode
        return when {
            mode == GatewayAuthMode.TOKEN && config?.token != null -> AuthMode.TOKEN
            mode == GatewayAuthMode.PASSWORD && config?.password != null -> AuthMode.PASSWORD
            mode == GatewayAuthMode.TRUSTED_PROXY -> AuthMode.TAILSCALE
            config?.token != null -> AuthMode.TOKEN
            config?.password != null -> AuthMode.PASSWORD
            else -> AuthMode.NONE
        }
    }

    /**
     * Constant-time string comparison to prevent timing attacks.
     */
    private fun safeCompare(a: String, b: String): Boolean {
        val aBytes = a.toByteArray()
        val bBytes = b.toByteArray()
        val aHash = MessageDigest.getInstance("SHA-256").digest(aBytes)
        val bHash = MessageDigest.getInstance("SHA-256").digest(bBytes)
        return MessageDigest.isEqual(aHash, bHash)
    }
}

data class ConnectParams(
    val connectionId: String,
    val token: String? = null,
    val password: String? = null,
    val deviceId: String? = null,
    val scopes: Set<String>? = null,
    val ip: String? = null,
    val role: String? = null,
)

sealed class AuthResult {
    data class Allowed(val context: AuthContext) : AuthResult()
    data class Denied(val reason: String, val mode: AuthMode) : AuthResult()
}

/**
 * Per-IP rate limiter for authentication attempts.
 * Ported from src/gateway/auth-rate-limit.ts
 */
class AuthRateLimiter(
    private val maxAttempts: Int = 10,
    private val windowMs: Long = 60_000,
    private val lockoutMs: Long = 300_000,
) {
    private data class IpState(
        val failures: MutableList<Long> = mutableListOf(),
        var lockedUntil: Long = 0,
    )

    private val ipStates = ConcurrentHashMap<String, IpState>()

    fun isLimited(ip: String): Boolean {
        val state = ipStates[ip] ?: return false
        val now = System.currentTimeMillis()

        if (state.lockedUntil > now) return true

        // Clean old failures outside window
        state.failures.removeIf { now - it > windowMs }
        return false
    }

    fun recordFailure(ip: String) {
        val now = System.currentTimeMillis()
        val state = ipStates.getOrPut(ip) { IpState() }
        state.failures.add(now)

        // Clean old failures
        state.failures.removeIf { now - it > windowMs }

        if (state.failures.size >= maxAttempts) {
            state.lockedUntil = now + lockoutMs
            state.failures.clear()
        }
    }
}
