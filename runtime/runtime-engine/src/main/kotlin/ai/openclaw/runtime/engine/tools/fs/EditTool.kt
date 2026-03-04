package ai.openclaw.runtime.engine.tools.fs

import ai.openclaw.core.agent.AgentTool
import ai.openclaw.core.agent.ToolContext
import ai.openclaw.runtime.engine.tools.ToolParamAliases
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption

class EditTool(
    workspaceDir: String,
    workspaceOnly: Boolean,
) : AgentTool {
    override val name: String = "edit"
    override val description: String = "Edit file content using string replacement"
    override val parametersSchema: String = """
        {
          "type": "object",
          "properties": {
            "path": {"type": "string", "description": "File path to edit"},
            "file_path": {"type": "string", "description": "Alias for path"},
            "old_text": {"type": "string", "description": "Text to replace"},
            "old_string": {"type": "string", "description": "Alias for old_text"},
            "new_text": {"type": "string", "description": "Replacement text"},
            "new_string": {"type": "string", "description": "Alias for new_text"},
            "replace_all": {"type": "boolean", "description": "Replace all occurrences"}
          },
          "required": ["path", "old_text", "new_text"],
          "additionalProperties": true
        }
    """.trimIndent()

    private val pathGuards = ToolPathGuards(workspaceDir, workspaceOnly)

    override suspend fun execute(input: String, context: ToolContext): String {
        val params = ToolParamAliases.parseObject(input)
            ?: return ToolParamAliases.jsonError(name, "Input must be a JSON object")
        val rawPath = ToolParamAliases.getString(params, "path", "file_path")
            ?: return ToolParamAliases.jsonError(name, "'path' is required")
        val oldText = ToolParamAliases.getString(params, "old_text", "old_string")
            ?: return ToolParamAliases.jsonError(name, "'old_text' is required")
        val newText = ToolParamAliases.getString(params, "new_text", "new_string")
            ?: return ToolParamAliases.jsonError(name, "'new_text' is required")
        val replaceAll = ToolParamAliases.getBoolean(params, "replace_all") ?: false

        if (oldText.isEmpty()) {
            return ToolParamAliases.jsonError(name, "'old_text' cannot be empty")
        }

        return runCatching {
            val resolved = pathGuards.resolve(rawPath)
            if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
                return ToolParamAliases.jsonError(name, "File not found: $rawPath")
            }
            val original = String(Files.readAllBytes(resolved), StandardCharsets.UTF_8)
            if (!original.contains(oldText)) {
                return ToolParamAliases.jsonError(name, "Target text was not found in file")
            }

            val updated = if (replaceAll) {
                original.replace(oldText, newText)
            } else {
                original.replaceFirst(oldText, newText)
            }
            val replacementCount = if (replaceAll) {
                countOccurrences(original, oldText)
            } else {
                1
            }

            Files.write(
                resolved,
                updated.toByteArray(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )

            ToolParamAliases.jsonOk(
                name,
                mapOf(
                    "path" to pathGuards.display(resolved),
                    "replacements" to replacementCount,
                ),
            )
        }.getOrElse { err ->
            ToolParamAliases.jsonError(name, err.message ?: "Failed to edit file")
        }
    }

    private fun countOccurrences(text: String, needle: String): Int {
        var count = 0
        var index = 0
        while (true) {
            val found = text.indexOf(needle, startIndex = index)
            if (found < 0) break
            count += 1
            index = found + needle.length
        }
        return count
    }
}
