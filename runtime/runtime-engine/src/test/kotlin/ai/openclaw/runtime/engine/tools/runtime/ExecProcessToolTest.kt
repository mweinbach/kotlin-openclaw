package ai.openclaw.runtime.engine.tools.runtime

import ai.openclaw.core.agent.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExecProcessToolTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `exec foreground returns completed output`() = runTest {
        val registry = ProcessRegistry(cleanupMs = 60_000)
        val tool = ExecTool(
            processRegistry = registry,
            workspaceDir = System.getProperty("java.io.tmpdir") ?: "/tmp",
            defaultYieldMs = 0,
            defaultTimeoutSec = 5,
        )

        val result = tool.execute(
            """
            {"command":"echo hello"}
            """.trimIndent(),
            ToolContext(sessionKey = "s", agentId = "a"),
        )

        val payload = json.parseToJsonElement(result).jsonObject
        assertEquals("ok", payload["status"]?.jsonPrimitive?.content)
        assertTrue(payload["output"]?.jsonPrimitive?.content?.contains("hello") == true)
    }

    @Test
    fun `exec background can be polled via process tool`() = runTest {
        val registry = ProcessRegistry(cleanupMs = 60_000)
        val exec = ExecTool(
            processRegistry = registry,
            workspaceDir = System.getProperty("java.io.tmpdir") ?: "/tmp",
            defaultYieldMs = 0,
            defaultTimeoutSec = 5,
        )
        val process = ProcessTool(registry)

        val start = exec.execute(
            """
            {"command":"sleep 1; echo done", "background": true}
            """.trimIndent(),
            ToolContext(sessionKey = "s", agentId = "a"),
        )
        val startPayload = json.parseToJsonElement(start).jsonObject
        assertEquals("ok", startPayload["status"]?.jsonPrimitive?.content)
        val sessionId = startPayload["sessionId"]?.jsonPrimitive?.content
        assertNotNull(sessionId)

        val poll = process.execute(
            """
            {"action":"poll", "sessionId":"$sessionId", "timeout": 2500}
            """.trimIndent(),
            ToolContext(sessionKey = "s", agentId = "a"),
        )
        val pollPayload = json.parseToJsonElement(poll).jsonObject
        assertEquals("ok", pollPayload["status"]?.jsonPrimitive?.content)
        assertTrue(pollPayload["output"]?.jsonPrimitive?.content?.contains("done") == true)
    }
}
