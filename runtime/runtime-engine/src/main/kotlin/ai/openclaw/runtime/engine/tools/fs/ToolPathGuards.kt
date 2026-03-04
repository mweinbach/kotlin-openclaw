package ai.openclaw.runtime.engine.tools.fs

import java.nio.file.Path

class ToolPathGuards(
    workspaceRoot: String,
    private val workspaceOnly: Boolean,
) {
    private val root: Path = Path.of(workspaceRoot)
        .toAbsolutePath()
        .normalize()

    fun resolve(inputPath: String): Path {
        val raw = inputPath.trim()
        require(raw.isNotEmpty()) { "Path cannot be blank" }
        val candidate = Path.of(raw)
        val resolved = if (candidate.isAbsolute) {
            candidate.normalize()
        } else {
            root.resolve(candidate).normalize()
        }
        if (workspaceOnly && !resolved.startsWith(root)) {
            throw IllegalArgumentException("Path '$raw' escapes workspace root")
        }
        return resolved
    }

    fun display(resolved: Path): String {
        val normalized = resolved.toAbsolutePath().normalize()
        return if (normalized.startsWith(root)) {
            root.relativize(normalized).toString().ifEmpty { "." }
        } else {
            normalized.toString()
        }
    }
}
