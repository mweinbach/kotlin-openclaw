package ai.openclaw.runtime.engine.tools.fs

import ai.openclaw.core.agent.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplyPatchToolTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `workspaceOnly blocks escaping paths`() = runTest {
        val workspace = Files.createTempDirectory("apply-patch-workspace")
        val tool = ApplyPatchTool(workspace.toString(), workspaceOnly = true)

        val result = tool.execute(
            """
            {
              "input": "*** Begin Patch\n*** Add File: ../escape.txt\n+hello\n*** End Patch"
            }
            """.trimIndent(),
            ToolContext(sessionKey = "s", agentId = "a"),
        )

        val payload = json.parseToJsonElement(result).jsonObject
        assertEquals("error", payload["status"]?.jsonPrimitive?.content)
        assertTrue(payload["error"]?.jsonPrimitive?.content?.contains("escapes workspace root") == true)
    }

    @Test
    fun `workspaceOnly false allows escaping paths`() = runTest {
        val workspace = Files.createTempDirectory("apply-patch-workspace-open")
        val outside = workspace.parent.resolve("apply-patch-outside.txt")
        Files.deleteIfExists(outside)

        val tool = ApplyPatchTool(workspace.toString(), workspaceOnly = false)
        val result = tool.execute(
            """
            {
              "input": "*** Begin Patch\n*** Add File: ../apply-patch-outside.txt\n+hello\n*** End Patch"
            }
            """.trimIndent(),
            ToolContext(sessionKey = "s", agentId = "a"),
        )

        val payload = json.parseToJsonElement(result).jsonObject
        assertEquals("ok", payload["status"]?.jsonPrimitive?.content)
        assertTrue(Files.exists(outside))
    }
}
