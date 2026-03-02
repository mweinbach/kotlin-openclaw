package ai.openclaw.core.security

import ai.openclaw.core.model.ApprovalsConfig
import ai.openclaw.core.model.ExecApprovalForwardingConfig
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApprovalManagerTest {

    @Test
    fun `no policy means auto-approve`() = runTest {
        val manager = ApprovalManager()
        val decision = manager.checkApproval("exec", "{}", "main", "session-1")
        assertEquals(ApprovalDecision.APPROVED, decision)
    }

    @Test
    fun `config policy with exec disabled auto-approves`() = runTest {
        val config = ApprovalsConfig(exec = ExecApprovalForwardingConfig(enabled = false))
        val policy = ConfigBasedApprovalPolicy(config)
        val manager = ApprovalManager(policy = policy)
        val decision = manager.checkApproval("exec", "{}", "main", "session-1")
        assertEquals(ApprovalDecision.APPROVED, decision)
    }

    @Test
    fun `config policy with exec enabled requires approval for exec tool`() = runTest {
        val config = ApprovalsConfig(exec = ExecApprovalForwardingConfig(enabled = true))
        val policy = ConfigBasedApprovalPolicy(config)
        val manager = ApprovalManager(policy = policy, defaultTimeoutMs = 500)

        // Launch approval check in background
        val result = kotlinx.coroutines.CompletableDeferred<ApprovalDecision>()
        launch {
            result.complete(manager.checkApproval("exec", "ls -la", "main", "session-1"))
        }

        // Wait for the pending request to appear
        var retries = 10
        while (manager.pendingRequests().isEmpty() && retries > 0) {
            kotlinx.coroutines.delay(50)
            retries--
        }

        val pending = manager.pendingRequests()
        assertEquals(1, pending.size)
        assertEquals("exec", pending[0].toolName)

        // Approve it
        assertTrue(manager.approve(pending[0].id))
        assertEquals(ApprovalDecision.APPROVED, result.await())
    }

    @Test
    fun `deny returns denied decision`() = runTest {
        val config = ApprovalsConfig(exec = ExecApprovalForwardingConfig(enabled = true))
        val policy = ConfigBasedApprovalPolicy(config)
        val manager = ApprovalManager(policy = policy, defaultTimeoutMs = 5000)

        val result = kotlinx.coroutines.CompletableDeferred<ApprovalDecision>()
        launch {
            result.complete(manager.checkApproval("exec", "rm -rf /", "main", "session-1"))
        }

        var retries = 10
        while (manager.pendingRequests().isEmpty() && retries > 0) {
            kotlinx.coroutines.delay(50)
            retries--
        }

        val pending = manager.pendingRequests()
        assertTrue(manager.deny(pending[0].id))
        assertEquals(ApprovalDecision.DENIED, result.await())
    }

    @Test
    fun `timeout returns timed-out decision`() = runTest {
        val config = ApprovalsConfig(exec = ExecApprovalForwardingConfig(enabled = true))
        val policy = ConfigBasedApprovalPolicy(config)
        val manager = ApprovalManager(policy = policy, defaultTimeoutMs = 100)

        val decision = manager.checkApproval("exec", "ls", "main", "session-1")
        assertEquals(ApprovalDecision.TIMED_OUT, decision)
    }

    @Test
    fun `non-exec tools are auto-approved even with policy`() = runTest {
        val config = ApprovalsConfig(exec = ExecApprovalForwardingConfig(enabled = true))
        val policy = ConfigBasedApprovalPolicy(config)
        val manager = ApprovalManager(policy = policy)
        val decision = manager.checkApproval("read_file", "{}", "main", "session-1")
        assertEquals(ApprovalDecision.APPROVED, decision)
    }

    @Test
    fun `agent filter restricts which agents need approval`() = runTest {
        val config = ApprovalsConfig(
            exec = ExecApprovalForwardingConfig(
                enabled = true,
                agentFilter = listOf("restricted-agent"),
            )
        )
        val policy = ConfigBasedApprovalPolicy(config)
        val manager = ApprovalManager(policy = policy)

        // main agent should auto-approve
        val decision = manager.checkApproval("exec", "{}", "main", "session-1")
        assertEquals(ApprovalDecision.APPROVED, decision)
    }

    @Test
    fun `events are emitted for approval requests`() = runTest {
        val config = ApprovalsConfig(exec = ExecApprovalForwardingConfig(enabled = true))
        val policy = ConfigBasedApprovalPolicy(config)
        val manager = ApprovalManager(policy = policy, defaultTimeoutMs = 100)

        val events = mutableListOf<ApprovalEvent>()
        val collectJob = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            manager.events.collect { events.add(it) }
        }

        // Allow collector to subscribe
        kotlinx.coroutines.yield()

        manager.checkApproval("exec", "ls", "main", "session-1")

        // Allow events to propagate
        kotlinx.coroutines.yield()

        assertTrue(events.any { it is ApprovalEvent.ApprovalRequired })
        assertTrue(events.any { it is ApprovalEvent.ApprovalResolved })

        collectJob.cancel()
    }
}
