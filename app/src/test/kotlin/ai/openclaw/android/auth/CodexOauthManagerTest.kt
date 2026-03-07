package ai.openclaw.android.auth

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CodexOauthManagerTest {

    private lateinit var server: HttpServer

    @Before
    fun setup() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `refresh token flow exchanges id token for api credential`() = runTest {
        val refreshBody = AtomicReference<String>()
        val exchangeBody = AtomicReference<String>()
        server.createContext("/oauth/token") { exchange ->
            handleOauthToken(exchange, refreshBody, exchangeBody)
        }

        val context = ApplicationProvider.getApplicationContext<Application>()
        val manager = CodexOauthManager(
            context = context,
            issuer = "http://127.0.0.1:${server.address.port}",
        )

        val session = manager.refreshFromRefreshToken("refresh-token")

        assertEquals("oauth-access", session.accessToken)
        assertEquals("oauth-refresh-next", session.refreshToken)
        assertEquals("id-token-123", session.idToken)
        assertEquals("sk-codex", session.apiKey)

        assertTrue(refreshBody.get().contains("grant_type=refresh_token"))
        val tokenExchangeBody = exchangeBody.get()
        assertTrue(tokenExchangeBody.contains("requested_token=openai-api-key"))
        assertTrue(tokenExchangeBody.contains("subject_token=id-token-123"))
        assertTrue(
            tokenExchangeBody.contains(
                "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange",
            ),
        )
    }

    private fun handleOauthToken(
        exchange: HttpExchange,
        refreshBody: AtomicReference<String>,
        exchangeBody: AtomicReference<String>,
    ) {
        val body = exchange.requestBody.bufferedReader().use { it.readText() }
        val response = when {
            "grant_type=refresh_token" in body -> {
                refreshBody.set(body)
                """
                {
                  "access_token": "oauth-access",
                  "refresh_token": "oauth-refresh-next",
                  "id_token": "id-token-123",
                  "expires_in": 3600
                }
                """.trimIndent()
            }

            "requested_token=openai-api-key" in body -> {
                exchangeBody.set(body)
                """{"access_token":"sk-codex"}"""
            }

            else -> {
                exchange.sendResponseHeaders(400, -1)
                exchange.close()
                return
            }
        }

        val responseBytes = response.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, responseBytes.size.toLong())
        exchange.responseBody.use { out -> out.write(responseBytes) }
    }
}
