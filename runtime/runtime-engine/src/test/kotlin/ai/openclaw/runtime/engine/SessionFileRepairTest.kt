package ai.openclaw.runtime.engine

import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionFileRepairTest {
    @Test
    fun `repairs malformed jsonl lines and writes backup`() {
        val tempDir = Files.createTempDirectory("session-repair-test")
        try {
            val file = tempDir.resolve("session.jsonl").toFile()
            file.writeText(
                """
                {"sessionId":"s1","agentId":"main"}
                {"role":"user","content":"hello"}
                not-json
                {"role":"assistant","content":"ok"}
                
                """.trimIndent(),
            )
            val warnings = mutableListOf<String>()
            val report = repairSessionFileIfNeeded(file) { warnings += it }

            assertTrue(report.repaired)
            assertEquals(1, report.droppedLines)
            assertNotNull(report.backupPath)
            assertTrue(File(report.backupPath).exists())
            val lines = file.readLines().filter { it.isNotBlank() }
            assertEquals(3, lines.size)
            assertTrue(warnings.any { it.contains("session file repaired") })
        } finally {
            File(tempDir.toUri()).deleteRecursively()
        }
    }

    @Test
    fun `skips repair when header is invalid`() {
        val tempDir = Files.createTempDirectory("session-repair-header-test")
        try {
            val file = tempDir.resolve("session.jsonl").toFile()
            file.writeText(
                """
                {"role":"user","content":"hello"}
                not-json
                """.trimIndent(),
            )
            val warnings = mutableListOf<String>()
            val report = repairSessionFileIfNeeded(file) { warnings += it }
            assertFalse(report.repaired)
            assertEquals("invalid session header", report.reason)
            assertTrue(warnings.any { it.contains("invalid session header") })
        } finally {
            File(tempDir.toUri()).deleteRecursively()
        }
    }
}
