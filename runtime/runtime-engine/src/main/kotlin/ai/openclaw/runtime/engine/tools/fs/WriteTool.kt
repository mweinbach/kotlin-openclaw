package ai.openclaw.runtime.engine.tools.fs

import ai.openclaw.core.agent.AgentTool
import ai.openclaw.core.agent.ToolContext
import ai.openclaw.runtime.engine.tools.ToolParamAliases
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption

class WriteTool(
    workspaceDir: String,
    workspaceOnly: Boolean,
) : AgentTool {
    override val name: String = "write"
    override val description: String = "Create or overwrite a file"
    override val parametersSchema: String = """
        {
          "type": "object",
          "properties": {
            "path": {"type": "string", "description": "File path to write"},
            "file_path": {"type": "string", "description": "Alias for path"},
            "content": {"type": "string", "description": "Content to write"},
            "text": {"type": "string", "description": "Alias for content"}
          },
          "required": ["path", "content"],
          "additionalProperties": true
        }
    """.trimIndent()

    private val pathGuards = ToolPathGuards(workspaceDir, workspaceOnly)

    override suspend fun execute(input: String, context: ToolContext): String {
        val params = ToolParamAliases.parseObject(input)
            ?: return ToolParamAliases.jsonError(name, "Input must be a JSON object")
        val rawPath = ToolParamAliases.getString(params, "path", "file_path")
            ?: return ToolParamAliases.jsonError(name, "'path' is required")
        val content = ToolParamAliases.getString(params, "content", "text")
            ?: return ToolParamAliases.jsonError(name, "'content' is required")

        return runCatching {
            val resolved = pathGuards.resolve(rawPath)
            val parent = resolved.parent
            if (parent != null) {
                Files.createDirectories(parent)
            }
            Files.write(
                resolved,
                content.toByteArray(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            ToolParamAliases.jsonOk(
                name,
                mapOf(
                    "path" to pathGuards.display(resolved),
                    "bytes" to content.toByteArray(StandardCharsets.UTF_8).size,
                    "chars" to content.length,
                ),
            )
        }.getOrElse { err ->
            ToolParamAliases.jsonError(name, err.message ?: "Failed to write file")
        }
    }
}
