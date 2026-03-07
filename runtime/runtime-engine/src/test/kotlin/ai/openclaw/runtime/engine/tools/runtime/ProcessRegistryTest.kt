package ai.openclaw.runtime.engine.tools.runtime

import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ProcessRegistryTest {

    @Test
    fun `remove kills running session before forgetting it`() {
        val registry = ProcessRegistry(cleanupMs = 60_000)
        val process = startLongRunningProcess()

        try {
            val session = registry.create(
                command = "sleep 30",
                workingDir = tempDir(),
                process = process,
            )

            assertTrue(process.isAlive)
            assertTrue(registry.remove(session.id))
            awaitCondition("removed process to exit") { !process.isAlive }
            assertFalse(process.isAlive)
            assertNull(registry.get(session.id))
        } finally {
            registry.close()
            destroyProcessIfAlive(process)
        }
    }

    @Test
    fun `close kills all live sessions and clears registry`() {
        val registry = ProcessRegistry(cleanupMs = 60_000)
        val first = startLongRunningProcess()
        val second = startLongRunningProcess()

        try {
            registry.create("sleep 30", tempDir(), first)
            registry.create("sleep 30", tempDir(), second)
            assertEquals(2, registry.list().size)

            registry.close()

            awaitCondition("first process to exit after close") { !first.isAlive }
            awaitCondition("second process to exit after close") { !second.isAlive }
            assertTrue(registry.list().isEmpty())
        } finally {
            destroyProcessIfAlive(first)
            destroyProcessIfAlive(second)
        }
    }

    @Test
    fun `abandoned registry cleanup kills live sessions`() {
        val process = startLongRunningProcess()
        val registryRef = abandonRegistry(process)

        try {
            awaitCondition("registry collection", timeoutMs = 10_000) {
                requestGc()
                registryRef.get() == null
            }
            awaitCondition("cleaner to stop abandoned process", timeoutMs = 10_000) {
                requestGc()
                !process.isAlive
            }
            assertFalse(process.isAlive)
        } finally {
            destroyProcessIfAlive(process)
        }
    }

    private fun abandonRegistry(process: Process): WeakReference<ProcessRegistry> {
        lateinit var registryRef: WeakReference<ProcessRegistry>

        fun createAndForget() {
            val registry = ProcessRegistry(cleanupMs = 60_000)
            registry.create(
                command = "sleep 30",
                workingDir = tempDir(),
                process = process,
            )
            registryRef = WeakReference(registry)
        }

        createAndForget()
        return registryRef
    }

    private fun startLongRunningProcess(): Process {
        return ProcessBuilder(shellPath(), "-c", "sleep 30")
            .directory(File(tempDir()))
            .start()
    }

    private fun shellPath(): String {
        return if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
    }

    private fun tempDir(): String {
        return System.getProperty("java.io.tmpdir") ?: "/tmp"
    }

    private fun destroyProcessIfAlive(process: Process) {
        if (!process.isAlive) {
            return
        }
        process.destroyForcibly()
        process.waitFor(2, TimeUnit.SECONDS)
    }

    private fun requestGc() {
        repeat(3) {
            System.gc()
            System.runFinalization()
            Thread.sleep(50)
        }
    }

    private fun awaitCondition(
        description: String,
        timeoutMs: Long = 5_000,
        pollMs: Long = 50,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return
            }
            Thread.sleep(pollMs)
        }
        fail("Timed out waiting for $description")
    }
}
