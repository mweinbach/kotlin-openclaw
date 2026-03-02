package ai.openclaw.runtime.cron

import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistence layer for cron jobs. Stores jobs in a JSON file.
 * Ported from src/cron/store.ts.
 */
class CronStore(
    private val storePath: String,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun load(): CronStoreFile {
        val file = File(storePath)
        if (!file.exists()) return CronStoreFile()
        return try {
            json.decodeFromString(CronStoreFile.serializer(), file.readText())
        } catch (_: Exception) {
            CronStoreFile()
        }
    }

    fun save(store: CronStoreFile) {
        val file = File(storePath)
        file.parentFile?.mkdirs()
        val content = json.encodeToString(CronStoreFile.serializer(), store)
        // Atomic write via temp file
        val tmp = File(file.parent, "${file.name}.${ProcessHandle.current().pid()}.tmp")
        try {
            tmp.writeText(content)
            tmp.renameTo(file)
        } finally {
            tmp.delete() // cleanup if rename failed
        }
    }
}
