package ai.openclaw.runtime.engine.tools.fs

import ai.openclaw.core.agent.AgentTool
import ai.openclaw.core.agent.ToolContext
import ai.openclaw.runtime.engine.tools.ToolParamAliases
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption

class ApplyPatchTool(
    workspaceDir: String,
    workspaceOnly: Boolean = true,
) : AgentTool {
    override val name: String = "apply_patch"
    override val description: String = "Apply a patch using *** Begin Patch / *** End Patch format"
    override val parametersSchema: String = """
        {
          "type": "object",
          "properties": {
            "input": {"type": "string", "description": "Patch text"}
          },
          "required": ["input"],
          "additionalProperties": true
        }
    """.trimIndent()

    private val pathGuards = ToolPathGuards(workspaceDir, workspaceOnly)

    override suspend fun execute(input: String, context: ToolContext): String {
        val params = ToolParamAliases.parseObject(input)
            ?: return ToolParamAliases.jsonError(name, "Input must be a JSON object")
        val patchText = ToolParamAliases.getString(params, "input")
            ?: return ToolParamAliases.jsonError(name, "'input' is required")

        return runCatching {
            val hunks = parsePatchText(patchText)
            if (hunks.isEmpty()) {
                return@runCatching ToolParamAliases.jsonError(name, "No patch hunks found")
            }

            val added = mutableListOf<String>()
            val modified = mutableListOf<String>()
            val deleted = mutableListOf<String>()

            for (hunk in hunks) {
                when (hunk) {
                    is Hunk.Add -> {
                        val target = pathGuards.resolve(hunk.path)
                        target.parent?.let(Files::createDirectories)
                        Files.write(
                            target,
                            hunk.contents.toByteArray(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE,
                        )
                        added += pathGuards.display(target)
                    }
                    is Hunk.Delete -> {
                        val target = pathGuards.resolve(hunk.path)
                        Files.deleteIfExists(target)
                        deleted += pathGuards.display(target)
                    }
                    is Hunk.Update -> {
                        val source = pathGuards.resolve(hunk.path)
                        if (!Files.exists(source) || !Files.isRegularFile(source)) {
                            return@runCatching ToolParamAliases.jsonError(name, "File not found: ${hunk.path}")
                        }
                        val original = String(Files.readAllBytes(source), StandardCharsets.UTF_8)
                        val updated = applyUpdatePatch(original, hunk.patchLines)
                        if (hunk.moveTo != null) {
                            val moveTarget = pathGuards.resolve(hunk.moveTo)
                            moveTarget.parent?.let(Files::createDirectories)
                            Files.write(
                                moveTarget,
                                updated.toByteArray(StandardCharsets.UTF_8),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE,
                            )
                            Files.deleteIfExists(source)
                            modified += pathGuards.display(moveTarget)
                        } else {
                            Files.write(
                                source,
                                updated.toByteArray(StandardCharsets.UTF_8),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE,
                            )
                            modified += pathGuards.display(source)
                        }
                    }
                }
            }

            val summaryLines = mutableListOf("Success. Updated the following files:")
            added.distinct().forEach { summaryLines += "A $it" }
            modified.distinct().forEach { summaryLines += "M $it" }
            deleted.distinct().forEach { summaryLines += "D $it" }

            ToolParamAliases.jsonOk(
                name,
                mapOf(
                    "summary" to summaryLines.joinToString("\n"),
                    "added" to added.distinct(),
                    "modified" to modified.distinct(),
                    "deleted" to deleted.distinct(),
                ),
            )
        }.getOrElse { err ->
            ToolParamAliases.jsonError(name, err.message ?: "Failed to apply patch")
        }
    }

    private sealed class Hunk {
        data class Add(val path: String, val contents: String) : Hunk()
        data class Delete(val path: String) : Hunk()
        data class Update(val path: String, val moveTo: String?, val patchLines: List<String>) : Hunk()
    }

    private fun parsePatchText(raw: String): List<Hunk> {
        val lines = raw.replace("\r\n", "\n").split("\n")
        if (lines.isEmpty() || lines.first().trim() != "*** Begin Patch") {
            throw IllegalArgumentException("Patch must start with *** Begin Patch")
        }

        val hunks = mutableListOf<Hunk>()
        var i = 1
        while (i < lines.size) {
            val line = lines[i]
            when {
                line == "*** End Patch" -> return hunks
                line.startsWith("*** Add File: ") -> {
                    val path = line.removePrefix("*** Add File: ").trim()
                    i += 1
                    val addLines = mutableListOf<String>()
                    while (i < lines.size && !isHeader(lines[i])) {
                        val current = lines[i]
                        if (!current.startsWith("+")) {
                            throw IllegalArgumentException("Add file hunk can only contain '+' lines")
                        }
                        addLines += current.drop(1)
                        i += 1
                    }
                    hunks += Hunk.Add(path = path, contents = addLines.joinToString("\n"))
                }
                line.startsWith("*** Delete File: ") -> {
                    val path = line.removePrefix("*** Delete File: ").trim()
                    hunks += Hunk.Delete(path)
                    i += 1
                }
                line.startsWith("*** Update File: ") -> {
                    val path = line.removePrefix("*** Update File: ").trim()
                    i += 1
                    var moveTo: String? = null
                    if (i < lines.size && lines[i].startsWith("*** Move to: ")) {
                        moveTo = lines[i].removePrefix("*** Move to: ").trim()
                        i += 1
                    }
                    val patchLines = mutableListOf<String>()
                    while (i < lines.size && !isHeader(lines[i])) {
                        patchLines += lines[i]
                        i += 1
                    }
                    hunks += Hunk.Update(path = path, moveTo = moveTo, patchLines = patchLines)
                }
                line.isBlank() -> {
                    i += 1
                }
                else -> {
                    throw IllegalArgumentException("Unsupported patch line: $line")
                }
            }
        }

        throw IllegalArgumentException("Patch must end with *** End Patch")
    }

    private fun isHeader(line: String): Boolean {
        return line == "*** End Patch" ||
            line.startsWith("*** Add File: ") ||
            line.startsWith("*** Delete File: ") ||
            line.startsWith("*** Update File: ")
    }

    private fun applyUpdatePatch(original: String, patchLines: List<String>): String {
        val originalLines = original.replace("\r\n", "\n").split("\n").toMutableList()
        val out = mutableListOf<String>()
        var index = 0

        for (line in patchLines) {
            if (line == "*** End of File" || line.startsWith("@@")) {
                continue
            }
            if (line.isEmpty()) {
                continue
            }
            when (line.first()) {
                ' ' -> {
                    val text = line.drop(1)
                    index = consumeMatching(originalLines, out, index, text, remove = false)
                }
                '-' -> {
                    val text = line.drop(1)
                    index = consumeMatching(originalLines, out, index, text, remove = true)
                }
                '+' -> {
                    out += line.drop(1)
                }
                else -> {
                    throw IllegalArgumentException("Unsupported update patch line: $line")
                }
            }
        }

        while (index < originalLines.size) {
            out += originalLines[index]
            index += 1
        }

        return out.joinToString("\n")
    }

    private fun consumeMatching(
        original: List<String>,
        out: MutableList<String>,
        startIndex: Int,
        target: String,
        remove: Boolean,
    ): Int {
        var index = startIndex
        if (index < original.size && original[index] == target) {
            if (!remove) {
                out += original[index]
            }
            return index + 1
        }

        val found = findIndex(original, target, index)
        if (found < 0) {
            throw IllegalArgumentException("Patch context not found: '$target'")
        }

        while (index < found) {
            out += original[index]
            index += 1
        }

        if (!remove) {
            out += original[index]
        }
        return index + 1
    }

    private fun findIndex(lines: List<String>, target: String, start: Int): Int {
        var idx = start.coerceAtLeast(0)
        while (idx < lines.size) {
            if (lines[idx] == target) return idx
            idx += 1
        }
        return -1
    }
}
