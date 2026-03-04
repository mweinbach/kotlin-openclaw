package ai.openclaw.runtime.engine.tools.fs

import ai.openclaw.core.agent.AgentTool
import ai.openclaw.core.agent.ToolContext
import ai.openclaw.runtime.engine.tools.ToolParamAliases
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class ReadTool(
    workspaceDir: String,
    workspaceOnly: Boolean,
    private val defaultMaxChars: Int = 40_000,
) : AgentTool {
    override val name: String = "read"
    override val description: String = "Read file contents from the workspace"
    override val parametersSchema: String = """
        {
          "type": "object",
          "properties": {
            "path": {"type": "string", "description": "File path to read"},
            "file_path": {"type": "string", "description": "Alias for path"},
            "max_chars": {"type": "integer", "description": "Maximum chars to return"}
          },
          "required": ["path"],
          "additionalProperties": true
        }
    """.trimIndent()

    private val pathGuards = ToolPathGuards(workspaceDir, workspaceOnly)

    override suspend fun execute(input: String, context: ToolContext): String {
        val params = ToolParamAliases.parseObject(input)
            ?: return ToolParamAliases.jsonError(name, "Input must be a JSON object")
        val rawPath = ToolParamAliases.getString(params, "path", "file_path")
            ?: return ToolParamAliases.jsonError(name, "'path' is required")
        val maxChars = ToolParamAliases.getInt(params, "max_chars")
            ?.coerceIn(256, 1_000_000)
            ?: defaultMaxChars

        return runCatching {
            val resolved = pathGuards.resolve(rawPath)
            if (!Files.exists(resolved)) {
                return ToolParamAliases.jsonError(name, "File not found: $rawPath")
            }
            if (!Files.isRegularFile(resolved)) {
                return ToolParamAliases.jsonError(name, "Path is not a regular file: $rawPath")
            }
            val content = String(Files.readAllBytes(resolved), StandardCharsets.UTF_8)
            val truncated = content.length > maxChars
            val safeContent = if (truncated) {
                content.substring(0, maxChars)
            } else {
                content
            }
            ToolParamAliases.jsonOk(
                name,
                mapOf(
                    "path" to pathGuards.display(resolved),
                    "content" to safeContent,
                    "truncated" to truncated,
                    "totalChars" to content.length,
                ),
            )
        }.getOrElse { err ->
            ToolParamAliases.jsonError(name, err.message ?: "Failed to read file")
        }
    }
}
