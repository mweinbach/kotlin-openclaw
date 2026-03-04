package ai.openclaw.runtime.gateway

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * Gateway frame protocol types and dispatcher.
 * Mirrors OpenClaw gateway ws frame topology: req/res/event.
 */

@Serializable
data class GatewayRequestFrame(
    val type: String = "req",
    val id: String,
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
data class GatewayErrorFrame(
    val code: String,
    val message: String,
    val details: JsonElement? = null,
    val retryable: Boolean? = null,
)

@Serializable
data class GatewayResponseFrame(
    val type: String = "res",
    val id: String,
    val ok: Boolean,
    val payload: JsonElement? = null,
    val error: GatewayErrorFrame? = null,
)

@Serializable
data class GatewayEventFrame(
    val type: String = "event",
    val event: String,
    val payload: JsonElement? = null,
    val seq: Long? = null,
    val stateVersion: JsonElement? = null,
)

/**
 * Handler for a gateway request method.
 */
fun interface RpcMethodHandler {
    suspend fun handle(params: JsonElement?, context: RpcContext): JsonElement?
}

/**
 * Context available to every request handler.
 */
data class RpcContext(
    val connectionId: String,
    val authContext: AuthContext?,
    val gateway: GatewayServer,
)

/**
 * Dispatches gateway requests to registered method handlers.
 */
class RpcDispatcher {
    private val methods = mutableMapOf<String, RpcMethodHandler>()
    private val scopeRequirements = mutableMapOf<String, String>()

    fun register(method: String, requiredScope: String? = null, handler: RpcMethodHandler) {
        methods[method] = handler
        if (requiredScope != null) {
            scopeRequirements[method] = requiredScope
        }
    }

    fun hasMethod(method: String): Boolean = method in methods

    fun methodNames(): List<String> = methods.keys.sorted()

    suspend fun dispatch(request: GatewayRequestFrame, context: RpcContext): GatewayResponseFrame {
        val handler = methods[request.method]
            ?: return GatewayResponseFrame(
                id = request.id,
                ok = false,
                error = GatewayErrorFrame(
                    code = "method_not_found",
                    message = "Method not found: ${request.method}",
                ),
            )

        val requiredScope = scopeRequirements[request.method]
        if (requiredScope != null) {
            val auth = context.authContext
            if (auth == null || !auth.hasScope(requiredScope)) {
                return GatewayResponseFrame(
                    id = request.id,
                    ok = false,
                    error = GatewayErrorFrame(
                        code = "forbidden",
                        message = "Insufficient scope for method: ${request.method}",
                    ),
                )
            }
        }

        return try {
            val result = handler.handle(request.params, context)
            GatewayResponseFrame(id = request.id, ok = true, payload = result ?: JsonNull)
        } catch (e: RpcException) {
            GatewayResponseFrame(
                id = request.id,
                ok = false,
                error = GatewayErrorFrame(code = e.code, message = e.message ?: "Unknown error"),
            )
        } catch (e: Exception) {
            GatewayResponseFrame(
                id = request.id,
                ok = false,
                error = GatewayErrorFrame(
                    code = "internal_error",
                    message = e.message ?: "Internal error",
                ),
            )
        }
    }
}

class RpcException(val code: String, override val message: String) : RuntimeException(message)
