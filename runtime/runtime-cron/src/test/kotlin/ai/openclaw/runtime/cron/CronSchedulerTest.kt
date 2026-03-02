package ai.openclaw.runtime.cron

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CronSchedulerTest {

    private fun createInMemoryStore(): CronStore {
        val tempFile = kotlin.io.path.createTempFile("cron-test-", ".json").toFile()
        tempFile.deleteOnExit()
        return CronStore(tempFile.absolutePath)
    }

    private var executedJobs = mutableListOf<CronJob>()

    private val testExecutor = CronJobExecutor { job ->
        executedJobs.add(job)
    }

    @Test
    fun `add and list jobs`() = runTest {
        val store = createInMemoryStore()
        val scheduler = CronScheduler(store, testExecutor)
        scheduler.start()

        val job = scheduler.add(
            name = "Test Job",
            schedule = CronSchedule.Every(everyMs = 60_000),
            payload = CronPayload.AgentTurn(message = "hello"),
            agentId = "main",
        )

        assertNotNull(job.id)
        assertEquals("Test Job", job.name)
        assertTrue(job.enabled)

        val jobs = scheduler.list()
        assertEquals(1, jobs.size)
        assertEquals("Test Job", jobs[0].name)

        scheduler.stop()
    }

    @Test
    fun `remove job`() = runTest {
        val store = createInMemoryStore()
        val scheduler = CronScheduler(store, testExecutor)
        scheduler.start()

        val job = scheduler.add(
            name = "Removable",
            schedule = CronSchedule.Every(everyMs = 60_000),
            payload = CronPayload.SystemEvent(text = "tick"),
        )

        assertTrue(scheduler.remove(job.id))
        assertEquals(0, scheduler.list().size)

        scheduler.stop()
    }

    @Test
    fun `disable and enable job`() = runTest {
        val store = createInMemoryStore()
        val scheduler = CronScheduler(store, testExecutor)
        scheduler.start()

        val job = scheduler.add(
            name = "Toggle",
            schedule = CronSchedule.Every(everyMs = 60_000),
            payload = CronPayload.AgentTurn(message = "test"),
        )

        val disabled = scheduler.setEnabled(job.id, false)
        assertNotNull(disabled)
        assertEquals(false, disabled.enabled)

        val enabled = scheduler.setEnabled(job.id, true)
        assertNotNull(enabled)
        assertEquals(true, enabled.enabled)

        scheduler.stop()
    }

    @Test
    fun `run now executes the job`() = runTest {
        executedJobs.clear()
        val store = createInMemoryStore()
        val scheduler = CronScheduler(store, testExecutor)
        scheduler.start()

        val job = scheduler.add(
            name = "RunNow",
            schedule = CronSchedule.Every(everyMs = 3_600_000), // 1 hour
            payload = CronPayload.AgentTurn(message = "immediate"),
        )

        scheduler.runNow(job.id)
        assertEquals(1, executedJobs.size)
        assertEquals("RunNow", executedJobs[0].name)

        scheduler.stop()
    }

    @Test
    fun `delete after run removes job`() = runTest {
        executedJobs.clear()
        val store = createInMemoryStore()
        val scheduler = CronScheduler(store, testExecutor)
        scheduler.start()

        val job = scheduler.add(
            name = "OneShot",
            schedule = CronSchedule.Every(everyMs = 60_000),
            payload = CronPayload.AgentTurn(message = "once"),
            deleteAfterRun = true,
        )

        scheduler.runNow(job.id)
        assertEquals(1, executedJobs.size)

        // Job should be deleted after execution
        assertNull(scheduler.getJob(job.id))

        scheduler.stop()
    }

    @Test
    fun `at schedule computes next run time`() = runTest {
        val store = createInMemoryStore()
        val futureMs = System.currentTimeMillis() + 3_600_000 // 1 hour from now
        val scheduler = CronScheduler(store, testExecutor)
        scheduler.start()

        val job = scheduler.add(
            name = "AtJob",
            schedule = CronSchedule.At(at = futureMs.toString()),
            payload = CronPayload.AgentTurn(message = "at time"),
        )

        assertNotNull(job.state.nextRunAtMs)
        assertEquals(futureMs, job.state.nextRunAtMs)

        scheduler.stop()
    }

    @Test
    fun `persistence survives restart`() = runTest {
        val store = createInMemoryStore()
        val scheduler1 = CronScheduler(store, testExecutor)
        scheduler1.start()

        scheduler1.add(
            name = "Persistent",
            schedule = CronSchedule.Every(everyMs = 60_000),
            payload = CronPayload.AgentTurn(message = "persisted"),
        )
        scheduler1.stop()

        // Create new scheduler with same store
        val scheduler2 = CronScheduler(store, testExecutor)
        scheduler2.start()

        val jobs = scheduler2.list()
        assertEquals(1, jobs.size)
        assertEquals("Persistent", jobs[0].name)

        scheduler2.stop()
    }
}
