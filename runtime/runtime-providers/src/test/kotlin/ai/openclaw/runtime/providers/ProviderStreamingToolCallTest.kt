package ai.openclaw.runtime.providers

import ai.openclaw.core.agent.LlmContentBlock
import ai.openclaw.core.agent.LlmMessage
import ai.openclaw.core.agent.LlmRequest
import ai.openclaw.core.agent.LlmStreamEvent
import ai.openclaw.core.agent.LlmToolCall
import ai.openclaw.core.agent.LlmToolDefinition
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderStreamingToolCallTest {
    private val json = Json { ignoreUnknownKeys = true }
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
    fun `ollama stream emits tool calls from native ndjson chunks`() = runTest {
        enqueueNdjson(
            """
            {"model":"llama3","message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"read","arguments":{"path":"docs.md"}}}]},"done":false}
            {"model":"llama3","message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","prompt_eval_count":11,"eval_count":7}
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
        val usage = events.filterIsInstance<LlmStreamEvent.Usage>().single()
        assertEquals(1, toolUses.size)
        assertTrue(toolUses[0].id.startsWith("ollama_call_"))
        assertEquals("read", toolUses[0].name)
        assertTrue(toolUses[0].input.contains("docs.md"))
        assertEquals(11, usage.inputTokens)
        assertEquals(7, usage.outputTokens)
    }

    @Test
    fun `ollama stream assigns unique fallback ids when chunk omits ids`() = runTest {
        enqueueNdjson(
            """
            {"model":"llama3","message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"read","arguments":{"path":"a"}}},{"function":{"name":"read","arguments":{"path":"b"}}}]},"done":false}
            {"model":"llama3","message":{"role":"assistant","content":""},"done":true,"done_reason":"stop"}
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
        assertEquals(2, toolUses.size)
        assertTrue(toolUses[0].id.startsWith("ollama_call_"))
        assertTrue(toolUses[1].id.startsWith("ollama_call_"))
        assertTrue(toolUses[0].id != toolUses[1].id)
    }

    @Test
    fun `ollama request preserves user images assistant tool calls and tool name`() = runTest {
        enqueueNdjson("""{"model":"llama3","message":{"role":"assistant","content":"ok"},"done":true,"done_reason":"stop"}""")

        val provider = OllamaProvider(
            baseUrl = server.url("").toString().removeSuffix("/"),
        )

        provider.streamCompletion(
            LlmRequest(
                model = "ollama/llama3",
                systemPrompt = "system prompt",
                messages = listOf(
                    LlmMessage(
                        role = LlmMessage.Role.USER,
                        contentBlocks = listOf(
                            LlmContentBlock.Text("inspect this"),
                            LlmContentBlock.ImageUrl("data:image/png;base64,AAA"),
                        ),
                    ),
                    LlmMessage(
                        role = LlmMessage.Role.ASSISTANT,
                        content = "",
                        toolCalls = listOf(
                            LlmToolCall(
                                id = "call_1",
                                name = "read",
                                arguments = """{"path":"README.md"}""",
                            ),
                        ),
                    ),
                    LlmMessage(
                        role = LlmMessage.Role.TOOL,
                        content = "done",
                        toolCallId = "call_1",
                        name = "read",
                    ),
                ),
            ),
        ).toList()

        val recorded = server.takeRequest()
        val body = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        val messages = body["messages"]!!.jsonArray
        assertEquals("/api/chat", recorded.path)

        val user = messages[1].jsonObject
        assertEquals("user", user["role"]!!.jsonPrimitive.content)
        assertEquals("inspect this", user["content"]!!.jsonPrimitive.content)
        assertEquals(
            "AAA",
            user["images"]!!.jsonArray.first().jsonPrimitive.content,
        )

        val assistant = messages[2].jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        val toolCalls = assistant["tool_calls"]!!.jsonArray
        assertEquals("read", toolCalls.first().jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)

        val tool = messages[3].jsonObject
        assertEquals("tool", tool["role"]!!.jsonPrimitive.content)
        assertEquals("read", tool["tool_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ollama request sends bearer authorization header when api key is configured`() = runTest {
        enqueueNdjson("""{"model":"llama3","message":{"role":"assistant","content":"ok"},"done":true,"done_reason":"stop"}""")

        val provider = OllamaProvider(
            baseUrl = server.url("").toString().removeSuffix("/"),
            apiKey = "test-ollama-key",
        )

        provider.streamCompletion(
            LlmRequest(
                model = "ollama/llama3",
                messages = listOf(
                    LlmMessage(role = LlmMessage.Role.USER, content = "hello"),
                ),
            ),
        ).toList()

        val recorded = server.takeRequest()
        assertEquals("Bearer test-ollama-key", recorded.getHeader("Authorization"))
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

    @Test
    fun `gemini request serializes data-uri image blocks as inlineData`() = runTest {
        enqueueSse(
            """
            {"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}]}
            """.trimIndent(),
        )

        val provider = GeminiProvider(
            apiKey = "test-key",
            baseUrl = server.url("/v1beta").toString().removeSuffix("/"),
        )

        provider.streamCompletion(
            LlmRequest(
                model = "gemini/gemini-2.0-flash",
                messages = listOf(
                    LlmMessage(
                        role = LlmMessage.Role.USER,
                        contentBlocks = listOf(
                            LlmContentBlock.Text("inspect"),
                            LlmContentBlock.ImageUrl("data:image/png;base64,AAA"),
                        ),
                    ),
                ),
            ),
        ).toList()

        val recorded = server.takeRequest()
        val body = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        val parts = body["contents"]!!.jsonArray.first().jsonObject["parts"]!!.jsonArray
        val inlinePart = parts.firstOrNull { part -> part.jsonObject["inlineData"] != null }
        assertTrue(inlinePart != null)
        val inlineData = inlinePart!!.jsonObject["inlineData"]!!.jsonObject
        assertEquals("image/png", inlineData["mimeType"]!!.jsonPrimitive.content)
        assertEquals("AAA", inlineData["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gemini request cleans unsupported schema keywords and flattens literal unions`() = runTest {
        enqueueSse(
            """
            {"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}]}
            """.trimIndent(),
        )

        val provider = GeminiProvider(
            apiKey = "test-key",
            baseUrl = server.url("/v1beta").toString().removeSuffix("/"),
        )

        provider.streamCompletion(
            LlmRequest(
                model = "gemini/gemini-2.0-flash",
                messages = listOf(LlmMessage(role = LlmMessage.Role.USER, content = "hi")),
                tools = listOf(
                    LlmToolDefinition(
                        name = "demo_tool",
                        description = "demo",
                        parameters = """
                            {
                              "type": "object",
                              "properties": {
                                "mode": {
                                  "anyOf": [
                                    {"const":"a","type":"string"},
                                    {"const":"b","type":"string"}
                                  ]
                                },
                                "payload": {
                                  "${'$'}ref": "#/definitions/payload"
                                },
                                "count": {
                                  "type": ["integer", "null"],
                                  "minimum": 1
                                }
                              },
                              "definitions": {
                                "payload": {
                                  "type": "object",
                                  "properties": {
                                    "x": {"type":"string","minLength":1}
                                  },
                                  "additionalProperties": false
                                }
                              },
                              "additionalProperties": false
                            }
                        """.trimIndent(),
                    ),
                ),
            ),
        ).toList()

        val recorded = server.takeRequest()
        val body = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        val schema = body["tools"]!!
            .jsonArray
            .first()
            .jsonObject["functionDeclarations"]!!
            .jsonArray
            .first()
            .jsonObject["parameters"]!!
            .jsonObject

        assertTrue(schema["additionalProperties"] == null)
        val properties = schema["properties"]!!.jsonObject
        val payload = properties["payload"]!!.jsonObject
        assertTrue(payload["\$ref"] == null)

        val mode = properties["mode"]!!.jsonObject
        assertEquals("string", mode["type"]!!.jsonPrimitive.content)
        val modeEnum = mode["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("a", "b"), modeEnum)

        val count = properties["count"]!!.jsonObject
        assertEquals("integer", count["type"]!!.jsonPrimitive.content)
        assertTrue(count["minimum"] == null)
    }

    @Test
    fun `anthropic request serializes data-uri image blocks as image source`() = runTest {
        enqueueSse(
            """
            {"type":"message_start","message":{"usage":{"input_tokens":1,"output_tokens":0}}}
            {"type":"message_stop"}
            """.trimIndent(),
        )

        val provider = AnthropicProvider(
            apiKey = "test-key",
            baseUrl = server.url("").toString().removeSuffix("/"),
        )

        provider.streamCompletion(
            LlmRequest(
                model = "anthropic/claude-sonnet-4-5",
                messages = listOf(
                    LlmMessage(
                        role = LlmMessage.Role.USER,
                        contentBlocks = listOf(
                            LlmContentBlock.Text("inspect"),
                            LlmContentBlock.ImageUrl("data:image/jpeg;base64,BBB"),
                        ),
                    ),
                ),
            ),
        ).toList()

        val recorded = server.takeRequest()
        val body = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        val content = body["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonArray
        val imageBlock = content.firstOrNull { block -> block.jsonObject["type"]?.jsonPrimitive?.content == "image" }
        assertTrue(imageBlock != null)
        val source = imageBlock!!.jsonObject["source"]!!.jsonObject
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", source["media_type"]!!.jsonPrimitive.content)
        assertEquals("BBB", source["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun `anthropic provider normalizes trailing v1 in base url`() = runTest {
        enqueueSse(
            """
            {"type":"message_start","message":{"usage":{"input_tokens":1,"output_tokens":0}}}
            {"type":"message_stop"}
            """.trimIndent(),
        )

        val provider = AnthropicProvider(
            apiKey = "test-key",
            baseUrl = server.url("/v1").toString().removeSuffix("/"),
        )

        provider.streamCompletion(
            LlmRequest(
                model = "anthropic/claude-sonnet-4-5",
                messages = listOf(
                    LlmMessage(
                        role = LlmMessage.Role.USER,
                        content = "hello",
                    ),
                ),
            ),
        ).toList()

        val recorded = server.takeRequest()
        assertEquals("/v1/messages", recorded.path)
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

    private fun enqueueNdjson(jsonLines: String) {
        val body = buildString {
            jsonLines
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { line ->
                    append(line)
                    append('\n')
                }
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/x-ndjson")
                .setBody(body),
        )
    }
}
