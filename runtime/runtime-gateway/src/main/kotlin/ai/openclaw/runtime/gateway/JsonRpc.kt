package ai.openclaw.runtime.gateway

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * JSON-RPC 2.0 protocol types and dispatcher.
 * Ported from src/gateway/server/ws-connection/message-handler.ts
 */

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
) {
    companion object {
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
        const val AUTH_REQUIRED = -32000
        const val FORBIDDEN = -32001
        const val RATE_LIMITED = -32002
    }
}

/**
 * Event notification pushed to clients (no id, no response expected).
 */
@Serializable
data class JsonRpcNotification(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement? = null,
)

/**
 * Handler for a JSON-RPC method.
 */
fun interface RpcMethodHandler {
    suspend fun handle(params: JsonElement?, context: RpcContext): JsonElement?
}

/**
 * Context available to every RPC handler.
 */
data class RpcContext(
    val connectionId: String,
    val authContext: AuthContext?,
    val gateway: GatewayServer,
)

/**
 * Dispatches JSON-RPC requests to registered method handlers.
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

    suspend fun dispatch(request: JsonRpcRequest, context: RpcContext): JsonRpcResponse {
        val handler = methods[request.method]
            ?: return JsonRpcResponse(
                id = request.id,
                error = JsonRpcError(
                    code = JsonRpcError.METHOD_NOT_FOUND,
                    message = "Method not found: ${request.method}",
                ),
            )

        // Check scope requirements
        val requiredScope = scopeRequirements[request.method]
        if (requiredScope != null) {
            val auth = context.authContext
            if (auth == null || !auth.hasScope(requiredScope)) {
                return JsonRpcResponse(
                    id = request.id,
                    error = JsonRpcError(
                        code = JsonRpcError.FORBIDDEN,
                        message = "Insufficient scope for method: ${request.method}",
                    ),
                )
            }
        }

        return try {
            val result = handler.handle(request.params, context)
            JsonRpcResponse(id = request.id, result = result ?: JsonNull)
        } catch (e: RpcException) {
            JsonRpcResponse(
                id = request.id,
                error = JsonRpcError(code = e.code, message = e.message ?: "Unknown error"),
            )
        } catch (e: Exception) {
            JsonRpcResponse(
                id = request.id,
                error = JsonRpcError(
                    code = JsonRpcError.INTERNAL_ERROR,
                    message = e.message ?: "Internal error",
                ),
            )
        }
    }
}

class RpcException(val code: Int, override val message: String) : RuntimeException(message)
