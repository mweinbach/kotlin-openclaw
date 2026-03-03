package ai.openclaw.runtime.providers

import ai.openclaw.core.agent.LlmRequest
import ai.openclaw.core.agent.LlmStreamEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderStreamingToolCallTest {
    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `openai stream assigns fallback tool call id when missing`() = runTest {
        enqueueSse(
            """
            {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"read","arguments":"{\"path\":\"README.md\"}"}}]}}]}
            {"choices":[{"finish_reason":"tool_calls"}]}
            """.trimIndent(),
        )

        val provider = OpenAiProvider(
            apiKey = "test-key",
            baseUrl = server.url("/v1").toString().removeSuffix("/"),
        )

        val events = provider.streamCompletion(
            LlmRequest(
                model = "openai/gpt-4o-mini",
                messages = emptyList(),
            ),
        ).toList()

        val toolUses = events.filterIsInstance<LlmStreamEvent.ToolUse>()
        assertEquals(1, toolUses.size)
        assertEquals("call_auto_1", toolUses[0].id)
        assertEquals("read", toolUses[0].name)
    }

    @Test
    fun `ollama stream emits tool calls from openai-compatible deltas`() = runTest {
        enqueueSse(
            """
            {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"read","arguments":"{\"path\":\"docs.md\"}"}}]}}]}
            {"choices":[{"finish_reason":"tool_calls"}]}
            """.trimIndent(),
        )

        val provider = OllamaProvider(
            baseUrl = server.url("").toString().removeSuffix("/"),
        )

        val events = provider.streamCompletion(
            LlmRequest(
                model = "ollama/llama3",
                messages = emptyList(),
            ),
        ).toList()

        val toolUses = events.filterIsInstance<LlmStreamEvent.ToolUse>()
        assertEquals(1, toolUses.size)
        assertEquals("read", toolUses[0].name)
        assertTrue(toolUses[0].input.contains("docs.md"))
    }

    @Test
    fun `gemini stream deduplicates repeated snapshot tool calls`() = runTest {
        enqueueSse(
            """
            {"candidates":[{"content":{"parts":[{"text":"Hello"},{"functionCall":{"name":"read","args":{"path":"A"}}}]}}]}
            {"candidates":[{"content":{"parts":[{"text":"Hello"},{"functionCall":{"name":"read","args":{"path":"A"}}}]}}]}
            {"candidates":[{"content":{"parts":[{"text":"Hello world"},{"functionCall":{"name":"read","args":{"path":"A"}}}]},"finishReason":"STOP"}]}
            """.trimIndent(),
        )

        val provider = GeminiProvider(
            apiKey = "test-key",
            baseUrl = server.url("/v1beta").toString().removeSuffix("/"),
        )

        val events = provider.streamCompletion(
            LlmRequest(
                model = "gemini/gemini-2.0-flash",
                messages = emptyList(),
            ),
        ).toList()

        val text = events.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString(separator = "") { it.text }
        val toolUses = events.filterIsInstance<LlmStreamEvent.ToolUse>()

        assertEquals("Hello world", text)
        assertEquals(1, toolUses.size)
        assertEquals("read", toolUses[0].name)
        assertTrue(toolUses[0].input.contains("\"path\":\"A\""))
    }

    private fun enqueueSse(jsonEvents: String) {
        val body = buildString {
            jsonEvents
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { event ->
                    append("data: ")
                    append(event)
                    append("\n\n")
                }
            append("data: [DONE]\n\n")
        }

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body),
        )
    }
}
