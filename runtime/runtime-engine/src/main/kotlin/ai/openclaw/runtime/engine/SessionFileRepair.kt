package ai.openclaw.runtime.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class SessionFileRepairReport(
    val repaired: Boolean,
    val droppedLines: Int,
    val backupPath: String? = null,
    val reason: String? = null,
)

internal fun repairSessionFileIfNeeded(
    file: File,
    warn: (String) -> Unit = {},
): SessionFileRepairReport {
    val path = file.absolutePath
    if (path.isBlank()) {
        return SessionFileRepairReport(repaired = false, droppedLines = 0, reason = "missing session file")
    }
    val content = try {
        file.readText()
    } catch (err: IOException) {
        if (!file.exists()) {
            return SessionFileRepairReport(
                repaired = false,
                droppedLines = 0,
                reason = "missing session file",
            )
        }
        val reason = "failed to read session file: ${err.message ?: "unknown error"}"
        warn("session file repair skipped: $reason (${file.name})")
        return SessionFileRepairReport(repaired = false, droppedLines = 0, reason = reason)
    }

    val parser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }
    val lines = content.split(Regex("\\r?\\n"))
    val cleanedLines = mutableListOf<String>()
    var droppedLines = 0

    for (line in lines) {
        if (line.isBlank()) continue
        val parsed = runCatching { parser.parseToJsonElement(line).jsonObject }.getOrNull()
        if (parsed == null) {
            droppedLines += 1
            continue
        }
        cleanedLines += parser.encodeToString(JsonObject.serializer(), parsed)
    }

    if (cleanedLines.isEmpty()) {
        return SessionFileRepairReport(
            repaired = false,
            droppedLines = droppedLines,
            reason = "empty session file",
        )
    }

    if (!isSessionHeader(cleanedLines.first(), parser)) {
        warn("session file repair skipped: invalid session header (${file.name})")
        return SessionFileRepairReport(
            repaired = false,
            droppedLines = droppedLines,
            reason = "invalid session header",
        )
    }

    if (droppedLines == 0) {
        return SessionFileRepairReport(repaired = false, droppedLines = 0)
    }

    val cleaned = cleanedLines.joinToString(separator = "\n", postfix = "\n")
    val backupFile = File("${file.absolutePath}.bak-${ProcessHandle.current().pid()}-${System.currentTimeMillis()}")
    val tmpFile = File("${file.absolutePath}.repair-${ProcessHandle.current().pid()}-${System.currentTimeMillis()}.tmp")

    return try {
        Files.copy(file.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        tmpFile.writeText(cleaned)
        Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        warn("session file repaired: dropped $droppedLines malformed line(s) (${file.name})")
        SessionFileRepairReport(
            repaired = true,
            droppedLines = droppedLines,
            backupPath = backupFile.absolutePath,
        )
    } catch (err: Exception) {
        runCatching { tmpFile.delete() }
        SessionFileRepairReport(
            repaired = false,
            droppedLines = droppedLines,
            reason = "repair failed: ${err.message ?: "unknown error"}",
        )
    }
}

private fun isSessionHeader(line: String, parser: Json): Boolean {
    val obj = runCatching { parser.parseToJsonElement(line).jsonObject }.getOrNull() ?: return false
    val sessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    return sessionId.isNotEmpty()
}
