package ai.openclaw.runtime.cron

import ai.openclaw.core.agent.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class CronToolTest {

    private fun createTestScheduler(): CronScheduler {
        val tempFile = kotlin.io.path.createTempFile("cron-tool-test-", ".json").toFile()
        tempFile.deleteOnExit()
        val store = CronStore(tempFile.absolutePath)
        return CronScheduler(store, CronJobExecutor { })
    }

    private val context = ToolContext(sessionKey = "test-session", agentId = "main")

    @Test
    fun `list action returns empty message when no jobs`() = runTest {
        val scheduler = createTestScheduler()
        scheduler.start()
        val tool = CronTool(scheduler)

        val result = tool.execute("""{"action": "list"}""", context)
        assertTrue(result.contains("No cron jobs"))

        scheduler.stop()
    }

    @Test
    fun `create action creates a job`() = runTest {
        val scheduler = createTestScheduler()
        scheduler.start()
        val tool = CronTool(scheduler)

        val result = tool.execute("""
            {
                "action": "create",
                "name": "Daily Check",
                "schedule_type": "cron",
                "schedule_expr": "0 9 * * *",
                "message": "Run daily check"
            }
        """.trimIndent(), context)

        assertTrue(result.contains("Created cron job"))
        assertTrue(result.contains("Daily Check"))

        // Verify it shows up in list
        val listResult = tool.execute("""{"action": "list"}""", context)
        assertTrue(listResult.contains("Daily Check"))
        assertTrue(listResult.contains("cron: 0 9 * * *"))

        scheduler.stop()
    }

    @Test
    fun `create action with every schedule`() = runTest {
        val scheduler = createTestScheduler()
        scheduler.start()
        val tool = CronTool(scheduler)

        val result = tool.execute("""
            {
                "action": "create",
                "name": "Heartbeat",
                "schedule_type": "every",
                "schedule_every_ms": 60000,
                "message": "ping"
            }
        """.trimIndent(), context)

        assertTrue(result.contains("Created cron job"))
        assertTrue(result.contains("Heartbeat"))

        scheduler.stop()
    }

    @Test
    fun `delete action removes a job`() = runTest {
        val scheduler = createTestScheduler()
        scheduler.start()
        val tool = CronTool(scheduler)

        // Create
        tool.execute("""
            {
                "action": "create",
                "name": "ToDelete",
                "schedule_type": "every",
                "schedule_every_ms": 60000,
                "message": "delete me"
            }
        """.trimIndent(), context)

        val jobs = scheduler.list(includeDisabled = true)
        val jobId = jobs.first().id

        // Delete
        val result = tool.execute("""{"action": "delete", "id": "${jobId.take(8)}"}""", context)
        assertTrue(result.contains("Deleted"))

        // Verify gone
        val listResult = tool.execute("""{"action": "list"}""", context)
        assertTrue(listResult.contains("No cron jobs"))

        scheduler.stop()
    }

    @Test
    fun `disable and enable actions work`() = runTest {
        val scheduler = createTestScheduler()
        scheduler.start()
        val tool = CronTool(scheduler)

        tool.execute("""
            {
                "action": "create",
                "name": "Toggleable",
                "schedule_type": "every",
                "schedule_every_ms": 60000,
                "message": "toggle"
            }
        """.trimIndent(), context)

        val jobs = scheduler.list(includeDisabled = true)
        val jobId = jobs.first().id

        val disableResult = tool.execute("""{"action": "disable", "id": "${jobId.take(8)}"}""", context)
        assertTrue(disableResult.contains("Disabled"))

        val enableResult = tool.execute("""{"action": "enable", "id": "${jobId.take(8)}"}""", context)
        assertTrue(enableResult.contains("Enabled"))

        scheduler.stop()
    }

    @Test
    fun `missing required params returns error`() = runTest {
        val scheduler = createTestScheduler()
        scheduler.start()
        val tool = CronTool(scheduler)

        val result = tool.execute("""{"action": "create"}""", context)
        assertTrue(result.contains("Error"))

        scheduler.stop()
    }

    @Test
    fun `unknown action returns error`() = runTest {
        val scheduler = createTestScheduler()
        scheduler.start()
        val tool = CronTool(scheduler)

        val result = tool.execute("""{"action": "nope"}""", context)
        assertTrue(result.contains("Error"))

        scheduler.stop()
    }
}
