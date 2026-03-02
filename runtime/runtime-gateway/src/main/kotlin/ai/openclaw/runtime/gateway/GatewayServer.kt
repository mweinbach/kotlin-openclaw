package ai.openclaw.runtime.gateway

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.json.Json

/**
 * Embedded Ktor-based gateway server.
 * Provides WebSocket + REST endpoints for agent communication.
 *
 * Ported from src/gateway/
 */
class GatewayServer(
    private val port: Int = 18789,
    private val host: String = "127.0.0.1",
) {
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    fun start() {
        server = embeddedServer(Netty, port = port, host = host) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = false
                })
            }
            install(WebSockets)

            routing {
                get("/health") {
                    call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
                }

                webSocket("/ws") {
                    // JSON-RPC protocol handler
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            // TODO: Parse JSON-RPC and route to handlers
                            send(Frame.Text("""{"jsonrpc":"2.0","result":"ok"}"""))
                        }
                    }
                }
            }
        }
        server?.start(wait = false)
    }

    fun stop() {
        server?.stop(1000, 5000)
        server = null
    }

    val isRunning: Boolean get() = server != null
}
