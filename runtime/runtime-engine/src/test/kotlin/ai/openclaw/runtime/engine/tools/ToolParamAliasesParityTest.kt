package ai.openclaw.runtime.engine.tools

import ai.openclaw.core.agent.ToolContext
import ai.openclaw.runtime.engine.tools.fs.EditTool
import ai.openclaw.runtime.engine.tools.fs.ReadTool
import ai.openclaw.runtime.engine.tools.fs.WriteTool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolParamAliasesParityTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `file_path and old_string aliases are accepted`() = runTest {
        val workspace = Files.createTempDirectory("alias-parity")
        val write = WriteTool(workspace.toString(), workspaceOnly = true)
        val read = ReadTool(workspace.toString(), workspaceOnly = true)
        val edit = EditTool(workspace.toString(), workspaceOnly = true)

        val writeResult = write.execute(
            """{"file_path":"note.txt", "text":"hello world"}""",
            ToolContext(sessionKey = "s", agentId = "a"),
        )
        assertEquals("ok", json.parseToJsonElement(writeResult).jsonObject["status"]?.jsonPrimitive?.content)

        val editResult = edit.execute(
            """{"file_path":"note.txt", "old_string":"world", "new_string":"openclaw"}""",
            ToolContext(sessionKey = "s", agentId = "a"),
        )
        assertEquals("ok", json.parseToJsonElement(editResult).jsonObject["status"]?.jsonPrimitive?.content)

        val readResult = read.execute(
            """{"file_path":"note.txt"}""",
            ToolContext(sessionKey = "s", agentId = "a"),
        )
        val readPayload = json.parseToJsonElement(readResult).jsonObject
        assertEquals("ok", readPayload["status"]?.jsonPrimitive?.content)
        assertTrue(readPayload["content"]?.jsonPrimitive?.content?.contains("hello openclaw") == true)
    }
}
