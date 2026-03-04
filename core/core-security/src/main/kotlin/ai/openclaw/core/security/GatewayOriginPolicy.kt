package ai.openclaw.core.security

import java.net.URI

data class GatewayOriginPolicyConfig(
    val allowedOrigins: List<String> = emptyList(),
    val allowHostHeaderFallback: Boolean = false,
)

class GatewayOriginPolicy(
    private val config: GatewayOriginPolicyConfig = GatewayOriginPolicyConfig(),
) {
    fun isAllowed(
        originHeader: String?,
        hostHeader: String?,
        remoteHost: String?,
    ): Boolean {
        val origin = originHeader?.trim().orEmpty()
        val normalizedAllowed = config.allowedOrigins
            .mapNotNull { normalizeOrigin(it) }
            .toSet()

        if (origin.isBlank()) {
            return isLoopbackHost(remoteHost)
        }

        val normalizedOrigin = normalizeOrigin(origin) ?: return false
        if (normalizedAllowed.isEmpty()) {
            return isLoopbackOrigin(normalizedOrigin)
        }
        if (normalizedAllowed.contains(normalizedOrigin)) {
            return true
        }

        if (config.allowHostHeaderFallback && !hostHeader.isNullOrBlank()) {
            val host = hostHeader.substringBefore(':').trim().lowercase()
            val originHost = hostFromOrigin(normalizedOrigin)
            if (host.isNotBlank() && originHost == host) {
                return true
            }
        }
        return isLoopbackOrigin(normalizedOrigin)
    }

    private fun normalizeOrigin(raw: String): String? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val port = when {
            uri.port > 0 -> uri.port
            scheme == "https" -> 443
            scheme == "http" -> 80
            else -> return null
        }
        return "$scheme://$host:$port"
    }

    private fun hostFromOrigin(origin: String): String {
        return runCatching { URI(origin).host?.lowercase().orEmpty() }.getOrDefault("")
    }

    private fun isLoopbackOrigin(origin: String): Boolean {
        val host = hostFromOrigin(origin)
        return isLoopbackHost(host)
    }

    private fun isLoopbackHost(host: String?): Boolean {
        val value = host?.trim()?.lowercase().orEmpty()
        return value == "localhost" || value == "127.0.0.1" || value == "::1"
    }
}
